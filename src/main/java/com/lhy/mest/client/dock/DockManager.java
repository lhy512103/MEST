package com.lhy.mest.client.dock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.fml.loading.FMLPaths;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockEdge;
import com.lhy.mest.client.dock.model.DockInsets;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.DockWorkspaceEditor;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LayoutEngine;
import com.lhy.mest.client.dock.model.LayoutNode;
import com.lhy.mest.client.dock.model.LayoutProjection;
import com.lhy.mest.client.dock.model.LayoutStyle;
import com.lhy.mest.client.dock.model.LayoutTrees;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleMetrics;
import com.lhy.mest.client.dock.model.NodeIds;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.workspace.AtomicFileDockLayoutStore;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;
import com.lhy.mest.client.dock.workspace.DockWorkspaceDefaults;
import com.lhy.mest.client.dock.workspace.LegacyMigrationContext;

/**
 * Runtime adapter between floating module views and the immutable recursive workspace model.
 *
 * <p>The workspace is the only structural source of truth. {@link ModulePanel} instances are stable
 * leaf views; a split never wraps or replaces them. Geometry is projected only after a workspace
 * revision, and slot coordinates are rewritten only when a leaf's effective geometry changes.
 */
public final class DockManager {
    private static final int DIVIDER_THICKNESS = 4;
    private static final int DIVIDER_HIT_PADDING = 2;
    private static final int DROP_ZONE_MIN = 12;
    private static final int DROP_ZONE_MAX = 32;
    private static final int SNAP_DISTANCE = 8;
    private static final int LEAF_DRAG_THRESHOLD = 4;

    private static final int DIVIDER_COLOR = 0xFF777B8C;
    private static final int DIVIDER_HOVER_COLOR = 0xFFACE9FF;
    private static final int DROP_FILL_COLOR = 0x66ACE9FF;
    private static final int DROP_BORDER_COLOR = 0xDDACE9FF;

    private static final LayoutStyle LAYOUT_STYLE =
            new LayoutStyle(DockInsets.NONE, DIVIDER_THICKNESS);

    private final List<ModulePanel> panels = new ArrayList<>();
    private final Map<String, ModulePanel> panelsByModuleId = new LinkedHashMap<>();
    /** Immutable snapshot of {@link #panels}; rebuilt only in {@link #init}. */
    private List<ModulePanel> panelsView = List.of();

    // --- Hot-path caches. All are invalidated whenever the projection is rebuilt ---------------
    /** Cached flattened leaf lists per root id (LayoutTrees.leaves allocates a new list per call). */
    private final Map<String, List<LeafNode>> leavesCache = new HashMap<>();
    /** Cached slot → owning-visible-panel lookups; identity keys, {@code Optional.empty()} = no owner. */
    private Map<Slot, java.util.Optional<ModulePanel>> slotPanelCache;
    /** Cached JEI/EMI exclusion bounds; {@code null} = dirty. */
    private List<DockRect> exclusionCache;

    private ModuleCatalog catalog;
    private ModuleCatalog persistenceCatalog;
    private DockWorkspaceEditor editor;
    private LayoutEngine layoutEngine;
    private DockLayoutPersistence persistence;
    private LegacyMigrationContext migrationContext;

    /** Canonical layout retained for persistence, independent of the current screen viewport. */
    private DockWorkspace workspace;
    /**
     * Viewport-clamped projection source. Bounds in this snapshot are transient and must not mark
     * the canonical workspace dirty when the screen is resized.
     */
    private DockWorkspace viewportWorkspace;
    private LayoutProjection projection;
    private boolean projectionDirty;
    private long workspaceRevision;
    private long persistedRevision = -1;
    /**
     * A corrupt persisted layout is quarantined by the store. Until the player deliberately changes
     * the layout, never write the recovered defaults back to the original path: a close-without-edit
     * must not look like a successful repair, and a failed quarantine may have left the only
     * recoverable copy at that original path.
     */
    private boolean persistenceBlockedAfterLoadFailure;
    private int structureVersion;
    private int screenWidth;
    private int screenHeight;
    private DockWorkspace undoWorkspace;
    private DockWorkspace gestureStartWorkspace;
    /**
     * Original workspace captured when the screen raises a root before dispatching a click to a
     * panel-owned child control. The control may consume the click before {@link #mouseClicked}, so
     * this small two-phase transaction lets that raise either commit on its own or merge into a
     * subsequent dock gesture.
     */
    private DockWorkspace pendingFocusWorkspace;
    private boolean layoutLocked;

    private enum Mode {
        NONE,
        PENDING_LEAF_DRAG,
        DRAG_ROOT,
        RESIZE_ROOT,
        RESIZE_DIVIDER
    }

    private Mode mode = Mode.NONE;
    private String activeRootId;
    private String activeLeafNodeId;
    private String activeSplitId;
    private double grabOffsetX;
    private double grabOffsetY;
    private double pressX;
    private double pressY;
    private DropCandidate dropCandidate;

    public boolean isEmpty() {
        return panels.isEmpty();
    }

    public void init(List<ModulePanel> initialPanels, int screenWidth, int screenHeight) {
        if (initialPanels == null || initialPanels.isEmpty()) {
            throw new IllegalArgumentException("at least one module panel is required");
        }
        this.screenWidth = positiveViewport(screenWidth);
        this.screenHeight = positiveViewport(screenHeight);

        panels.clear();
        panelsByModuleId.clear();
        for (ModulePanel panel : initialPanels) {
            if (panel == null) {
                throw new NullPointerException("module panel");
            }
            if (panelsByModuleId.putIfAbsent(panel.id(), panel) != null) {
                throw new IllegalArgumentException("duplicate module panel id: " + panel.id());
            }
            panel.hosted = false;
            panels.add(panel);
        }
        panelsView = List.copyOf(panels);

        migrationContext = LegacyMigrationContext.currentDockDefaults(this.screenWidth, this.screenHeight);
        catalog = createCatalog(panels, DockInsets.NONE);
        persistenceCatalog = createCatalog(panels, migrationContext.rootInsets());
        editor = new DockWorkspaceEditor(catalog);
        layoutEngine = new LayoutEngine(catalog, LAYOUT_STYLE);
        var codec = new DockLayoutCodec(persistenceCatalog, migrationContext);
        Path path = FMLPaths.CONFIGDIR.get()
                .resolve(MESplicedterminal.MODID)
                .resolve("layout.json");
        persistence = new AtomicFileDockLayoutStore(path, codec);

        boolean loadedCleanV2 = false;
        boolean rewriteMigratedLayout = false;
        persistenceBlockedAfterLoadFailure = false;
        try {
            var loaded = persistence.load();
            if (persistence.lastQuarantinedPath().isPresent()) {
                persistenceBlockedAfterLoadFailure = true;
                MESplicedterminal.LOGGER.warn(
                        "Recovered terminal layout from defaults; corrupt file quarantined at {}",
                        persistence.lastQuarantinedPath().orElseThrow());
            }
            if (loaded.isPresent()) {
                DockLayoutCodec.DecodedLayout decoded = loaded.get();
                workspace = decoded.workspace();
                rewriteMigratedLayout = decoded.needsRewrite();
                loadedCleanV2 = !decoded.needsRewrite();
            } else {
                workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
            }
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to load terminal layout v2, using defaults", e);
            // This also covers a failed quarantine move. In that case the original file may still
            // exist, so it is especially important that a later close hook cannot overwrite it.
            persistenceBlockedAfterLoadFailure = true;
            workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
        }

        viewportWorkspace = clampWorkspaceToViewport(workspace);
        workspaceRevision = 1;
        persistedRevision = loadedCleanV2 ? workspaceRevision : -1;
        undoWorkspace = null;
        gestureStartWorkspace = null;
        pendingFocusWorkspace = null;
        layoutLocked = false;
        mode = Mode.NONE;
        activeRootId = null;
        activeLeafNodeId = null;
        activeSplitId = null;
        dropCandidate = null;
        projectionDirty = true;
        structureVersion++;
        ensureProjection();

        if (rewriteMigratedLayout && !persistenceBlockedAfterLoadFailure) {
            save();
        }
    }

    public void updateViewport(int screenWidth, int screenHeight) {
        int newWidth = positiveViewport(screenWidth);
        int newHeight = positiveViewport(screenHeight);
        if (this.screenWidth == newWidth && this.screenHeight == newHeight) {
            return;
        }
        this.screenWidth = newWidth;
        this.screenHeight = newHeight;
        // Viewport clamping is a transient projection concern. Keep the canonical workspace (and
        // its persisted revision) untouched so a temporary small window does not destroy the user's
        // larger-screen geometry.
        viewportWorkspace = clampWorkspaceToViewport(workspace);
        projectionDirty = true;
    }

    public int structureVersion() {
        return structureVersion;
    }

    /** Stable module-registration order, independent of floating-root Z order. */
    public List<ModulePanel> panels() {
        return panelsView;
    }

    /** Visible floating roots in back-to-front order, suitable for JEI/EMI exclusion zones. */
    public List<DockRect> exclusionBounds() {
        ensureProjection();
        if (exclusionCache == null) {
            var result = new ArrayList<DockRect>();
            for (LayoutProjection.RootPlacement root : projection.roots()) {
                if (root.effectivelyVisible()) {
                    result.add(root.bounds());
                }
            }
            exclusionCache = Collections.unmodifiableList(result);
        }
        return exclusionCache;
    }

    /** Applies a pending model projection. A clean call performs no arrangement or slot writes. */
    public void layoutAll() {
        ensureProjection();
    }

    public void toggleVisible(ModulePanel panel) {
        LeafNode leaf = leafForPanel(panel);
        rememberUndoPoint(workspace);
        DockWorkspace changed = editor.setLeafVisible(workspace, leaf.nodeId(), !leaf.visible());
        replaceWorkspace(changed, false);
        save();
    }

    public void resetLayout() {
        DockWorkspace defaults = DockWorkspaceDefaults.create(
                persistenceCatalog,
                LegacyMigrationContext.currentDockDefaults(screenWidth, screenHeight));
        applyPresetWorkspace(defaults);
    }

    public boolean isLayoutLocked() {
        return layoutLocked;
    }

    public void toggleLayoutLocked() {
        layoutLocked = !layoutLocked;
        mode = Mode.NONE;
        activeRootId = null;
        activeLeafNodeId = null;
        activeSplitId = null;
        dropCandidate = null;
        gestureStartWorkspace = null;
        pendingFocusWorkspace = null;
        structureVersion++;
    }

    public boolean canUndoLayout() {
        return undoWorkspace != null;
    }

    public void undoLayout() {
        if (undoWorkspace == null) {
            return;
        }
        DockWorkspace restore = undoWorkspace;
        undoWorkspace = null;
        persistenceBlockedAfterLoadFailure = false;
        structureVersion++;
        replaceWorkspace(restore, true);
        save();
    }

    /** Compact preset: flatten leaves into default-sized, non-overlapping roots where space allows. */
    public void applyCompactPreset() {
        var roots = new ArrayList<FloatingRoot>();
        int gap = 4;
        int x = gap;
        int y = gap;
        int rowHeight = 0;
        for (ModulePanel panel : panels) {
            LeafNode leaf = leafForPanel(panel);
            int width = Math.min(screenWidth, Math.max(panel.minWidth(), panel.defaultWidth()));
            int height = Math.min(screenHeight, Math.max(panel.minHeight(), panel.defaultHeight()));
            if (x > gap && (long) x + width > screenWidth) {
                x = gap;
                y += rowHeight + gap;
                rowHeight = 0;
            }
            DockRect bounds = clampRectToViewport(new DockRect(x, y, width, height), DockSize.ZERO);
            roots.add(new FloatingRoot(DockWorkspaceDefaults.rootId(panel.id()), bounds, leaf));
            x += width + gap;
            rowHeight = Math.max(rowHeight, height);
        }
        applyPresetWorkspace(new DockWorkspace(roots));
    }

    private void applyPresetWorkspace(DockWorkspace target) {
        if (!target.equals(workspace)) {
            rememberUndoPoint(workspace);
            replaceWorkspace(target, true);
        }
        // Reset and compact are explicit repair actions. They are allowed to replace a
        // quarantined/corrupt file even when the chosen preset is already the active workspace.
        persistenceBlockedAfterLoadFailure = false;
        save();
    }

    public void renderBackground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks,
            ModulePanel.PanelSlotRenderer slotRenderer) {
        ensureProjection();
        ModulePanel hoveredLeaf = topLeafAt(mouseX, mouseY);
        FloatingRoot hoveredRoot = topRootAt(mouseX, mouseY, null);
        DividerHit hoveredDivider = hoveredRoot == null ? null : dividerAt(hoveredRoot, mouseX, mouseY);

        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            for (LeafNode leaf : leavesOf(root)) {
                var placement = projection.visibleLeaf(leaf.nodeId());
                if (placement.isEmpty()) {
                    continue;
                }
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                int routedMouseX = panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                int routedMouseY = panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                panel.renderFrame(graphics, font, routedMouseX, routedMouseY, partialTicks);
                panel.renderBackgroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
                panel.renderSlots(graphics, slotRenderer);
            }
            renderDividers(graphics, root, hoveredDivider);
            // A composite root owns one resize affordance. Leaf panels deliberately do not draw
            // their own grips, otherwise every leaf in a split would expose a dead handle.
            ModulePanel.renderResizeGrip(graphics, root.bounds());
        }
        renderDropHighlight(graphics);
    }

    public void renderForeground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks) {
        ensureProjection();
        FloatingRoot hoveredRoot = topRootAt(mouseX, mouseY, null);
        ModulePanel hoveredLeaf = topLeafAt(mouseX, mouseY);
        // Roots are stored back-to-front. Keep that order for compositing, but route the real
        // cursor only to the topmost root under it; a lower overlapping root must never emit a
        // tooltip/hover state on top of the root that visually owns the cursor.
        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            for (LeafNode leaf : leavesOf(root)) {
                if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                    continue;
                }
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                int routedMouseX = root == hoveredRoot && panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                int routedMouseY = root == hoveredRoot && panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                panel.renderForegroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
            }
        }
    }

    /**
     * Returns a leaf in the topmost floating root at the point. On a divider, returns a representative
     * leaf from that root so the screen still blocks click-through.
     */
    public ModulePanel topPanelAt(double mouseX, double mouseY) {
        ensureProjection();
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        if (root == null) {
            return null;
        }
        ModulePanel exact = leafAt(root, mouseX, mouseY);
        if (exact != null) {
            return exact;
        }
        for (LeafNode leaf : leavesOf(root)) {
            if (projection.visibleLeaf(leaf.nodeId()).isPresent()) {
                return panelsByModuleId.get(leaf.moduleId());
            }
        }
        return null;
    }

    public ModulePanel topLeafAt(double mouseX, double mouseY) {
        ensureProjection();
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        return root == null ? null : leafAt(root, mouseX, mouseY);
    }

    public boolean isEffectivelyVisible(ModulePanel panel) {
        ensureProjection();
        LeafNode leaf = leafForPanel(panel);
        return projection.isEffectivelyVisible(leaf.nodeId());
    }

    /**
     * Returns whether an area owned by a panel is fully inside its visible leaf and not covered by
     * any higher floating root.
     *
     * <p>This is intended for native screen widgets that render after the dock pass. Without this
     * check, such a widget would visually and interactively punch through a root painted above its
     * owning panel.
     */
    public boolean isAreaUnobscured(ModulePanel panel, DockRect area) {
        ensureProjection();
        LeafNode leaf = leafForPanel(panel);
        DockRect leafBounds = projection.visibleLeaf(leaf.nodeId())
                .map(LayoutProjection.LeafPlacement::bounds)
                .orElse(null);
        if (leafBounds == null || !contains(leafBounds, area)) {
            return false;
        }
        String ownerRootId = rootContainingNode(viewportWorkspace, leaf.nodeId()).rootId();
        return isAreaUnobscured(viewportWorkspace.roots(), ownerRootId, area);
    }

    /**
     * Raises the visible root under the pointer before the screen dispatches the click to controls
     * owned by that panel.
     *
     * <p>The change is deliberately left pending. If a child control consumes the click,
     * {@link #commitPanelInteraction()} records and saves the raise. If dock chrome starts a drag or
     * resize, {@link #mouseClicked(double, double, int)} takes over the same snapshot so the whole
     * gesture remains one undoable edit and one save.
     */
    public void beginPanelInteraction(double mouseX, double mouseY) {
        ensureProjection();
        if (layoutLocked || mode != Mode.NONE || pendingFocusWorkspace != null) {
            return;
        }
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        if (root == null || viewportWorkspace.roots().getLast().rootId().equals(root.rootId())) {
            return;
        }
        DockWorkspace beforeInteraction = workspace;
        replaceWorkspace(editor.raiseRoot(workspace, root.rootId()), false);
        if (!beforeInteraction.equals(workspace)) {
            pendingFocusWorkspace = beforeInteraction;
        }
    }

    /** Commits a pre-dispatch root raise when no dock gesture took ownership of it. */
    public void commitPanelInteraction() {
        DockWorkspace beforeInteraction = takePendingFocusWorkspace();
        if (beforeInteraction != null) {
            commitInteraction(beforeInteraction);
        }
    }

    public ModulePanel panelForSlot(Slot slot) {
        ensureProjection();
        Map<Slot, java.util.Optional<ModulePanel>> cache = slotPanelCache;
        if (cache == null) {
            cache = new IdentityHashMap<>();
            slotPanelCache = cache;
        }
        java.util.Optional<ModulePanel> cached = cache.get(slot);
        if (cached == null) {
            cached = java.util.Optional.empty();
            for (ModulePanel panel : panels) {
                if (panel.visible && panel.ownsSlot(slot)) {
                    cached = java.util.Optional.of(panel);
                    break;
                }
            }
            cache.put(slot, cached);
        }
        return cached.orElse(null);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ensureProjection();
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        if (root == null) {
            return false;
        }

        if (layoutLocked) {
            return false;
        }

        DividerHit divider = dividerAt(root, mouseX, mouseY);
        if (button == 1 && divider != null) {
            DockWorkspace beforeInteraction = takeInteractionStartWorkspace();
            rememberUndoPoint(beforeInteraction);
            detachDividerBranch(root, divider, Screen.hasShiftDown());
            return true;
        }
        if (button != 0) {
            return false;
        }

        DockWorkspace beforeInteraction = takeInteractionStartWorkspace();
        boolean rootRaised = !beforeInteraction.equals(workspace);
        if (!viewportWorkspace.roots().getLast().rootId().equals(root.rootId())) {
            replaceWorkspace(editor.raiseRoot(workspace, root.rootId()), false);
            rootRaised = rootRaised || !beforeInteraction.equals(workspace);
            root = rootById(root.rootId());
            divider = dividerAt(root, mouseX, mouseY);
        }

        if (divider != null) {
            mode = Mode.RESIZE_DIVIDER;
            activeRootId = root.rootId();
            activeSplitId = divider.splitNodeId();
            gestureStartWorkspace = beforeInteraction;
            return true;
        }

        if (inRootResizeHandle(root.bounds(), mouseX, mouseY)) {
            mode = Mode.RESIZE_ROOT;
            activeRootId = root.rootId();
            grabOffsetX = mouseX - root.bounds().right();
            grabOffsetY = mouseY - root.bounds().bottom();
            gestureStartWorkspace = beforeInteraction;
            return true;
        }

        ModulePanel leaf = leafAt(root, mouseX, mouseY);
        if (leaf != null && leaf.inTitleBar(mouseX, mouseY)) {
            LeafNode leafNode = leafNodeAt(root, mouseX, mouseY);
            if (leafNode == null) {
                if (rootRaised) {
                    commitInteraction(beforeInteraction);
                }
                return false;
            }
            activeLeafNodeId = leafNode.nodeId();
            pressX = mouseX;
            pressY = mouseY;
            if (root.content().nodeId().equals(leafNode.nodeId())) {
                // A standalone leaf is itself the floating root, so retain the fast path that
                // drags that root immediately.
                mode = Mode.DRAG_ROOT;
                activeRootId = root.rootId();
                grabOffsetX = mouseX - root.bounds().x();
                grabOffsetY = mouseY - root.bounds().y();
            } else {
                // In a composite root, wait for an actual drag before detaching the clicked leaf.
                // A click without movement must not mutate the layout tree.
                mode = Mode.PENDING_LEAF_DRAG;
                activeRootId = root.rootId();
                DockRect leafBounds = projection.boundsFor(leafNode.nodeId()).orElse(root.bounds());
                grabOffsetX = mouseX - leafBounds.x();
                grabOffsetY = mouseY - leafBounds.y();
            }
            dropCandidate = null;
            gestureStartWorkspace = beforeInteraction;
            return true;
        }
        if (rootRaised) {
            // A plain content click can finish without entering a dock gesture. Raising the root is
            // still a persistent layout edit and must be independently undoable.
            commitInteraction(beforeInteraction);
        }
        return false;
    }

    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY) {
        if (button != 0 || mode == Mode.NONE) {
            return false;
        }
        ensureProjection();
        if (mode == Mode.PENDING_LEAF_DRAG) {
            double dx = mouseX - pressX;
            double dy = mouseY - pressY;
            if (dx * dx + dy * dy < (double) LEAF_DRAG_THRESHOLD * LEAF_DRAG_THRESHOLD) {
                return true;
            }
            if (!detachPendingLeaf()) {
                // Detach can become invalid if the projected leaf disappears between press and
                // drag. A root may already have been raised at press time, so finish that edit
                // instead of silently leaving it unsaved and without an undo point.
                finishGesture();
                return true;
            }
            mode = Mode.DRAG_ROOT;
        }
        if (mode == Mode.DRAG_ROOT) {
            FloatingRoot root = rootById(activeRootId);
            DockRect bounds = root.bounds();
            DockRect moved = clampRectToViewport(new DockRect(
                    (int) Math.round(mouseX - grabOffsetX),
                    (int) Math.round(mouseY - grabOffsetY),
                    bounds.width(),
                    bounds.height()), DockSize.ZERO);
            replaceWorkspace(editor.setRootBounds(workspace, root.rootId(), moved), false);
            updateDropCandidate(mouseX, mouseY);
            return true;
        }
        if (mode == Mode.RESIZE_ROOT) {
            FloatingRoot root = rootById(activeRootId);
            DockSize minimum = rootMinimum(root.rootId());
            int width = (int) Math.round(mouseX - grabOffsetX - root.bounds().x());
            int height = (int) Math.round(mouseY - grabOffsetY - root.bounds().y());
            DockRect resized = clampRectToViewport(new DockRect(
                    root.bounds().x(),
                    root.bounds().y(),
                    Math.max(1, width),
                    Math.max(1, height)), minimum);
            replaceWorkspace(editor.setRootBounds(workspace, root.rootId(), resized), false);
            return true;
        }
        if (mode == Mode.RESIZE_DIVIDER) {
            resizeDivider(mouseX, mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || mode == Mode.NONE) {
            return false;
        }
        if (mode == Mode.PENDING_LEAF_DRAG) {
            // A title click that never crossed the drag threshold does not detach the leaf, but it
            // may still have raised a lower root. Commit that Z-order change as one undoable edit.
            finishGesture();
            return true;
        }
        if (mode == Mode.DRAG_ROOT) {
            if (dropCandidate != null) {
                executeDrop(dropCandidate);
            } else {
                snapActiveRoot();
            }
        }
        finishGesture();
        return true;
    }

    private void finishGesture() {
        DockWorkspace beforeInteraction = gestureStartWorkspace;
        mode = Mode.NONE;
        activeRootId = null;
        activeLeafNodeId = null;
        activeSplitId = null;
        dropCandidate = null;
        gestureStartWorkspace = null;
        commitInteraction(beforeInteraction);
    }

    /** Persists only a dirty workspace revision; repeated close/store hooks perform no file IO. */
    public void save() {
        if (persistence == null || workspace == null || persistedRevision == workspaceRevision
                || persistenceBlockedAfterLoadFailure) {
            return;
        }
        try {
            persistence.save(workspace);
            persistedRevision = workspaceRevision;
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to write terminal layout v2", e);
        }
    }

    private void ensureProjection() {
        if (!projectionDirty) {
            return;
        }
        leavesCache.clear();
        slotPanelCache = null;
        exclusionCache = null;
        LayoutProjection next = layoutEngine.project(viewportWorkspace);
        for (FloatingRoot root : viewportWorkspace.roots()) {
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                var placement = next.visibleLeaf(leaf.nodeId());
                boolean visible = placement.isPresent();
                boolean geometryChanged = false;
                if (placement.isPresent()) {
                    DockRect bounds = placement.get().bounds();
                    geometryChanged = panel.x != bounds.x()
                            || panel.y != bounds.y()
                            || panel.width != bounds.width()
                            || panel.height != bounds.height();
                    panel.x = bounds.x();
                    panel.y = bounds.y();
                    panel.width = bounds.width();
                    panel.height = bounds.height();
                }
                boolean stateChanged = panel.visible != visible || panel.hosted;
                panel.visible = visible;
                panel.hosted = false;
                if (geometryChanged || stateChanged) {
                    panel.layoutSlots();
                }
            }
        }
        projection = next;
        projectionDirty = false;
    }

    private void replaceWorkspace(DockWorkspace changed, boolean structural) {
        if (changed.equals(workspace)) {
            return;
        }
        // Every path into replaceWorkspace is a real user layout edit (move, resize, split,
        // visibility, detach, snap, compact, or undo). It is therefore the point at which it is
        // safe to release the post-load-failure write guard.
        persistenceBlockedAfterLoadFailure = false;
        workspace = changed;
        viewportWorkspace = clampWorkspaceToViewport(changed);
        workspaceRevision++;
        projectionDirty = true;
        if (structural) {
            structureVersion++;
        }
        ensureProjection();
    }

    private void rememberUndoPoint(DockWorkspace snapshot) {
        if (snapshot == null || snapshot.equals(workspace) && snapshot.equals(undoWorkspace)) {
            return;
        }
        boolean wasAvailable = undoWorkspace != null;
        undoWorkspace = snapshot;
        if (!wasAvailable) {
            structureVersion++;
        }
    }

    private DockWorkspace takeInteractionStartWorkspace() {
        DockWorkspace pending = takePendingFocusWorkspace();
        return pending != null ? pending : workspace;
    }

    private DockWorkspace takePendingFocusWorkspace() {
        DockWorkspace pending = pendingFocusWorkspace;
        pendingFocusWorkspace = null;
        return pending;
    }

    private void commitInteraction(DockWorkspace beforeInteraction) {
        if (beforeInteraction != null && !beforeInteraction.equals(workspace)) {
            rememberUndoPoint(beforeInteraction);
        }
        save();
    }

    private DockWorkspace clampWorkspaceToViewport(DockWorkspace source) {
        // Sanitize persisted coordinates before the layout engine performs any additions.
        var sanitizedRoots = new ArrayList<FloatingRoot>();
        for (FloatingRoot root : source.roots()) {
            sanitizedRoots.add(root.withBounds(clampRectToViewport(root.bounds(), DockSize.ZERO)));
        }
        DockWorkspace sanitized = new DockWorkspace(sanitizedRoots);
        LayoutProjection measured = layoutEngine.project(sanitized);

        var clampedRoots = new ArrayList<FloatingRoot>();
        for (FloatingRoot root : sanitized.roots()) {
            DockSize minimum = measured.roots().stream()
                    .filter(placement -> placement.rootId().equals(root.rootId()))
                    .findFirst()
                    .map(LayoutProjection.RootPlacement::minimumSize)
                    .orElse(DockSize.ZERO);
            clampedRoots.add(root.withBounds(clampRectToViewport(root.bounds(), minimum)));
        }
        return new DockWorkspace(clampedRoots);
    }

    private DockRect clampRectToViewport(DockRect bounds, DockSize minimum) {
        int width = Math.min(screenWidth, Math.max(bounds.width(), minimum.width()));
        int height = Math.min(screenHeight, Math.max(bounds.height(), minimum.height()));
        width = Math.max(1, width);
        height = Math.max(1, height);
        int maxX = Math.max(0, screenWidth - width);
        int maxY = Math.max(0, screenHeight - height);
        int x = (int) Math.max(0L, Math.min((long) bounds.x(), (long) maxX));
        int y = (int) Math.max(0L, Math.min((long) bounds.y(), (long) maxY));
        return new DockRect(x, y, width, height);
    }

    private void resizeDivider(double mouseX, double mouseY) {
        LayoutNode node = findNode(activeSplitId);
        if (!(node instanceof SplitNode split)) {
            return;
        }
        DockRect bounds = projection.boundsFor(split.nodeId()).orElse(null);
        if (bounds == null) {
            return;
        }
        int available = Math.max(1, split.axis().extent(bounds) - DIVIDER_THICKNESS);
        double coordinate = split.axis() == DockAxis.HORIZONTAL ? mouseX - bounds.x() : mouseY - bounds.y();
        double ratio = coordinate / available;

        DockSize firstMinimum = projection.minimumSizes().getOrDefault(split.first().nodeId(), DockSize.ZERO);
        DockSize secondMinimum = projection.minimumSizes().getOrDefault(split.second().nodeId(), DockSize.ZERO);
        int firstExtent = split.axis().extent(firstMinimum);
        int secondExtent = split.axis().extent(secondMinimum);
        double minimumRatio = 0.05;
        double maximumRatio = 0.95;
        if ((long) firstExtent + secondExtent <= available) {
            minimumRatio = Math.max(minimumRatio, (double) firstExtent / available);
            maximumRatio = Math.min(maximumRatio, 1.0 - (double) secondExtent / available);
        }
        ratio = Math.max(minimumRatio, Math.min(maximumRatio, ratio));
        replaceWorkspace(editor.setSplitRatio(workspace, split.nodeId(), ratio), false);
    }

    private void updateDropCandidate(double mouseX, double mouseY) {
        FloatingRoot targetRoot = topRootAt(mouseX, mouseY, activeRootId);
        if (targetRoot == null) {
            dropCandidate = null;
            return;
        }
        LeafNode targetLeaf = leafNodeAt(targetRoot, mouseX, mouseY);
        if (targetLeaf == null) {
            dropCandidate = null;
            return;
        }
        DockRect bounds = projection.visibleLeaf(targetLeaf.nodeId()).orElseThrow().bounds();
        DockEdge edge = dropEdge(bounds, mouseX, mouseY);
        dropCandidate = edge == null ? null : new DropCandidate(targetLeaf.nodeId(), edge, dropBounds(bounds, edge));
    }

    private void executeDrop(DropCandidate candidate) {
        FloatingRoot draggedRoot = rootById(activeRootId);
        // The candidate and its highlight were computed from the transient viewport projection.
        // Use the matching viewport root for geometry as well; mixing it with canonical (possibly
        // larger-screen) bounds makes a drop jump when the window is temporarily smaller.
        FloatingRoot targetViewportRoot = rootContainingNode(viewportWorkspace, candidate.targetNodeId());
        LayoutNode dragged = draggedRoot.content();
        DockRect targetBounds = projection.boundsFor(candidate.targetNodeId()).orElseThrow();
        int draggedExtent = candidate.edge().axis().extent(draggedRoot.bounds());
        int targetExtent = candidate.edge().axis().extent(targetBounds);
        double draggedFraction = (double) draggedExtent / Math.max(1, draggedExtent + targetExtent);
        double ratio = candidate.edge().draggedFirst() ? draggedFraction : 1.0 - draggedFraction;
        ratio = Math.max(0.2, Math.min(0.8, ratio));

        String splitNodeId = "split:" + NodeIds.random();
        DockWorkspace joined = editor.insertSplit(
                workspace,
                dragged.nodeId(),
                candidate.targetNodeId(),
                candidate.edge(),
                splitNodeId,
                ratio);
        String joinedRootId = rootContainingNode(joined, splitNodeId).rootId();
        DockRect expandedBounds = expandedDockBounds(
                targetViewportRoot.bounds(), draggedRoot.bounds(), candidate.edge());
        joined = editor.setRootBounds(joined, joinedRootId, expandedBounds);
        joined = editor.raiseRoot(joined, joinedRootId);
        replaceWorkspace(joined, true);
    }

    private DockRect expandedDockBounds(DockRect target, DockRect dragged, DockEdge edge) {
        int width = target.width();
        int height = target.height();
        int x = target.x();
        int y = target.y();
        if (edge.axis() == DockAxis.HORIZONTAL) {
            width = (int) Math.min(
                    (long) screenWidth,
                    (long) target.width() + dragged.width() + DIVIDER_THICKNESS);
            height = Math.min(screenHeight, Math.max(target.height(), dragged.height()));
            if (edge == DockEdge.LEFT) {
                x = Math.max(0, target.right() - width);
            }
        } else {
            width = Math.min(screenWidth, Math.max(target.width(), dragged.width()));
            height = (int) Math.min(
                    (long) screenHeight,
                    (long) target.height() + dragged.height() + DIVIDER_THICKNESS);
            if (edge == DockEdge.TOP) {
                y = Math.max(0, target.bottom() - height);
            }
        }
        return clampRectToViewport(new DockRect(x, y, width, height), DockSize.ZERO);
    }

    private boolean detachPendingLeaf() {
        if (activeRootId == null || activeLeafNodeId == null) {
            return false;
        }
        FloatingRoot sourceRoot = rootById(activeRootId);
        LayoutNode node = LayoutTrees.find(sourceRoot.content(), activeLeafNodeId).orElse(null);
        if (!(node instanceof LeafNode)
                || sourceRoot.content().nodeId().equals(activeLeafNodeId)) {
            return false;
        }
        DockRect leafBounds = projection.boundsFor(activeLeafNodeId).orElse(null);
        if (leafBounds == null) {
            return false;
        }

        String newRootId = "root:" + NodeIds.random();
        DockWorkspace changed = editor.detach(workspace, activeLeafNodeId, newRootId, leafBounds);
        changed = editor.raiseRoot(changed, newRootId);
        replaceWorkspace(changed, true);
        activeRootId = newRootId;
        return true;
    }

    private void detachDividerBranch(FloatingRoot sourceRoot, DividerHit divider, boolean detachFirst) {
        LayoutNode node = LayoutTrees.find(sourceRoot.content(), divider.splitNodeId()).orElseThrow();
        if (!(node instanceof SplitNode split)) {
            return;
        }
        LayoutNode detached = detachFirst ? split.first() : split.second();
        LayoutNode remaining = detachFirst ? split.second() : split.first();
        DockRect detachedBounds = projection.boundsFor(detached.nodeId()).orElse(sourceRoot.bounds());
        DockRect remainingBounds = projection.boundsFor(remaining.nodeId()).orElse(sourceRoot.bounds());
        String newRootId = "root:" + NodeIds.random();

        DockWorkspace changed = editor.detach(workspace, detached.nodeId(), newRootId, detachedBounds);
        if (sourceRoot.content().nodeId().equals(split.nodeId())) {
            changed = editor.setRootBounds(changed, sourceRoot.rootId(), remainingBounds);
        }
        changed = editor.raiseRoot(changed, newRootId);
        replaceWorkspace(changed, true);
        save();
    }

    private void snapActiveRoot() {
        FloatingRoot active = rootById(activeRootId);
        DockRect bounds = active.bounds();
        var xCandidates = new ArrayList<Integer>();
        var yCandidates = new ArrayList<Integer>();
        xCandidates.add(0);
        xCandidates.add(screenWidth - bounds.width());
        yCandidates.add(0);
        yCandidates.add(screenHeight - bounds.height());

        for (FloatingRoot target : viewportWorkspace.roots()) {
            if (target.rootId().equals(active.rootId()) || !LayoutTrees.hasVisibleLeaf(target.content())) {
                continue;
            }
            xCandidates.add(target.bounds().x());
            xCandidates.add(target.bounds().right());
            xCandidates.add(target.bounds().x() - bounds.width());
            xCandidates.add(target.bounds().right() - bounds.width());
            yCandidates.add(target.bounds().y());
            yCandidates.add(target.bounds().bottom());
            yCandidates.add(target.bounds().y() - bounds.height());
            yCandidates.add(target.bounds().bottom() - bounds.height());
        }
        int snappedX = closestSnap(bounds.x(), xCandidates);
        int snappedY = closestSnap(bounds.y(), yCandidates);
        DockRect snapped = clampRectToViewport(
                new DockRect(snappedX, snappedY, bounds.width(), bounds.height()),
                rootMinimum(active.rootId()));
        replaceWorkspace(editor.setRootBounds(workspace, active.rootId(), snapped), false);
    }

    private static int closestSnap(int current, List<Integer> candidates) {
        int result = current;
        int distance = SNAP_DISTANCE + 1;
        for (int candidate : candidates) {
            int candidateDistance = Math.abs(candidate - current);
            if (candidateDistance < distance) {
                result = candidate;
                distance = candidateDistance;
            }
        }
        return result;
    }

    /** Cached equivalent of {@code LayoutTrees.leaves(root.content())} for hot paths. */
    private List<LeafNode> leavesOf(FloatingRoot root) {
        List<LeafNode> cached = leavesCache.get(root.rootId());
        if (cached == null) {
            cached = LayoutTrees.leaves(root.content());
            leavesCache.put(root.rootId(), cached);
        }
        return cached;
    }

    private FloatingRoot topRootAt(double mouseX, double mouseY, String excludedRootId) {
        for (int i = viewportWorkspace.roots().size() - 1; i >= 0; i--) {
            FloatingRoot root = viewportWorkspace.roots().get(i);
            if (root.rootId().equals(excludedRootId) || !LayoutTrees.hasVisibleLeaf(root.content())) {
                continue;
            }
            if (contains(root.bounds(), mouseX, mouseY)) {
                return root;
            }
        }
        return null;
    }

    private ModulePanel leafAt(FloatingRoot root, double mouseX, double mouseY) {
        LeafNode leaf = leafNodeAt(root, mouseX, mouseY);
        return leaf == null ? null : panelsByModuleId.get(leaf.moduleId());
    }

    private LeafNode leafNodeAt(FloatingRoot root, double mouseX, double mouseY) {
        List<LeafNode> leaves = leavesOf(root);
        for (int i = leaves.size() - 1; i >= 0; i--) {
            LeafNode leaf = leaves.get(i);
            var placement = projection.visibleLeaf(leaf.nodeId());
            if (placement.isPresent() && contains(placement.get().bounds(), mouseX, mouseY)) {
                return leaf;
            }
        }
        return null;
    }

    private DividerHit dividerAt(FloatingRoot root, double mouseX, double mouseY) {
        for (LayoutProjection.DividerPlacement divider : projection.dividers()) {
            if (!LayoutTrees.contains(root.content(), divider.splitNodeId())) {
                continue;
            }
            DockRect bounds = divider.bounds();
            DockRect hitBounds = new DockRect(
                    bounds.x() - DIVIDER_HIT_PADDING,
                    bounds.y() - DIVIDER_HIT_PADDING,
                    bounds.width() + 2 * DIVIDER_HIT_PADDING,
                    bounds.height() + 2 * DIVIDER_HIT_PADDING);
            if (contains(hitBounds, mouseX, mouseY)) {
                return new DividerHit(divider.splitNodeId(), bounds);
            }
        }
        return null;
    }

    private void renderDividers(GuiGraphics graphics, FloatingRoot root, DividerHit hoveredDivider) {
        for (LayoutProjection.DividerPlacement divider : projection.dividers()) {
            if (!LayoutTrees.contains(root.content(), divider.splitNodeId())) {
                continue;
            }
            DockRect bounds = divider.bounds();
            int color = hoveredDivider != null && hoveredDivider.splitNodeId().equals(divider.splitNodeId())
                    ? DIVIDER_HOVER_COLOR
                    : DIVIDER_COLOR;
            graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), color);
        }
    }

    private void renderDropHighlight(GuiGraphics graphics) {
        if (dropCandidate == null) {
            return;
        }
        DockRect bounds = dropCandidate.highlightBounds();
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), DROP_FILL_COLOR);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.y() + 1, DROP_BORDER_COLOR);
        graphics.fill(bounds.x(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), DROP_BORDER_COLOR);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 1, bounds.bottom(), DROP_BORDER_COLOR);
        graphics.fill(bounds.right() - 1, bounds.y(), bounds.right(), bounds.bottom(), DROP_BORDER_COLOR);
    }

    private boolean rootEffectivelyVisible(String rootId) {
        for (LayoutProjection.RootPlacement root : projection.roots()) {
            if (root.rootId().equals(rootId)) {
                return root.effectivelyVisible();
            }
        }
        return false;
    }

    private DockSize rootMinimum(String rootId) {
        for (LayoutProjection.RootPlacement root : projection.roots()) {
            if (root.rootId().equals(rootId)) {
                return root.minimumSize();
            }
        }
        return DockSize.ZERO;
    }

    private FloatingRoot rootById(String rootId) {
        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (root.rootId().equals(rootId)) {
                return root;
            }
        }
        throw new IllegalArgumentException("unknown root: " + rootId);
    }

    private LayoutNode findNode(String nodeId) {
        for (FloatingRoot root : workspace.roots()) {
            var node = LayoutTrees.find(root.content(), nodeId);
            if (node.isPresent()) {
                return node.get();
            }
        }
        throw new IllegalArgumentException("unknown node: " + nodeId);
    }

    private LeafNode leafForPanel(ModulePanel panel) {
        if (panel == null || panelsByModuleId.get(panel.id()) != panel) {
            throw new IllegalArgumentException("panel is not registered with this dock");
        }
        for (FloatingRoot root : workspace.roots()) {
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                if (leaf.moduleId().equals(panel.id())) {
                    return leaf;
                }
            }
        }
        throw new IllegalStateException("workspace is missing module " + panel.id());
    }

    private static FloatingRoot rootContainingNode(DockWorkspace workspace, String nodeId) {
        return workspace.roots().stream()
                .filter(root -> LayoutTrees.contains(root.content(), nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown node: " + nodeId));
    }

    private static DockEdge dropEdge(DockRect bounds, double mouseX, double mouseY) {
        int zone = Math.max(
                DROP_ZONE_MIN,
                Math.min(DROP_ZONE_MAX, Math.min(bounds.width(), bounds.height()) / 4));
        double left = mouseX - bounds.x();
        double right = bounds.right() - mouseX;
        double top = mouseY - bounds.y();
        double bottom = bounds.bottom() - mouseY;
        double minimum = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (minimum > zone) {
            return null;
        }
        if (minimum == left) {
            return DockEdge.LEFT;
        }
        if (minimum == right) {
            return DockEdge.RIGHT;
        }
        if (minimum == top) {
            return DockEdge.TOP;
        }
        return DockEdge.BOTTOM;
    }

    private static DockRect dropBounds(DockRect target, DockEdge edge) {
        int width = edge.axis() == DockAxis.HORIZONTAL ? Math.max(1, target.width() / 3) : target.width();
        int height = edge.axis() == DockAxis.VERTICAL ? Math.max(1, target.height() / 3) : target.height();
        int x = edge == DockEdge.RIGHT ? target.right() - width : target.x();
        int y = edge == DockEdge.BOTTOM ? target.bottom() - height : target.y();
        return new DockRect(x, y, width, height);
    }

    private static boolean inRootResizeHandle(DockRect bounds, double mouseX, double mouseY) {
        return mouseX >= bounds.right() - ModulePanel.RESIZE_HANDLE
                && mouseX < bounds.right()
                && mouseY >= bounds.bottom() - ModulePanel.RESIZE_HANDLE
                && mouseY < bounds.bottom();
    }

    private static boolean contains(DockRect bounds, double mouseX, double mouseY) {
        return mouseX >= bounds.x()
                && mouseX < bounds.x() + (double) bounds.width()
                && mouseY >= bounds.y()
                && mouseY < bounds.y() + (double) bounds.height();
    }

    private static boolean contains(DockRect outer, DockRect inner) {
        return inner.x() >= outer.x()
                && inner.y() >= outer.y()
                && inner.right() <= outer.right()
                && inner.bottom() <= outer.bottom();
    }

    static boolean isAreaUnobscured(List<FloatingRoot> roots, String ownerRootId, DockRect area) {
        int ownerIndex = -1;
        for (int i = 0; i < roots.size(); i++) {
            FloatingRoot root = roots.get(i);
            if (root.rootId().equals(ownerRootId)) {
                if (!LayoutTrees.hasVisibleLeaf(root.content()) || !contains(root.bounds(), area)) {
                    return false;
                }
                ownerIndex = i;
                break;
            }
        }
        if (ownerIndex < 0) {
            return false;
        }
        for (int i = ownerIndex + 1; i < roots.size(); i++) {
            FloatingRoot higher = roots.get(i);
            if (LayoutTrees.hasVisibleLeaf(higher.content()) && intersects(higher.bounds(), area)) {
                return false;
            }
        }
        return true;
    }

    private static boolean intersects(DockRect first, DockRect second) {
        return first.x() < second.right()
                && first.right() > second.x()
                && first.y() < second.bottom()
                && first.bottom() > second.y();
    }

    private static int positiveViewport(int extent) {
        return Math.max(1, extent);
    }

    private static ModuleCatalog createCatalog(List<ModulePanel> panels, DockInsets excludedInsets) {
        var entries = new LinkedHashMap<String, ModuleMetrics>();
        for (ModulePanel panel : panels) {
            int minimumWidth = Math.max(1, panel.minWidth() - excludedInsets.horizontal());
            int minimumHeight = Math.max(1, panel.minHeight() - excludedInsets.vertical());
            int defaultWidth = Math.max(minimumWidth, panel.defaultWidth() - excludedInsets.horizontal());
            int defaultHeight = Math.max(minimumHeight, panel.defaultHeight() - excludedInsets.vertical());
            entries.put(
                    panel.id(),
                    new ModuleMetrics(
                            new DockSize(minimumWidth, minimumHeight),
                            new DockSize(defaultWidth, defaultHeight)));
        }
        return new ModuleCatalog(entries);
    }

    private record DividerHit(String splitNodeId, DockRect bounds) {
    }

    private record DropCandidate(String targetNodeId, DockEdge edge, DockRect highlightBounds) {
    }
}
