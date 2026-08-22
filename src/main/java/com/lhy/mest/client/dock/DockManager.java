package com.lhy.mest.client.dock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.model.ModuleMetrics;
import com.lhy.mest.client.dock.model.WorkspaceValidator;
import com.lhy.mest.client.dock.model.NodeIds;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.workspace.AtomicFileDockLayoutStore;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutDto;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;
import com.lhy.mest.client.dock.workspace.DockWorkspaceDefaults;
import com.lhy.mest.client.dock.workspace.LegacyMigrationContext;
import com.lhy.mest.client.panel.PatternAccessPanel;

/**
 * Runtime adapter between floating module views and the immutable recursive workspace model.
 *
 * <p>The workspace is the only structural source of truth. {@link ModulePanel} instances are stable
 * leaf views; a split never wraps or replaces them. Geometry is projected only after a workspace
 * revision, and slot coordinates are rewritten only when a leaf's effective geometry changes.
 */
public final class DockManager {
    private static final int DIVIDER_THICKNESS = 0;
    private static final int DIVIDER_HIT_PADDING = 3;
    private static final int DROP_ZONE_MIN = 8;
    private static final int DROP_ZONE_MAX = 16;
    private static final int SNAP_DISTANCE = 8;
    private static final int LEAF_DRAG_THRESHOLD = 4;
    private static final int MAX_UNDO_HISTORY = 32;

    private static final int DIVIDER_COLOR = 0xFF777B8C;
    private static final int DIVIDER_HOVER_COLOR = 0xFFACE9FF;
    private static final int DROP_FILL_COLOR = 0x66ACE9FF;
    private static final int DROP_BORDER_COLOR = 0xDDACE9FF;
    /** Faint fill for the four possible splice zones shown while a root drag hovers a leaf. */
    private static final int DROP_ZONE_FILL_COLOR = 0x33ACE9FF;
    /**
     * {@link net.minecraft.client.gui.GuiGraphics#renderItem} draws at local z=150 and count
     * labels at z=200. Panel chrome uses the current pose z, so each floating root must sit on
     * its own layer or every lower window's items composite above every later window's frame.
     */
    private static final float ROOT_LAYER_Z = 400.0F;
    /** Lift per-root foreground (buttons, labels) above that root's own item icons. */
    private static final float FOREGROUND_Z = 250.0F;
    private int anchoredChromeLayer;

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
    private final ArrayDeque<DockWorkspace> undoStack = new ArrayDeque<>();
    private DockWorkspace gestureStartWorkspace;
    /**
     * Original workspace captured when the screen raises a root before dispatching a click to a
     * panel-owned child control. The control may consume the click before {@link #mouseClicked}, so
     * this small two-phase transaction lets that raise either commit on its own or merge into a
     * subsequent dock gesture.
     */
    private DockWorkspace pendingFocusWorkspace;
    private boolean layoutLocked;
    private boolean editingLayout;
    private DockWorkspace editingOriginal;
    private boolean centerOnReturn = true;
    private boolean pendingCenterOnReturn;
    private Path preferencesPath;
    private int editorInsetLeft;
    private int editorInsetTop;
    private int editorInsetRight;
    private int editorInsetBottom;

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
    private boolean moveAllRoots;
    private DropCandidate dropCandidate;
    /** Bounds of the leaf under the pointer while dragging a root; drives the splice-zone affordances. */
    private DockRect dragHoverBounds;

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
        preferencesPath = path.getParent().resolve("preferences.json");
        loadPreferences();
        persistence = new AtomicFileDockLayoutStore(path, codec);

        boolean loadedCurrentLayout = false;
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
                loadedCurrentLayout = !decoded.needsRewrite();
            } else {
                workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
            }
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn(
                    "Failed to load terminal layout v{}, using defaults",
                    DockLayoutDto.CURRENT_VERSION,
                    e);
            // This also covers a failed quarantine move. In that case the original file may still
            // exist, so it is especially important that a later close hook cannot overwrite it.
            persistenceBlockedAfterLoadFailure = true;
            workspace = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
        }

        viewportWorkspace = clampWorkspaceToViewport(workspace);
        workspaceRevision = 1;
        persistedRevision = loadedCurrentLayout ? workspaceRevision : -1;
        undoStack.clear();
        gestureStartWorkspace = null;
        pendingFocusWorkspace = null;
        mode = Mode.NONE;
        activeRootId = null;
        activeLeafNodeId = null;
        activeSplitId = null;
        dropCandidate = null;
        projectionDirty = true;
        structureVersion++;
        ensureProjection();
        if (ensureAnchoredVisible()) {
            save();
        } else if (rewriteMigratedLayout && !persistenceBlockedAfterLoadFailure) {
            save();
        }
    }

    /**
     * Saved layouts from before the anchored-group rule can leave every visible window floating,
     * which hides the toolbar (and the layout editor). Recover by pinning one panel back to the
     * terminal group.
     */
    public boolean ensureAnchoredVisible() {
        if (hasVisibleAnchored()) {
            return false;
        }
        ModulePanel pick = null;
        for (ModulePanel panel : panels) {
            if (workspace.policyFor(panel.id()).visible()) {
                pick = panel;
                break;
            }
        }
        if (pick == null) {
            pick = panelsByModuleId.get("me_list");
        }
        if (pick == null && !panels.isEmpty()) {
            pick = panels.getFirst();
        }
        if (pick == null) {
            return false;
        }
        setModulePolicy(pick, policyFor(pick).withVisible(true).withFloating(false));
        return hasVisibleAnchored();
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

    public DockWorkspace beginLayoutEditing() {
        editingLayout = true;
        editingOriginal = workspace;
        return workspace;
    }

    public void cancelLayoutEditing() {
        if (editingLayout && editingOriginal != null && !editingOriginal.equals(workspace)) {
            replaceWorkspace(editingOriginal, true);
        }
        clearEditorCanvasInsets();
        editingLayout = false;
        editingOriginal = null;
        // The editor can be closed (Esc) in the middle of a gesture; dropping the gesture state
        // here prevents a later mouse event from acting on a root that no longer exists.
        resetGestureState();
    }

    public void commitLayoutEditing() {
        clearEditorCanvasInsets();
        editingLayout = false;
        editingOriginal = null;
        resetGestureState();
        if (centerOnReturn) {
            centerVisibleWorkspace();
            pendingCenterOnReturn = true;
        }
        save();
    }

    /**
     * Restricts editor-time clamping so title bars stay on the canvas instead of sliding under
     * the editor chrome. Insets are transient and do not rewrite the canonical workspace until
     * the player actually moves a window.
     */
    public void setEditorCanvasInsets(int left, int top, int right, int bottom) {
        if (!editingLayout) {
            return;
        }
        int newLeft = Math.max(0, left);
        int newTop = Math.max(0, top);
        int newRight = Math.max(0, right);
        int newBottom = Math.max(0, bottom);
        if (editorInsetLeft == newLeft && editorInsetTop == newTop
                && editorInsetRight == newRight && editorInsetBottom == newBottom) {
            return;
        }
        editorInsetLeft = newLeft;
        editorInsetTop = newTop;
        editorInsetRight = newRight;
        editorInsetBottom = newBottom;
        viewportWorkspace = clampWorkspaceToViewport(workspace);
        projectionDirty = true;
    }

    public DockWorkspace workspaceSnapshot() {
        return workspace;
    }

    public boolean centerOnReturn() {
        return centerOnReturn;
    }

    public void saveUiPreferences() {
        savePreferences();
    }

    public void setCenterOnReturn(boolean value) {
        if (centerOnReturn == value) {
            return;
        }
        centerOnReturn = value;
        savePreferences();
    }

    public void applyPendingCenter() {
        if (!pendingCenterOnReturn || editingLayout) {
            return;
        }
        pendingCenterOnReturn = false;
        DockWorkspace before = workspace;
        centerVisibleWorkspace();
        if (workspace != before) {
            save();
        }
    }

    public void centerVisibleWorkspace() {
        ensureProjection();
        var movedRootIds = new java.util.LinkedHashSet<String>();
        DockRect union = null;
        for (FloatingRoot root : workspace.roots()) {
            if (!rootInCenterGroup(root)) {
                continue;
            }
            DockRect part = contentChromeBounds(root);
            if (part == null || part.width() <= 0 || part.height() <= 0) {
                continue;
            }
            movedRootIds.add(root.rootId());
            union = union == null ? part : union.union(part);
        }
        DockWorkspace next = editor.centerRoots(workspace, movedRootIds, union, screenWidth, screenHeight);
        if (!next.equals(workspace)) {
            replaceWorkspace(next, true);
        }
    }

    /** Visible framed windows only. Encoding tabs and the ME scroller well are outside chrome. */
    public DockRect anchoredGroupBounds() {
        ensureProjection();
        DockRect union = null;
        for (FloatingRoot root : workspace.roots()) {
            if (!rootInCenterGroup(root)) {
                continue;
            }
            DockRect part = contentChromeBounds(root);
            if (part == null || part.width() <= 0 || part.height() <= 0) {
                continue;
            }
            union = union == null ? part : union.union(part);
        }
        return union;
    }

    private boolean rootInCenterGroup(FloatingRoot root) {
        if (!rootEffectivelyVisible(root.rootId())) {
            return false;
        }
        for (LeafNode leaf : leavesOf(root)) {
            if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                continue;
            }
            if (!workspace.policyFor(leaf.moduleId()).floating()) {
                return true;
            }
        }
        return false;
    }

    public ModuleLayoutPolicy policyFor(ModulePanel panel) {
        return workspace.policyFor(panel.id());
    }

    public void setModulePolicy(ModulePanel panel, ModuleLayoutPolicy policy) {
        if (panel == null || policy == null) {
            throw new NullPointerException("panel and policy");
        }
        LeafNode leaf = leafForPanel(panel);
        ModuleLayoutPolicy current = workspace.policyFor(panel.id());
        DockWorkspace next = workspace;
        if (current.visible() != policy.visible()) {
            DockRect previousBounds = current.visible() ? projectionBounds(leaf.nodeId()) : null;
            next = editor.setLeafVisible(
                    next, leaf.nodeId(), policy.visible(), previousBounds, null, previousBounds);
        }
        var policies = new LinkedHashMap<>(next.policies());
        policies.put(panel.id(), policy);
        next = next.withPolicies(policies);
        if (!hasVisibleAnchored(next)) {
            return;
        }
        replaceWorkspace(next, false);
    }

    /**
     * Extra chrome (toolbar / upgrades) attaches to the non-floating group. At least one visible
     * anchored panel must remain so that group never disappears.
     */
    public boolean hasVisibleAnchored() {
        return hasVisibleAnchored(workspace);
    }

    private boolean hasVisibleAnchored(DockWorkspace candidate) {
        if (catalog == null || candidate == null) {
            return true;
        }
        for (String moduleId : catalog.moduleIds()) {
            ModuleLayoutPolicy policy = candidate.policyFor(moduleId);
            if (policy.visible() && !policy.floating()) {
                return true;
            }
        }
        return false;
    }

    public void togglePinned(ModulePanel panel) {
        if (panel == null || !panel.pinVisible()) {
            return;
        }
        ModuleLayoutPolicy current = workspace.policyFor(panel.id());
        if (!current.floating()) {
            return;
        }
        setModulePolicy(panel, current.withPinned(!current.pinned()));
        save();
    }

    public void applyEditedWorkspace(DockWorkspace edited) {
        WorkspaceValidator.validateStrict(edited, catalog);
        if (!hasVisibleAnchored(edited)) {
            return;
        }
        rememberUndoPoint(workspace);
        replaceWorkspace(edited, true);
        save();
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

    public void relayoutAllSlots() {
        ensureProjection();
        for (ModulePanel panel : panels) {
            panel.layoutSlots();
        }
    }

    public void toggleVisible(ModulePanel panel) {
        LeafNode leaf = leafForPanel(panel);
        boolean currentlyVisible = workspace.policyFor(leaf.moduleId()).visible();
        boolean nextVisible = !currentlyVisible;
        DockRect previousBounds = currentlyVisible ? projectionBounds(leaf.nodeId()) : null;
        rememberUndoPoint(workspace);
        replaceWorkspace(
                editor.setLeafVisible(
                        workspace, leaf.nodeId(), nextVisible, previousBounds, null, previousBounds),
                false);
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
        resetGestureState();
        structureVersion++;
        savePreferences();
    }

    public boolean canUndoLayout() {
        return !undoStack.isEmpty();
    }

    public void undoLayout() {
        if (undoStack.isEmpty()) {
            return;
        }
        DockWorkspace restore = undoStack.removeFirst();
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

    @FunctionalInterface
    public interface AnchoredChromeRenderer {
        void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTicks);
    }

    public void renderBackground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks,
            ModulePanel.PanelSlotRenderer slotRenderer) {
        renderBackground(graphics, font, mouseX, mouseY, partialTicks, slotRenderer, null);
    }

    public void renderBackground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks,
            ModulePanel.PanelSlotRenderer slotRenderer,
            AnchoredChromeRenderer anchoredChrome) {
        ensureProjection();
        ModulePanel hoveredLeaf = topLeafAt(mouseX, mouseY);
        FloatingRoot hoveredRoot = topRootAt(mouseX, mouseY, null);
        DividerHit hoveredDivider = hoveredRoot == null ? null : dividerAt(hoveredRoot, mouseX, mouseY);

        int layer = 0;
        boolean chromeDrawn = false;
        for (FloatingRoot root : paintOrderRoots()) {
            if (!chromeDrawn && !isAnchoredRoot(root)) {
                anchoredChromeLayer = layer;
                if (anchoredChrome != null) {
                    int chromeLayer = layer++;
                    withRootLayer(graphics, chromeLayer, 0.0F, () ->
                            anchoredChrome.render(graphics, font, mouseX, mouseY, partialTicks));
                }
                chromeDrawn = true;
            }
            int rootLayer = layer++;
            withRootLayer(graphics, rootLayer, 0.0F, () -> {
                boolean composite = visibleLeafCount(root) > 1;
                if (composite) {
                    for (LeafNode leaf : leavesOf(root)) {
                        var placement = projection.visibleLeaf(leaf.nodeId());
                        if (placement.isEmpty()) {
                            continue;
                        }
                        ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                        int routedMouseX = panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                        int routedMouseY = panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                        panel.renderUnderlay(graphics, font, routedMouseX, routedMouseY, partialTicks);
                    }
                    boolean skipRightShadow = false;
                    for (LeafNode leaf : leavesOf(root)) {
                        ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                        if (panel != null && panel.outsideHitWidth() > 0) {
                            skipRightShadow = true;
                            break;
                        }
                    }
                    ModulePanel.renderRootChrome(graphics, contentChromeBounds(root), skipRightShadow);
                }
                for (LeafNode leaf : leavesOf(root)) {
                    var placement = projection.visibleLeaf(leaf.nodeId());
                    if (placement.isEmpty()) {
                        continue;
                    }
                    ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                    int routedMouseX = panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                    int routedMouseY = panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                    if (!composite) {
                        panel.renderUnderlay(graphics, font, routedMouseX, routedMouseY, partialTicks);
                        panel.renderFrame(graphics, font, routedMouseX, routedMouseY, partialTicks);
                    } else if (!panel.isOutsideChrome()) {
                        panel.renderSectionHeader(graphics, font, routedMouseX, routedMouseY);
                    }
                    panel.renderBackgroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
                    if (isAnchoredRoot(root)) {
                        panel.renderSlots(graphics, slotRenderer);
                    }
                }
                renderDividers(graphics, root, hoveredDivider);
                ModulePanel.renderResizeGrip(graphics, root.bounds());
            });
        }
        if (!chromeDrawn) {
            anchoredChromeLayer = layer;
            if (anchoredChrome != null) {
                int chromeLayer = layer++;
                withRootLayer(graphics, chromeLayer, 0.0F, () ->
                        anchoredChrome.render(graphics, font, mouseX, mouseY, partialTicks));
            }
        }
        withRootLayer(graphics, layer, 0.0F, () -> renderDropHighlight(graphics));
    }

    public void renderForeground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks) {
        renderForeground(graphics, font, mouseX, mouseY, partialTicks, null);
    }

    public void renderForeground(
            GuiGraphics graphics,
            Font font,
            int mouseX,
            int mouseY,
            float partialTicks,
            ModulePanel.PanelSlotRenderer slotRenderer) {
        ensureProjection();
        FloatingRoot hoveredRoot = topRootAt(mouseX, mouseY, null);
        ModulePanel hoveredLeaf = topLeafAt(mouseX, mouseY);
        // Roots are stored back-to-front. Keep that order for compositing, but route the real
        // cursor only to the topmost root under it; a lower overlapping root must never emit a
        // tooltip/hover state on top of the root that visually owns the cursor.
        int layer = 0;
        boolean chromeDrawn = false;
        for (FloatingRoot root : paintOrderRoots()) {
            if (!chromeDrawn && !isAnchoredRoot(root)) {
                anchoredChromeLayer = Math.max(anchoredChromeLayer, layer);
                chromeDrawn = true;
                layer++;
            }
            int rootLayer = layer++;
            withRootLayer(graphics, rootLayer, FOREGROUND_Z, () -> {
                for (LeafNode leaf : leavesOf(root)) {
                    if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                        continue;
                    }
                    ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                    int routedMouseX = root == hoveredRoot && panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                    int routedMouseY = root == hoveredRoot && panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                    if (slotRenderer != null && !isAnchoredRoot(root)) {
                        panel.renderSlots(graphics, slotRenderer);
                    }
                    panel.renderForegroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
                    if (panel.pinVisible() && panel.inPinButton(routedMouseX, routedMouseY)) {
                        graphics.renderComponentTooltip(
                                font,
                                List.of(Component.translatable(panel.pinned()
                                        ? "gui.mesplicedterminal.unpin_panel"
                                        : "gui.mesplicedterminal.pin_panel")),
                                mouseX,
                                mouseY);
                    }
                }
            });
        }
    }

    /**
     * Raise the current pose so this floating root composites as a single window above earlier
     * roots. {@code extraZ} stacks same-root passes (foreground above that root's items).
     */
    private static void withRootLayer(GuiGraphics graphics, int layer, float extraZ, Runnable draw) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, layer * ROOT_LAYER_Z + extraZ);
        try {
            draw.run();
        } finally {
            graphics.pose().popPose();
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
        return isAreaUnobscured(viewportWorkspace, ownerRootId, area);
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
        if (mode != Mode.NONE || pendingFocusWorkspace != null) {
            return;
        }
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        if (root == null || viewportWorkspace.roots().getLast().rootId().equals(root.rootId())) {
            return;
        }
        if (layoutLocked && isAnchoredRoot(root)) {
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

    /**
     * Starts a root drag for a module without a press on that module's own title bar. Used by the
     * layout editor's sidebar palette: pressing a module entry raises (and, when necessary, reveals
     * or detaches) the module's leaf so the editor's regular drag routing moves it with the same
     * drop-zone splicing as an in-canvas drag.
     *
     * @return false when a gesture is already active or the module has no projected geometry to
     *         drag from
     */
    public boolean startExternalDrag(ModulePanel panel, double mouseX, double mouseY) {
        ensureProjection();
        if (panel == null || mode != Mode.NONE) {
            return false;
        }
        LeafNode leaf = leafForPanel(panel);
        DockWorkspace before = workspace;

        // Hidden modules have no projected geometry. Revealing them here turns the sidebar entry
        // into a palette: press-and-drag places the module even when its policy hid it.
        ModuleLayoutPolicy policy = workspace.policyFor(leaf.moduleId());
        if (!policy.visible()) {
            replaceWorkspace(
                    editor.setLeafVisible(workspace, leaf.nodeId(), true, null, null, null),
                    false);
        }

        FloatingRoot root = rootContainingNode(viewportWorkspace, leaf.nodeId());
        if (!root.content().nodeId().equals(leaf.nodeId())) {
            // The leaf lives inside a composite root: detach it into its own window at its current
            // projected bounds so the pointer keeps its relative grip during the drag.
            DockRect leafBounds = projection.visibleLeaf(leaf.nodeId())
                    .map(LayoutProjection.LeafPlacement::bounds)
                    .orElse(null);
            if (leafBounds == null) {
                return false;
            }
            String newRootId = "root:" + NodeIds.random();
            DockWorkspace detached = detachAndRestore(workspace, leaf.nodeId(), newRootId);
            detached = editor.raiseRoot(detached, newRootId);
            if (detached.equals(workspace)) {
                return false;
            }
            replaceWorkspace(detached, true);
            activeRootId = newRootId;
            root = rootById(newRootId);
        } else if (!viewportWorkspace.roots().getLast().rootId().equals(root.rootId())) {
            replaceWorkspace(editor.raiseRoot(workspace, root.rootId()), false);
        }

        mode = Mode.DRAG_ROOT;
        activeRootId = root.rootId();
        grabOffsetX = mouseX - root.bounds().x();
        grabOffsetY = mouseY - root.bounds().y();
        pressX = mouseX;
        pressY = mouseY;
        dropCandidate = null;
        dragHoverBounds = null;
        gestureStartWorkspace = before;
        return true;
    }

    /**
     * Pose-stack Z used for the vanilla/AE2 slot hover overlay so it composites with the
     * same floating root as the slot icons instead of falling behind a raised window.
     */
    public float slotHighlightZ(ModulePanel panel) {
        ensureProjection();
        LeafNode leaf = leafForPanel(panel);
        FloatingRoot host = rootContainingNode(viewportWorkspace, leaf.nodeId());
        if (host == null) {
            return 200.0F;
        }
        return paintLayerOf(host) * ROOT_LAYER_Z + 200.0F;
    }

    private int paintLayerOf(FloatingRoot target) {
        int layer = 0;
        boolean chromeDrawn = false;
        for (FloatingRoot root : paintOrderRoots()) {
            if (!chromeDrawn && !isAnchoredRoot(root)) {
                layer++;
                chromeDrawn = true;
            }
            if (root.rootId().equals(target.rootId())) {
                return layer;
            }
            layer++;
        }
        return 0;
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

        if (layoutLocked && !editingLayout && isAnchoredRoot(root)) {
            return false;
        }

        DividerHit divider = dividerAt(root, mouseX, mouseY);
        if (button == 1 && divider != null) {
            if (!editingLayout && !canResizeNode(findNode(divider.splitNodeId()))) {
                return false;
            }
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

        if (inRootResizeHandle(root.bounds(), mouseX, mouseY)
                && (editingLayout || canResizeNode(root.content()))) {
            mode = Mode.RESIZE_ROOT;
            activeRootId = root.rootId();
            grabOffsetX = mouseX - root.bounds().right();
            grabOffsetY = mouseY - root.bounds().bottom();
            gestureStartWorkspace = beforeInteraction;
            return true;
        }

        ModulePanel leaf = leafAt(root, mouseX, mouseY);
        if (leaf != null && leaf.inPinButton(mouseX, mouseY)) {
            togglePinned(leaf);
            if (rootRaised) {
                commitInteraction(beforeInteraction);
            }
            return true;
        }
        if (leaf != null && leaf.inTitleBar(mouseX, mouseY)) {
            LeafNode leafNode = leafNodeAt(root, mouseX, mouseY);
            if (leafNode == null) {
                if (rootRaised) {
                    commitInteraction(beforeInteraction);
                }
                return false;
            }
            activeLeafNodeId = leafNode.nodeId();
            if (!editingLayout && !policyFor(leafNode).movable()) {
                if (rootRaised) {
                    commitInteraction(beforeInteraction);
                }
                return false;
            }
            pressX = mouseX;
            pressY = mouseY;
            if (editingLayout && Screen.hasControlDown()) {
                // Ctrl+drag in the editor translates every floating root as one group.
                mode = Mode.DRAG_ROOT;
                moveAllRoots = true;
                activeRootId = root.rootId();
                grabOffsetX = mouseX - root.bounds().x();
                grabOffsetY = mouseY - root.bounds().y();
            } else if (root.content().nodeId().equals(leafNode.nodeId())) {
                // A standalone leaf is itself the floating root, so retain the fast path that
                // drags that root immediately.
                mode = Mode.DRAG_ROOT;
                activeRootId = root.rootId();
                grabOffsetX = mouseX - root.bounds().x();
                grabOffsetY = mouseY - root.bounds().y();
            } else if (!editingLayout && !policyFor(leafNode).floating()) {
                // Non-floating modules stay in the terminal window; drag the whole spliced root.
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
            dragHoverBounds = null;
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
            if (moveAllRoots) {
                translateAllRoots(mouseX, mouseY);
                dropCandidate = null;
                dragHoverBounds = null;
                return true;
            }
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
        if (mode == Mode.DRAG_ROOT && !moveAllRoots) {
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
        resetGestureState();
        commitInteraction(beforeInteraction);
    }

    private void translateAllRoots(double mouseX, double mouseY) {
        FloatingRoot active = rootById(activeRootId);
        int desiredDx = (int) Math.round(mouseX - grabOffsetX) - active.bounds().x();
        int desiredDy = (int) Math.round(mouseY - grabOffsetY) - active.bounds().y();
        int dx = clampedVisibleGroupDelta(desiredDx, true);
        int dy = clampedVisibleGroupDelta(desiredDy, false);
        if (dx == 0 && dy == 0) {
            return;
        }
        DockWorkspace next = workspace;
        for (FloatingRoot root : viewportWorkspace.roots()) {
            DockRect bounds = root.bounds();
            next = editor.setRootBounds(next, root.rootId(), new DockRect(
                    bounds.x() + dx,
                    bounds.y() + dy,
                    bounds.width(),
                    bounds.height()));
        }
        replaceWorkspace(next, false);
    }

    /**
     * Shared translation for Ctrl+drag. Hidden roots keep their relative offset but must not
     * shrink the allowed range: their leftover default geometry often fills the canvas and would
     * otherwise pin the visible cluster to a few pixels.
     */
    int clampedVisibleGroupDelta(int desired, boolean horizontal) {
        int minDelta = Integer.MIN_VALUE;
        int maxDelta = Integer.MAX_VALUE;
        boolean constrained = false;
        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (!hasVisibleLeaf(workspace, root.content())) {
                continue;
            }
            constrained = true;
            DockRect bounds = root.bounds();
            DockRect farthestPositive = clampRectToViewport(
                    new DockRect(Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, bounds.width(), bounds.height()),
                    DockSize.ZERO);
            DockRect farthestNegative = clampRectToViewport(
                    new DockRect(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4, bounds.width(), bounds.height()),
                    DockSize.ZERO);
            if (horizontal) {
                minDelta = Math.max(minDelta, farthestNegative.x() - bounds.x());
                maxDelta = Math.min(maxDelta, farthestPositive.x() - bounds.x());
            } else {
                minDelta = Math.max(minDelta, farthestNegative.y() - bounds.y());
                maxDelta = Math.min(maxDelta, farthestPositive.y() - bounds.y());
            }
        }
        if (!constrained) {
            return 0;
        }
        return Math.max(minDelta, Math.min(maxDelta, desired));
    }

    private void resetGestureState() {
        mode = Mode.NONE;
        activeRootId = null;
        activeLeafNodeId = null;
        activeSplitId = null;
        moveAllRoots = false;
        dropCandidate = null;
        dragHoverBounds = null;
        gestureStartWorkspace = null;
        pendingFocusWorkspace = null;
    }

    /** Persists only a dirty workspace revision; repeated close/store hooks perform no file IO. */
    public void save() {
        if (persistence == null || workspace == null || editingLayout || persistedRevision == workspaceRevision
                || persistenceBlockedAfterLoadFailure) {
            return;
        }
        try {
            persistence.save(workspace);
            persistedRevision = workspaceRevision;
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn(
                    "Failed to write terminal layout v{}",
                    DockLayoutDto.CURRENT_VERSION,
                    e);
        }
    }

    private void loadPreferences() {
        centerOnReturn = true;
        layoutLocked = false;
        if (preferencesPath == null || !Files.isRegularFile(preferencesPath)) {
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(preferencesPath)).getAsJsonObject();
            if (object.has("centerOnReturn")) {
                centerOnReturn = object.get("centerOnReturn").getAsBoolean();
            }
            if (object.has("layoutLocked")) {
                layoutLocked = object.get("layoutLocked").getAsBoolean();
            }
            PatternAccessPanel.readPreferences(object);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to read terminal layout preferences", e);
        }
    }

    private void savePreferences() {
        if (preferencesPath == null) {
            return;
        }
        try {
            Files.createDirectories(preferencesPath.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("centerOnReturn", centerOnReturn);
            object.addProperty("layoutLocked", layoutLocked);
            PatternAccessPanel.writePreferences(object);
            Files.writeString(preferencesPath, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to write terminal layout preferences", e);
        }
    }

    private ModuleLayoutPolicy policyFor(LeafNode leaf) {
        return workspace.policyFor(leaf.moduleId());
    }

    private boolean canResizeNode(LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return policyFor(leaf).resizable();
        }
        SplitNode split = (SplitNode) node;
        return canResizeNode(split.first()) && canResizeNode(split.second());
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
            int visibleLeaves = 0;
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                if (next.visibleLeaf(leaf.nodeId()).isPresent()) {
                    visibleLeaves++;
                }
            }
            boolean composite = visibleLeaves > 1;
            DockRect window = composite ? root.bounds() : null;
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
                boolean stateChanged = panel.visible != visible || panel.hosted || panel.spliced != composite
                        || !java.util.Objects.equals(panel.splicedWindow, window);
                panel.visible = visible;
                panel.hosted = false;
                panel.spliced = composite;
                panel.splicedWindow = window;
                int inset = panel.preferredContentRightInset();
                stateChanged = stateChanged || panel.contentRightInset != inset;
                panel.contentRightInset = inset;
                boolean anchored = false;
                for (LeafNode sibling : LayoutTrees.leaves(root.content())) {
                    if (next.visibleLeaf(sibling.nodeId()).isEmpty()) {
                        continue;
                    }
                    if (!workspace.policyFor(sibling.moduleId()).floating()) {
                        anchored = true;
                        break;
                    }
                }
                ModuleLayoutPolicy policy = workspace.policyFor(leaf.moduleId());
                panel.setPinControl(visible && !anchored && policy.floating(), policy.pinned());
                if (geometryChanged || stateChanged) {
                    panel.layoutSlots();
                }
            }
        }
        joinOutsideRails();
        projection = next;
        projectionDirty = false;
    }

    private void joinOutsideRails() {
        var clusters = new HashMap<String, List<ModulePanel>>();
        for (ModulePanel panel : panelsByModuleId.values()) {
            panel.resetJoinedRail();
            if (!panel.visible || panel.outsideHitWidth() <= 0) {
                continue;
            }
            int railX = panel.x + panel.width;
            String key = (panel.splicedWindow == null ? System.identityHashCode(panel) : System.identityHashCode(panel.splicedWindow))
                    + ":" + railX;
            clusters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(panel);
        }
        for (List<ModulePanel> group : clusters.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingInt(panel -> panel.y));
            int clusterStart = 0;
            for (int index = 1; index <= group.size(); index++) {
                boolean split = index == group.size()
                        || group.get(index).y > group.get(index - 1).y + group.get(index - 1).height + 2;
                if (!split) {
                    continue;
                }
                if (index - clusterStart >= 2) {
                    ModulePanel first = group.get(clusterStart);
                    ModulePanel last = group.get(index - 1);
                    first.joinedRailY = first.y;
                    first.joinedRailH = last.y + last.height - first.joinedRailY;
                    for (int joined = clusterStart + 1; joined < index; joined++) {
                        group.get(joined).drawOutsideRail = false;
                    }
                }
                clusterStart = index;
            }
        }
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
        if (snapshot == null || snapshot.equals(workspace)) {
            return;
        }
        if (!undoStack.isEmpty() && snapshot.equals(undoStack.peekFirst())) {
            return;
        }
        boolean wasEmpty = undoStack.isEmpty();
        undoStack.addFirst(snapshot);
        while (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeLast();
        }
        if (wasEmpty) {
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
        DockWorkspace sanitized = source.withRoots(sanitizedRoots);
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
        return source.withRoots(clampedRoots);
    }

    private DockRect clampRectToViewport(DockRect bounds, DockSize minimum) {
        int minX = editingLayout ? editorInsetLeft : 0;
        int minY = editingLayout ? editorInsetTop : 0;
        int maxRight = Math.max(minX + 1, screenWidth - (editingLayout ? editorInsetRight : 0));
        int maxBottom = Math.max(minY + 1, screenHeight - (editingLayout ? editorInsetBottom : 0));
        int canvasWidth = Math.max(1, maxRight - minX);
        int canvasHeight = Math.max(1, maxBottom - minY);
        int width = Math.min(canvasWidth, Math.max(bounds.width(), minimum.width()));
        int height = Math.min(canvasHeight, Math.max(bounds.height(), minimum.height()));
        width = Math.max(1, width);
        height = Math.max(1, height);
        int maxX = Math.max(minX, maxRight - width);
        int maxY = Math.max(minY, maxBottom - height);
        int x = (int) Math.max((long) minX, Math.min((long) bounds.x(), (long) maxX));
        int y = (int) Math.max((long) minY, Math.min((long) bounds.y(), (long) maxY));
        return new DockRect(x, y, width, height);
    }

    private void clearEditorCanvasInsets() {
        editorInsetLeft = 0;
        editorInsetTop = 0;
        editorInsetRight = 0;
        editorInsetBottom = 0;
        if (workspace != null) {
            viewportWorkspace = clampWorkspaceToViewport(workspace);
            projectionDirty = true;
        }
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
        if (targetRoot == null || (layoutLocked && !editingLayout && isAnchoredRoot(targetRoot))) {
            dropCandidate = null;
            dragHoverBounds = null;
            return;
        }
        LeafNode targetLeaf = leafNodeAt(targetRoot, mouseX, mouseY);
        if (targetLeaf == null) {
            dropCandidate = null;
            dragHoverBounds = null;
            return;
        }
        DockRect bounds = projection.visibleLeaf(targetLeaf.nodeId()).orElseThrow().bounds();
        dragHoverBounds = bounds;
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
        if (!(node instanceof LeafNode leaf)
                || sourceRoot.content().nodeId().equals(activeLeafNodeId)) {
            return false;
        }
        if (!editingLayout && !policyFor(leaf).floating()) {
            return false;
        }
        DockRect leafBounds = projection.boundsFor(activeLeafNodeId).orElse(null);
        if (leafBounds == null) {
            return false;
        }

        String newRootId = "root:" + NodeIds.random();
        DockWorkspace changed = detachAndRestore(workspace, activeLeafNodeId, newRootId);
        changed = editor.raiseRoot(changed, newRootId);
        replaceWorkspace(changed, true);
        activeRootId = newRootId;
        dragHoverBounds = null;
        return true;
    }

    private void detachDividerBranch(FloatingRoot sourceRoot, DividerHit divider, boolean detachFirst) {
        LayoutNode node = LayoutTrees.find(sourceRoot.content(), divider.splitNodeId()).orElseThrow();
        if (!(node instanceof SplitNode split)) {
            return;
        }
        LayoutNode detached = detachFirst ? split.first() : split.second();
        if (!editingLayout && !subtreeCanFloat(detached)) {
            return;
        }
        String newRootId = "root:" + NodeIds.random();
        DockWorkspace changed = detachAndRestore(workspace, detached.nodeId(), newRootId);
        changed = editor.raiseRoot(changed, newRootId);
        replaceWorkspace(changed, true);
        save();
    }

    /**
     * Splits {@code nodeId} into its own floating root and restores both windows to the standalone
     * sizes captured at splice time. Without a stored size, remaining content keeps its projected
     * slice instead of the combined host window.
     */
    private boolean subtreeCanFloat(LayoutNode node) {
        for (LeafNode leaf : LayoutTrees.leaves(node)) {
            if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                continue;
            }
            if (!policyFor(leaf).floating()) {
                return false;
            }
        }
        return true;
    }

    private DockWorkspace detachAndRestore(DockWorkspace source, String nodeId, String newRootId) {
        FloatingRoot sourceRoot = rootContainingNode(source, nodeId);
        DockRect detachedProjected = projection.boundsFor(nodeId).orElse(sourceRoot.bounds());
        DockRect detachedBounds = source.restoredBounds(nodeId, detachedProjected);
        DockWorkspace changed = editor.detach(source, nodeId, newRootId, detachedBounds);
        FloatingRoot remainingRoot = null;
        for (FloatingRoot root : changed.roots()) {
            if (root.rootId().equals(sourceRoot.rootId())) {
                remainingRoot = root;
                break;
            }
        }
        if (remainingRoot == null) {
            return changed;
        }
        DockRect remainingBounds = remainingBoundsAfterDetach(
                source, sourceRoot, remainingRoot.content(), detachedProjected);
        if (!remainingBounds.equals(remainingRoot.bounds())) {
            changed = editor.setRootBounds(changed, remainingRoot.rootId(), remainingBounds);
        }
        return changed;
    }

    private DockRect remainingBoundsAfterDetach(
            DockWorkspace source,
            FloatingRoot sourceRoot,
            LayoutNode remaining,
            DockRect detachedProjected) {
        DockRect remainingProjected = projection.boundsFor(remaining.nodeId()).orElse(sourceRoot.bounds());
        if (source.restoreSizes().containsKey(remaining.nodeId())) {
            return source.restoredBounds(remaining.nodeId(), remainingProjected);
        }
        if (remainingProjected.equals(sourceRoot.bounds())) {
            DockRect shrunk = excludeSlice(sourceRoot.bounds(), detachedProjected);
            if (!shrunk.equals(sourceRoot.bounds()) && shrunk.width() > 0 && shrunk.height() > 0) {
                return shrunk;
            }
        }
        return remainingProjected;
    }

    /** Drops a full-width or full-height detached slice from {@code root} along the split axis. */
    private static DockRect excludeSlice(DockRect root, DockRect slice) {
        if (slice == null || slice.width() <= 0 || slice.height() <= 0) {
            return root;
        }
        boolean spansHeight = slice.y() <= root.y() && slice.bottom() >= root.bottom();
        boolean spansWidth = slice.x() <= root.x() && slice.right() >= root.right();
        if (spansHeight && slice.width() < root.width()) {
            if (slice.x() <= root.x()) {
                int width = root.right() - slice.right();
                if (width > 0) {
                    return new DockRect(slice.right(), root.y(), width, root.height());
                }
            } else if (slice.right() >= root.right()) {
                int width = slice.x() - root.x();
                if (width > 0) {
                    return new DockRect(root.x(), root.y(), width, root.height());
                }
            }
        }
        if (spansWidth && slice.height() < root.height()) {
            if (slice.y() <= root.y()) {
                int height = root.bottom() - slice.bottom();
                if (height > 0) {
                    return new DockRect(root.x(), slice.bottom(), root.width(), height);
                }
            } else if (slice.bottom() >= root.bottom()) {
                int height = slice.y() - root.y();
                if (height > 0) {
                    return new DockRect(root.x(), root.y(), root.width(), height);
                }
            }
        }
        return root;
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
            if (target.rootId().equals(active.rootId()) || !rootEffectivelyVisible(target.rootId())) {
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
        List<FloatingRoot> order = paintOrderRoots();
        for (int i = order.size() - 1; i >= 0; i--) {
            FloatingRoot root = order.get(i);
            if (root.rootId().equals(excludedRootId) || !rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            if (contains(root.bounds(), mouseX, mouseY) || rootOutsideHit(root, mouseX, mouseY)) {
                return root;
            }
        }
        return null;
    }

    public boolean isFloatingWindow(ModulePanel panel) {
        if (panel == null) {
            return false;
        }
        LeafNode leaf = leafForPanel(panel);
        FloatingRoot root = rootContainingNode(viewportWorkspace, leaf.nodeId());
        return root != null && !isAnchoredRoot(root);
    }

    public float anchoredChromeZ() {
        return anchoredChromeLayer * ROOT_LAYER_Z;
    }

    /**
     * Z for the cursor stack. Must sit above extra chrome, but stay inside the GUI projection
     * (very large translates clip 3D item models, which looks like a cut-off icon).
     */
    public float cursorItemZ() {
        return Math.min(anchoredChromeZ() + ROOT_LAYER_Z + 300.0F, 1800.0F);
    }

    private List<FloatingRoot> paintOrderRoots() {
        var anchored = new ArrayList<FloatingRoot>();
        var floating = new ArrayList<FloatingRoot>();
        var pinned = new ArrayList<FloatingRoot>();
        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            if (isAnchoredRoot(root)) {
                anchored.add(root);
            } else if (isPinnedRoot(root)) {
                pinned.add(root);
            } else {
                floating.add(root);
            }
        }
        var order = new ArrayList<FloatingRoot>(anchored.size() + floating.size() + pinned.size());
        order.addAll(anchored);
        order.addAll(floating);
        order.addAll(pinned);
        return order;
    }

    private boolean isAnchoredRoot(FloatingRoot root) {
        return rootInCenterGroup(root);
    }

    private boolean isPinnedRoot(FloatingRoot root) {
        for (LeafNode leaf : leavesOf(root)) {
            if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                continue;
            }
            ModuleLayoutPolicy policy = workspace.policyFor(leaf.moduleId());
            if (policy.floating() && policy.pinned()) {
                return true;
            }
        }
        return false;
    }

    private boolean rootOutsideHit(FloatingRoot root, double mouseX, double mouseY) {
        for (LeafNode leaf : leavesOf(root)) {
            var placement = projection.visibleLeaf(leaf.nodeId());
            if (placement.isEmpty()) {
                continue;
            }
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            DockRect bounds = placement.get().bounds();
            DockRect hit = hitBounds(bounds, panel);
            if ((hit.x() != bounds.x() || hit.y() != bounds.y()
                    || hit.width() != bounds.width() || hit.height() != bounds.height())
                    && contains(hit, mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    private static DockRect hitBounds(DockRect bounds, ModulePanel panel) {
        int left = panel == null ? 0 : panel.outsideHitLeftWidth();
        int right = panel == null ? 0 : panel.outsideHitWidth();
        int top = panel == null ? 0 : panel.outsideHitTop();
        if (left == 0 && right == 0 && top == 0) {
            return bounds;
        }
        return new DockRect(
                bounds.x() - left,
                bounds.y() - top,
                bounds.width() + left + right,
                bounds.height() + top);
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
            if (placement.isEmpty()) {
                continue;
            }
            DockRect bounds = placement.get().bounds();
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            if (contains(hitBounds(bounds, panel), mouseX, mouseY)) {
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

    private int visibleLeafCount(FloatingRoot root) {
        int count = 0;
        for (LeafNode leaf : leavesOf(root)) {
            if (projection.visibleLeaf(leaf.nodeId()).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private DockRect contentChromeBounds(FloatingRoot root) {
        DockRect union = null;
        for (LeafNode leaf : leavesOf(root)) {
            var placement = projection.visibleLeaf(leaf.nodeId());
            if (placement.isEmpty()) {
                continue;
            }
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            if (panel != null && panel.isOutsideChrome()) {
                continue;
            }
            // Leaf placement is the generated window only. Encoding tabs, the ME scroller well
            // and other outsideHit* decoration must not widen return-centering or attach points.
            DockRect bounds = placement.get().bounds();
            union = union == null ? bounds : union.union(bounds);
        }
        return union;
    }

    private void renderDividers(GuiGraphics graphics, FloatingRoot root, DividerHit hoveredDivider) {
        if (hoveredDivider == null) {
            return;
        }
        for (LayoutProjection.DividerPlacement divider : projection.dividers()) {
            if (!LayoutTrees.contains(root.content(), divider.splitNodeId())
                    || !hoveredDivider.splitNodeId().equals(divider.splitNodeId())) {
                continue;
            }
            DockRect bounds = divider.bounds();
            if (divider.axis() == DockAxis.HORIZONTAL) {
                int x = bounds.x();
                graphics.fill(x, bounds.y(), x + 1, bounds.bottom(), DIVIDER_HOVER_COLOR);
            } else {
                int y = bounds.y();
                graphics.fill(bounds.x(), y, bounds.right(), y + 1, DIVIDER_HOVER_COLOR);
            }
        }
    }

    private void renderDropHighlight(GuiGraphics graphics) {
        if (mode != Mode.DRAG_ROOT || dragHoverBounds == null) {
            return;
        }
        // While a root is dragged, outline the thin splice bands of the leaf under the
        // pointer. The band that would actually receive the drop is emphasized below.
        for (DockEdge edge : DockEdge.values()) {
            DockRect zone = dropBounds(dragHoverBounds, edge);
            graphics.fill(zone.x(), zone.y(), zone.right(), zone.bottom(), DROP_ZONE_FILL_COLOR);
        }
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

    private DockRect projectionBounds(String nodeId) {
        ensureProjection();
        return projection.boundsFor(nodeId).orElse(null);
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

    private static int dropZoneThickness(DockRect bounds) {
        return Math.max(
                DROP_ZONE_MIN,
                Math.min(DROP_ZONE_MAX, Math.min(bounds.width(), bounds.height()) / 8));
    }

    private static DockEdge dropEdge(DockRect bounds, double mouseX, double mouseY) {
        int zone = dropZoneThickness(bounds);
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
        int zone = dropZoneThickness(target);
        int width = edge.axis() == DockAxis.HORIZONTAL ? zone : target.width();
        int height = edge.axis() == DockAxis.VERTICAL ? zone : target.height();
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

    static boolean isAreaUnobscured(DockWorkspace workspace, String ownerRootId, DockRect area) {
        List<FloatingRoot> roots = workspace.roots();
        int ownerIndex = -1;
        for (int i = 0; i < roots.size(); i++) {
            FloatingRoot root = roots.get(i);
            if (root.rootId().equals(ownerRootId)) {
                if (!hasVisibleLeaf(workspace, root.content()) || !contains(root.bounds(), area)) {
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
            if (hasVisibleLeaf(workspace, higher.content()) && intersects(higher.bounds(), area)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasVisibleLeaf(DockWorkspace workspace, LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return workspace.policyFor(leaf.moduleId()).visible();
        }
        SplitNode split = (SplitNode) node;
        return hasVisibleLeaf(workspace, split.first()) || hasVisibleLeaf(workspace, split.second());
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
                            new DockSize(defaultWidth, defaultHeight),
                            panel.expandsVertically()));
        }
        return new ModuleCatalog(entries);
    }

    private record DividerHit(String splitNodeId, DockRect bounds) {
    }

    private record DropCandidate(String targetNodeId, DockEdge edge, DockRect highlightBounds) {
    }
}
