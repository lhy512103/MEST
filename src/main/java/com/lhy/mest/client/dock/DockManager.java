package com.lhy.mest.client.dock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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

    private static final int DIVIDER_COLOR = 0xFF777B8C;
    private static final int DIVIDER_HOVER_COLOR = 0xFFACE9FF;
    private static final int DROP_FILL_COLOR = 0x66ACE9FF;
    private static final int DROP_BORDER_COLOR = 0xDDACE9FF;

    private static final LayoutStyle LAYOUT_STYLE =
            new LayoutStyle(DockInsets.NONE, DIVIDER_THICKNESS);

    private final List<ModulePanel> panels = new ArrayList<>();
    private final Map<String, ModulePanel> panelsByModuleId = new LinkedHashMap<>();

    private ModuleCatalog catalog;
    private ModuleCatalog persistenceCatalog;
    private DockWorkspaceEditor editor;
    private LayoutEngine layoutEngine;
    private DockLayoutPersistence persistence;
    private LegacyMigrationContext migrationContext;

    private DockWorkspace workspace;
    private LayoutProjection projection;
    private boolean projectionDirty;
    private long workspaceRevision;
    private long persistedRevision = -1;
    private int structureVersion;
    private int screenWidth;
    private int screenHeight;
    private DockWorkspace undoWorkspace;
    private DockWorkspace gestureStartWorkspace;
    private boolean layoutLocked;

    private enum Mode {
        NONE,
        DRAG_ROOT,
        RESIZE_ROOT,
        RESIZE_DIVIDER
    }

    private Mode mode = Mode.NONE;
    private String activeRootId;
    private String activeSplitId;
    private double grabOffsetX;
    private double grabOffsetY;
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
        try {
            var loaded = persistence.load();
            if (loaded.isPresent()) {
                DockLayoutCodec.DecodedLayout decoded = loaded.get();
                workspace = decoded.workspace();
                rewriteMigratedLayout = decoded.migrated();
                loadedCleanV2 = !decoded.migrated();
            } else {
                workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
            }
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to load terminal layout v2, using defaults", e);
            workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
        }

        DockWorkspace clamped = clampWorkspaceToViewport(workspace);
        boolean geometryRecovered = !clamped.equals(workspace);
        workspace = clamped;
        workspaceRevision = 1;
        persistedRevision = loadedCleanV2 && !geometryRecovered ? workspaceRevision : -1;
        undoWorkspace = null;
        gestureStartWorkspace = null;
        layoutLocked = false;
        projectionDirty = true;
        structureVersion++;
        ensureProjection();

        if (rewriteMigratedLayout) {
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
        DockWorkspace clamped = clampWorkspaceToViewport(workspace);
        if (!clamped.equals(workspace)) {
            replaceWorkspace(clamped, false);
        }
    }

    public int structureVersion() {
        return structureVersion;
    }

    /** Stable module-registration order, independent of floating-root Z order. */
    public List<ModulePanel> panels() {
        return List.copyOf(panels);
    }

    /** Visible floating roots in back-to-front order, suitable for JEI/EMI exclusion zones. */
    public List<DockRect> exclusionBounds() {
        ensureProjection();
        var result = new ArrayList<DockRect>();
        for (LayoutProjection.RootPlacement root : projection.roots()) {
            if (root.effectivelyVisible()) {
                result.add(root.bounds());
            }
        }
        return List.copyOf(result);
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
        rememberUndoPoint(workspace);
        DockWorkspace defaults = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
        replaceWorkspace(clampWorkspaceToViewport(defaults), true);
        save();
    }

    public boolean isLayoutLocked() {
        return layoutLocked;
    }

    public void toggleLayoutLocked() {
        layoutLocked = !layoutLocked;
        mode = Mode.NONE;
        activeRootId = null;
        activeSplitId = null;
        dropCandidate = null;
        gestureStartWorkspace = null;
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
        structureVersion++;
        replaceWorkspace(restore, true);
        save();
    }

    /** Compact preset: flatten leaves into default-sized, non-overlapping roots where space allows. */
    public void applyCompactPreset() {
        rememberUndoPoint(workspace);
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
        replaceWorkspace(new DockWorkspace(roots), true);
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

        for (FloatingRoot root : workspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
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
        ModulePanel hoveredLeaf = topLeafAt(mouseX, mouseY);
        for (FloatingRoot root : workspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                    continue;
                }
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                int routedMouseX = panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                int routedMouseY = panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
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
        for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
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

    public ModulePanel panelForSlot(Slot slot) {
        ensureProjection();
        for (ModulePanel panel : panels) {
            if (panel.visible && panel.ownsSlot(slot)) {
                return panel;
            }
        }
        return null;
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

        DockWorkspace beforeInteraction = workspace;

        DividerHit divider = dividerAt(root, mouseX, mouseY);
        if (button == 1 && divider != null) {
            rememberUndoPoint(beforeInteraction);
            detachDividerBranch(root, divider);
            return true;
        }
        if (button != 0) {
            return false;
        }

        if (!workspace.roots().getLast().rootId().equals(root.rootId())) {
            replaceWorkspace(editor.raiseRoot(workspace, root.rootId()), false);
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
            mode = Mode.DRAG_ROOT;
            activeRootId = root.rootId();
            grabOffsetX = mouseX - root.bounds().x();
            grabOffsetY = mouseY - root.bounds().y();
            dropCandidate = null;
            gestureStartWorkspace = beforeInteraction;
            return true;
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
        if (mode == Mode.DRAG_ROOT) {
            if (dropCandidate != null) {
                executeDrop(dropCandidate);
            } else {
                snapActiveRoot();
            }
        }
        if (gestureStartWorkspace != null && !gestureStartWorkspace.equals(workspace)) {
            rememberUndoPoint(gestureStartWorkspace);
        }
        mode = Mode.NONE;
        activeRootId = null;
        activeSplitId = null;
        dropCandidate = null;
        gestureStartWorkspace = null;
        save();
        return true;
    }

    /** Persists only a dirty workspace revision; repeated close/store hooks perform no file IO. */
    public void save() {
        if (persistence == null || workspace == null || persistedRevision == workspaceRevision) {
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
        LayoutProjection next = layoutEngine.project(workspace);
        for (FloatingRoot root : workspace.roots()) {
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
        DockWorkspace clamped = clampWorkspaceToViewport(changed);
        if (clamped.equals(workspace)) {
            return;
        }
        workspace = clamped;
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
        FloatingRoot targetRoot = rootContainingNode(workspace, candidate.targetNodeId());
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
        DockRect expandedBounds = expandedDockBounds(targetRoot.bounds(), draggedRoot.bounds(), candidate.edge());
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

    private void detachDividerBranch(FloatingRoot sourceRoot, DividerHit divider) {
        LayoutNode node = LayoutTrees.find(sourceRoot.content(), divider.splitNodeId()).orElseThrow();
        if (!(node instanceof SplitNode split)) {
            return;
        }
        LayoutNode detached = split.second();
        DockRect detachedBounds = projection.boundsFor(detached.nodeId()).orElse(sourceRoot.bounds());
        DockRect remainingBounds = projection.boundsFor(split.first().nodeId()).orElse(sourceRoot.bounds());
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
        int snappedX = closestSnap(bounds.x(), 0, screenWidth - bounds.width());
        int snappedY = closestSnap(bounds.y(), 0, screenHeight - bounds.height());

        for (FloatingRoot target : workspace.roots()) {
            if (target.rootId().equals(active.rootId()) || !LayoutTrees.hasVisibleLeaf(target.content())) {
                continue;
            }
            snappedX = closestSnap(
                    snappedX,
                    target.bounds().x(),
                    target.bounds().right(),
                    target.bounds().x() - bounds.width(),
                    target.bounds().right() - bounds.width());
            snappedY = closestSnap(
                    snappedY,
                    target.bounds().y(),
                    target.bounds().bottom(),
                    target.bounds().y() - bounds.height(),
                    target.bounds().bottom() - bounds.height());
        }
        DockRect snapped = clampRectToViewport(
                new DockRect(snappedX, snappedY, bounds.width(), bounds.height()),
                rootMinimum(active.rootId()));
        replaceWorkspace(editor.setRootBounds(workspace, active.rootId(), snapped), false);
    }

    private static int closestSnap(int current, int... candidates) {
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

    private FloatingRoot topRootAt(double mouseX, double mouseY, String excludedRootId) {
        for (int i = workspace.roots().size() - 1; i >= 0; i--) {
            FloatingRoot root = workspace.roots().get(i);
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
        List<LeafNode> leaves = LayoutTrees.leaves(root.content());
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
        return projection.roots().stream()
                .anyMatch(root -> root.rootId().equals(rootId) && root.effectivelyVisible());
    }

    private DockSize rootMinimum(String rootId) {
        return projection.roots().stream()
                .filter(root -> root.rootId().equals(rootId))
                .findFirst()
                .map(LayoutProjection.RootPlacement::minimumSize)
                .orElse(DockSize.ZERO);
    }

    private FloatingRoot rootById(String rootId) {
        return workspace.roots().stream()
                .filter(root -> root.rootId().equals(rootId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown root: " + rootId));
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
