package com.lhy.mest.client.dock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.CursorHelper;
import com.lhy.mest.client.dock.model.ContentNudge;
import com.lhy.mest.client.dock.model.ContentOffset;
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
import com.lhy.mest.client.dock.model.SpliceMode;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.workspace.AtomicFileDockLayoutStore;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutDto;
import com.lhy.mest.client.dock.workspace.DockLayoutFormatException;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;
import com.lhy.mest.client.dock.workspace.DockWorkspaceDefaults;
import com.lhy.mest.client.dock.workspace.LayoutPresetBank;
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
    private static final int CONTENT_DOUBLE_CLICK_MS = 300;

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
    /** Lift resource textures above the root background; item rendering adds up to another 200 z. */
    public static final float SLOT_CONTENT_Z = 50.0F;
    /** Keep controls/labels above fluid sprites and item icons/decorations, but below the next root. */
    private static final float FOREGROUND_Z = 300.0F;
    private int anchoredChromeLayer;

    private static final LayoutStyle LAYOUT_STYLE =
            new LayoutStyle(DockInsets.NONE, DIVIDER_THICKNESS);
    private static final boolean DEBUG_DOCK_TIMING = Boolean.getBoolean("mest.debugDockTiming");

    private final List<ModulePanel> panels = new ArrayList<>();
    private final Map<String, ModulePanel> panelsByModuleId = new LinkedHashMap<>();
    /** Immutable snapshot of {@link #panels}; rebuilt only in {@link #init}. */
    private List<ModulePanel> panelsView = List.of();

    // --- Hot-path caches. All are invalidated whenever the projection is rebuilt ---------------
    /** Cached flattened leaf lists per root id (LayoutTrees.leaves allocates a new list per call). */
    private final Map<String, List<LeafNode>> leavesCache = new HashMap<>();
    private List<FloatingRoot> paintOrderCache;
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
    private long projectionCount;
    private long projectionNanos;
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
    private final WorkspaceUndoHistory undoHistory = new WorkspaceUndoHistory(32);
    private DockWorkspace gestureStartWorkspace;
    /** Canonical workspace held aside while a pointer gesture projects its transient state. */
    private DockWorkspace gestureCanonicalWorkspace;
    private DockWorkspace gestureCanonicalViewport;
    private boolean gestureStructural;
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
    private boolean viewCellsVisible = true;
    private boolean moreSettingsVisible = false;
    private boolean pullItemsRecipeButton = true;
    private List<String> moreSettingsOrder = new ArrayList<>();
    private boolean pendingCenterOnReturn;
    private Path preferencesPath;
    private Path configDir;
    private DockLayoutCodec layoutCodec;
    private LayoutPresetBank.Data presets;
    private LayoutPresetBank.Data editingPresets;
    private List<DockWorkspace> editingUndoMark;
    private int editorInsetLeft;
    private int editorInsetTop;
    private int editorInsetRight;
    private int editorInsetBottom;

    private enum Mode {
        NONE,
        PENDING_LEAF_DRAG,
        DRAG_ROOT,
        RESIZE_ROOT,
        RESIZE_DIVIDER,
        RESIZE_LEAF,
        NUDGE_CONTENT
    }

    private Mode mode = Mode.NONE;
    private String activeRootId;
    private String activeLeafNodeId;
    private String activeSplitId;
    private DockRect activeDividerBounds;
    private double resizeLastX;
    private double resizeLastY;
    private double grabOffsetX;
    private double grabOffsetY;
    private double pressX;
    private double pressY;
    private boolean moveAllRoots;
    private String contentEditModuleId;
    private boolean contentSnapX;
    private boolean contentSnapY;
    private String lastContentClickModuleId;
    private long lastContentClickTime;
    private boolean lastContentClickWasDrag;
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
        layoutCodec = codec;
        configDir = FMLPaths.CONFIGDIR.get().resolve(MESplicedterminal.MODID);
        Path path = configDir.resolve("layout.json");
        preferencesPath = configDir.resolve("preferences.json");
        loadPreferences();
        persistence = new AtomicFileDockLayoutStore(path, codec);

        boolean loadedCurrentLayout = false;
        boolean loadedFromFile = false;
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
                loadedFromFile = true;
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

        loadPresetBank(workspace);
        if (presets != null) {
            if (loadedFromFile && !persistenceBlockedAfterLoadFailure) {
                // layout.json is written first on every save, so it is never older than the
                // active preset slot; keep the slot in step instead of letting it win.
                presets = presets.withWorkspace(presets.active(), workspace);
            } else {
                workspace = presets.workspaces()[presets.active()];
            }
        }
        viewportWorkspace = clampWorkspaceToViewport(workspace);
        workspaceRevision = 1;
        persistedRevision = loadedCurrentLayout ? workspaceRevision : -1;
        undoHistory.clear();
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
        if (workspace == null) {
            return;
        }
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
        exitContentEdit();
        editingOriginal = workspace;
        editingUndoMark = undoHistory.snapshot();
        captureActivePreset();
        editingPresets = presets == null ? null : presets.copy();
        return workspace;
    }

    public void cancelLayoutEditing() {
        discardPendingGesture();
        resetGestureState();
        if (editingLayout && editingPresets != null) {
            presets = editingPresets.copy();
            replaceWorkspace(presets.workspaces()[presets.active()], true);
        } else if (editingLayout && editingOriginal != null && !editingOriginal.equals(workspace)) {
            replaceWorkspace(editingOriginal, true);
        }
        // Undo points made inside the editor describe edits that were just thrown away.
        if (editingLayout && editingUndoMark != null) {
            undoHistory.restore(editingUndoMark);
        }
        editingUndoMark = null;
        clearEditorCanvasInsets();
        editingLayout = false;
        editingOriginal = null;
        editingPresets = null;
        exitContentEdit();
        // The editor can be closed (Esc) in the middle of a gesture; dropping the gesture state
        // here prevents a later mouse event from acting on a root that no longer exists.
        resetGestureState();
    }

    public void commitLayoutEditing() {
        finishPendingGesture();
        clearEditorCanvasInsets();
        editingLayout = false;
        editingOriginal = null;
        editingPresets = null;
        editingUndoMark = null;
        resetGestureState();
        if (centerOnReturn) {
            centerVisibleWorkspace();
            pendingCenterOnReturn = true;
        }
        captureActivePreset();
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

    public boolean viewCellsVisible() {
        return viewCellsVisible;
    }

    public void setViewCellsVisible(boolean value) {
        if (viewCellsVisible == value) {
            return;
        }
        viewCellsVisible = value;
        savePreferences();
    }

    public boolean moreSettingsVisible() {
        return moreSettingsVisible;
    }

    public void setMoreSettingsVisible(boolean value) {
        if (moreSettingsVisible == value) {
            return;
        }
        moreSettingsVisible = value;
        savePreferences();
    }

    public boolean pullItemsRecipeButton() {
        return pullItemsRecipeButton;
    }

    public void setPullItemsRecipeButton(boolean value) {
        if (pullItemsRecipeButton == value) {
            return;
        }
        pullItemsRecipeButton = value;
        com.lhy.mest.integration.MestPullItemsSupport.setEnabled(value);
        savePreferences();
    }

    public List<String> moreSettingsOrder() {
        return moreSettingsOrder;
    }

    public void setMoreSettingsOrder(List<String> order) {
        moreSettingsOrder = new ArrayList<>(order);
        savePreferences();
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
        if (!pendingCenterOnReturn || editingLayout || workspace == null) {
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
        if (workspace == null) {
            return;
        }
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
        if (workspace == null) {
            return null;
        }
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
        if (workspace == null) {
            return ModuleLayoutPolicy.defaults();
        }
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
        if (workspace == null) {
            return List.of();
        }
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

    public int presetCount() {
        return LayoutPresetBank.SLOT_COUNT;
    }

    public int activePreset() {
        return presets == null ? 0 : presets.active();
    }

    public String presetName(int index) {
        int slot = Math.floorMod(index, LayoutPresetBank.SLOT_COUNT);
        String custom = presets == null ? "" : presets.names()[slot];
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return defaultPresetName(slot);
    }

    public void setPresetName(int index, String name) {
        if (presets == null) {
            return;
        }
        int slot = Math.floorMod(index, LayoutPresetBank.SLOT_COUNT);
        String sanitized = LayoutPresetBank.sanitizeName(name);
        if (sanitized.equals(defaultPresetName(slot))) {
            sanitized = "";
        }
        if (sanitized.equals(presets.names()[slot])) {
            return;
        }
        presets = presets.withName(slot, sanitized);
        if (!editingLayout) {
            savePresetBankQuietly();
        }
    }

    public void cyclePreset() {
        selectPreset(activePreset() + 1);
    }

    public void selectPreset(int index) {
        if (presets == null) {
            return;
        }
        int slot = Math.floorMod(index, LayoutPresetBank.SLOT_COUNT);
        captureActivePreset();
        if (slot == presets.active() && presets.workspaces()[slot].equals(workspace)) {
            return;
        }
        presets = presets.withActive(slot);
        undoHistory.clear();
        replaceWorkspace(presets.workspaces()[slot], true);
        ensureAnchoredVisible();
        if (!editingLayout) {
            persistenceBlockedAfterLoadFailure = false;
            save();
        }
    }

    public boolean hasLayoutBackup() {
        return configDir != null && Files.isRegularFile(backupFile());
    }

    public Path shareFolder() {
        return configDir == null ? null : configDir.resolve("share");
    }

    public Path shareZipFile() {
        Path share = shareFolder();
        return share == null ? null : share.resolve(LayoutPresetBank.SHARE_ZIP_FILE);
    }

    public record LayoutShareResult(boolean ok, Component message, Path reveal) {
        public static LayoutShareResult ok(Component message, Path reveal) {
            return new LayoutShareResult(true, message, reveal);
        }

        public static LayoutShareResult fail(Component message) {
            return new LayoutShareResult(false, message, null);
        }
    }

    public LayoutShareResult exportLayouts() {
        if (presets == null || layoutCodec == null || configDir == null) {
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_export.fail"));
        }
        captureActivePreset();
        Path share = shareFolder();
        Path zipFile = shareZipFile();
        try {
            Files.createDirectories(share);
            Files.deleteIfExists(share.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE));
            Files.deleteIfExists(share.resolve(LayoutPresetBank.SHARE_PRESETS_FILE));
            for (int index = 0; index < LayoutPresetBank.SLOT_COUNT; index++) {
                Path slotDir = share.resolve(LayoutPresetBank.shareSlotFolder(index));
                Files.createDirectories(slotDir);
                LayoutPresetBank.writeAtomic(
                        slotDir.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE),
                        LayoutPresetBank.encodeSlot(
                                presets.workspaces()[index], presets.names()[index], layoutCodec));
                Files.deleteIfExists(slotDir.resolve(LayoutPresetBank.SHARE_NAME_FILE));
            }
            LayoutPresetBank.zipShareSlots(share, zipFile);
            return LayoutShareResult.ok(
                    Component.translatable(
                            "gui.mesplicedterminal.layout_export.ok",
                            "config/" + MESplicedterminal.MODID + "/share/" + LayoutPresetBank.SHARE_ZIP_FILE),
                    share);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to export terminal layout", e);
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_export.fail"));
        }
    }

    public LayoutShareResult importLayouts() {
        if (presets == null || layoutCodec == null || configDir == null) {
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_import.fail"));
        }
        try {
            LayoutPresetBank.Data imported = importFromShareFolders();
            if (imported == null) {
                Path zipFile = shareZipFile();
                if (zipFile != null && Files.isRegularFile(zipFile)) {
                    imported = LayoutPresetBank.readShareZip(zipFile, layoutCodec, presets);
                }
            }
            if (imported == null) {
                String json = readImportJson();
                if (json == null || json.isBlank()) {
                    return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_import.missing"));
                }
                imported = LayoutPresetBank.read(json, layoutCodec, presets);
            }
            return applyImportedPresets(imported);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to import terminal layout", e);
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_import.fail"));
        }
    }

    public LayoutShareResult importLayouts(Path zipFile) {
        if (presets == null || layoutCodec == null || zipFile == null) {
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_import.fail"));
        }
        try {
            return applyImportedPresets(LayoutPresetBank.readShareZip(zipFile, layoutCodec, presets));
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to import terminal layout from {}", zipFile, e);
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_import.fail"));
        }
    }

    private LayoutShareResult applyImportedPresets(LayoutPresetBank.Data imported) throws IOException {
        backupCurrentPresets();
        presets = imported;
        undoHistory.clear();
        replaceWorkspace(presets.workspaces()[presets.active()], true);
        ensureAnchoredVisible();
        if (!editingLayout) {
            persistenceBlockedAfterLoadFailure = false;
            save();
        }
        return LayoutShareResult.ok(Component.translatable("gui.mesplicedterminal.layout_import.ok"), null);
    }

    public LayoutShareResult restoreLayoutBackup() {
        if (!hasLayoutBackup() || layoutCodec == null) {
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_backup.missing"));
        }
        try {
            LayoutPresetBank.Data restored = LayoutPresetBank.read(
                    Files.readString(backupFile(), StandardCharsets.UTF_8),
                    layoutCodec,
                    presets);
            presets = restored;
            undoHistory.clear();
            replaceWorkspace(presets.workspaces()[presets.active()], true);
            ensureAnchoredVisible();
            if (!editingLayout) {
                persistenceBlockedAfterLoadFailure = false;
                save();
            }
            return LayoutShareResult.ok(Component.translatable("gui.mesplicedterminal.layout_backup.ok"), null);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to restore terminal layout backup", e);
            return LayoutShareResult.fail(Component.translatable("gui.mesplicedterminal.layout_backup.fail"));
        }
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
        finishPendingGesture();
        layoutLocked = !layoutLocked;
        structureVersion++;
        savePreferences();
    }

    /**
     * Active splice mode: {@link SpliceMode#UNIFIED} stretch, {@link SpliceMode#COMPACT} per-leaf
     * frames, or {@link SpliceMode#SHELL} one outer shell with 1px section rules.
     */
    public SpliceMode spliceMode() {
        return workspace == null ? SpliceMode.DEFAULT : workspace.spliceMode();
    }

    public boolean isContentEditing() {
        return contentEditModuleId != null;
    }

    public String contentEditModuleId() {
        return contentEditModuleId;
    }

    public boolean keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && contentEditModuleId != null) {
            exitContentEdit();
            return true;
        }
        return false;
    }

    private boolean handleContentEditClick(double mouseX, double mouseY, int button) {
        if (contentEditModuleId != null) {
            if (button != 0) {
                return true;
            }
            ModulePanel editing = panelsByModuleId.get(contentEditModuleId);
            if (editing == null || !editing.visible) {
                exitContentEdit();
                return false;
            }
            if (editing.inCloseButton(mouseX, mouseY)) {
                exitContentEdit();
                return true;
            }
            if (editing.inResetButton(mouseX, mouseY)) {
                resetContentOffset(editing.id());
                return true;
            }
            if (editing.contains(mouseX, mouseY) && !editing.inTitleBarControls(mouseX, mouseY)) {
                beginContentNudge(editing, mouseX, mouseY);
                return true;
            }
            return true;
        }
        if (!editingLayout || workspace == null || !workspace.drawsOuterShell() || button != 0) {
            return false;
        }
        ModulePanel leaf = topLeafAt(mouseX, mouseY);
        if (leaf == null || leaf.inTitleBarControls(mouseX, mouseY) || leaf.inResizeHandle(mouseX, mouseY)) {
            return false;
        }
        long now = Util.getMillis();
        boolean sameLeaf = leaf.id().equals(lastContentClickModuleId);
        boolean quick = now - lastContentClickTime <= CONTENT_DOUBLE_CLICK_MS;
        lastContentClickModuleId = leaf.id();
        lastContentClickTime = now;
        if (sameLeaf && quick && !lastContentClickWasDrag) {
            enterContentEdit(leaf.id());
            lastContentClickWasDrag = false;
            return true;
        }
        lastContentClickWasDrag = false;
        return false;
    }

    private void enterContentEdit(String moduleId) {
        contentEditModuleId = moduleId;
        contentSnapX = false;
        contentSnapY = false;
        projectionDirty = true;
        ensureProjection();
    }

    private void exitContentEdit() {
        if (contentEditModuleId == null && !contentSnapX && !contentSnapY) {
            return;
        }
        contentEditModuleId = null;
        contentSnapX = false;
        contentSnapY = false;
        if (mode == Mode.NUDGE_CONTENT) {
            finishGesture();
        }
        projectionDirty = true;
        ensureProjection();
    }

    private void beginContentNudge(ModulePanel panel, double mouseX, double mouseY) {
        mode = Mode.NUDGE_CONTENT;
        activeRootId = null;
        grabOffsetX = mouseX - panel.contentOffsetX;
        grabOffsetY = mouseY - panel.contentOffsetY;
        gestureStartWorkspace = takeInteractionStartWorkspace();
        lastContentClickWasDrag = false;
    }

    private void nudgeContent(double mouseX, double mouseY) {
        ModulePanel panel = contentEditModuleId == null ? null : panelsByModuleId.get(contentEditModuleId);
        if (panel == null) {
            return;
        }
        ContentNudge.Snap snap = ContentNudge.snap(
                (int) Math.round(mouseX - grabOffsetX),
                (int) Math.round(mouseY - grabOffsetY),
                panel.contentSlackX(),
                panel.contentSlackY());
        contentSnapX = snap.snapX();
        contentSnapY = snap.snapY();
        applyContentOffset(panel.id(), snap.offset(), false);
    }

    private void resetContentOffset(String moduleId) {
        rememberUndoPoint(workspace);
        applyContentOffset(moduleId, ContentOffset.ZERO, true);
        contentSnapX = false;
        contentSnapY = false;
    }

    private void applyContentOffset(String moduleId, ContentOffset offset, boolean persistZero) {
        var next = new LinkedHashMap<>(workspace.contentOffsets());
        if (offset == null || offset.isZero()) {
            next.remove(moduleId);
            if (!persistZero && workspace.contentOffset(moduleId).isZero()) {
                return;
            }
        } else {
            next.put(moduleId, offset);
        }
        replaceWorkspace(workspace.withContentOffsets(next), false);
    }

    /**
     * Advances the toolbar cycle: unified -&gt; compact -&gt; shell -&gt; unified. Entering the shell
     * mode also shrinks every root to its content, because that mode draws the shell at the root's
     * own rectangle rather than recomputing one from the leaves.
     */
    public void cycleSpliceMode() {
        if (workspace == null) {
            return;
        }
        SpliceMode next = spliceMode().next();
        if (!next.drawsOuterShell()) {
            exitContentEdit();
        }
        rememberUndoPoint(workspace);
        DockWorkspace changed = workspace.withSpliceMode(next);
        if (next.drawsOuterShell()) {
            changed = fitRootsToContent(changed);
        }
        replaceWorkspace(changed, true);
        save();
    }

    /** Sets every root rectangle to the size its subtree actually wants. */
    private DockWorkspace fitRootsToContent(DockWorkspace source) {
        DockWorkspace next = source;
        for (FloatingRoot root : source.roots()) {
            DockSize fitted = layoutEngine.preferredSize(next, root.content());
            if (fitted.isEmpty()) {
                continue;
            }
            next = editor.setRootBounds(next, root.rootId(), new DockRect(
                    root.bounds().x(),
                    root.bounds().y(),
                    Math.max(1, fitted.width()),
                    Math.max(1, fitted.height())));
        }
        return next;
    }

    public boolean canUndoLayout() {
        return !undoHistory.isEmpty();
    }

    public void undoLayout() {
        DockWorkspace restore = undoHistory.pop();
        if (restore == null) {
            return;
        }
        persistenceBlockedAfterLoadFailure = false;
        structureVersion++;
        replaceWorkspace(restore, true);
        save();
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
        int lastAnchoredLayer = 0;
        int chromeLayer = 0;
        boolean chromeDrawn = false;
        List<FloatingRoot> paintOrder = paintOrderRoots();
        int preview = 0;
        for (FloatingRoot root : paintOrder) {
            if (isAnchoredRoot(root)) {
                chromeLayer = preview;
            }
            preview++;
        }
        for (FloatingRoot root : paintOrder) {
            if (!chromeDrawn && layer > chromeLayer) {
                anchoredChromeLayer = chromeLayer;
                if (anchoredChrome != null) {
                    withRootLayer(graphics, chromeLayer, 0.0F, () ->
                            anchoredChrome.render(graphics, font, mouseX, mouseY, partialTicks));
                }
                chromeDrawn = true;
            }
            int rootLayer = layer++;
            if (isAnchoredRoot(root)) {
                lastAnchoredLayer = rootLayer;
            }
            withRootLayer(graphics, rootLayer, 0.0F, () -> {
                boolean composite = visibleLeafCount(root) > 1;
                // The unified and shell modes both cover the whole composite with one background;
                // only the per-leaf-frame mode paints a frame per leaf and hides shared edges.
                boolean shellSplice = composite && workspace != null && workspace.drawsOuterShell();
                boolean compactSplice = composite && !shellSplice
                        && workspace != null && workspace.drawsPerLeafFrames();
                if (composite && !compactSplice) {
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
                        // Any outside chrome needs the shared frame's right shadow suppressed;
                        // rail joining itself is handled separately by hasJoinableOutsideRail().
                        if (panel != null && panel.outsideHitWidth() > 0) {
                            skipRightShadow = true;
                            break;
                        }
                    }
                    ModulePanel.renderRootChrome(graphics, contentChromeBounds(root), skipRightShadow);
                }
                if (compactSplice) {
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
                    List<ModulePanel> occupied = visiblePanels(root);
                    for (ModulePanel panel : occupied) {
                        int routedMouseX = panel == hoveredLeaf ? mouseX : Integer.MIN_VALUE;
                        int routedMouseY = panel == hoveredLeaf ? mouseY : Integer.MIN_VALUE;
                        panel.renderFrame(
                                graphics, font, routedMouseX, routedMouseY, partialTicks,
                                sharedEdges(panel, occupied));
                    }
                }
                for (LeafNode leaf : paintLeaves(root)) {
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
                    } else if (!compactSplice && !panel.isOutsideChrome()) {
                        panel.renderSectionHeader(graphics, font, routedMouseX, routedMouseY);
                    }
                    panel.renderBackgroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
                }
                // GUI resources do not all use the same renderer: items render immediately while
                // fluids and add-on key types may batch vertices. Commit the whole root background
                // before drawing its slots, then commit the slots before a higher root starts.
                graphics.flush();
                if (slotRenderer != null) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(0.0F, 0.0F, SLOT_CONTENT_Z);
                    try {
                        for (LeafNode leaf : leavesOf(root)) {
                            if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                                continue;
                            }
                            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                            panel.renderSlots(graphics, slotRenderer);
                        }
                        graphics.flush();
                    } finally {
                        graphics.pose().popPose();
                    }
                }
                if (shellSplice) {
                    renderShellRules(graphics, root, hoveredDivider);
                } else {
                    renderDividers(graphics, root, hoveredDivider);
                }
                if (compactSplice) {
                    for (ModulePanel panel : visiblePanels(root)) {
                        ModulePanel.renderResizeGrip(graphics, occupiedRect(panel));
                    }
                } else if (contentEditModuleId == null) {
                    ModulePanel.renderResizeGrip(graphics, root.bounds());
                }
                if (contentEditModuleId != null) {
                    ModulePanel editing = panelsByModuleId.get(contentEditModuleId);
                    if (editing != null && editing.visible && visiblePanels(root).contains(editing)) {
                        editing.renderContentGuides(graphics, contentSnapX, contentSnapY);
                    }
                }
            });
        }
        if (!chromeDrawn) {
            anchoredChromeLayer = lastAnchoredLayer;
            if (anchoredChrome != null) {
                withRootLayer(graphics, lastAnchoredLayer, 0.0F, () ->
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
                    boolean hover = root == hoveredRoot && panel == hoveredLeaf
                            && (contentEditModuleId == null || panel.id().equals(contentEditModuleId));
                    int routedMouseX = hover ? mouseX : Integer.MIN_VALUE;
                    int routedMouseY = hover ? mouseY : Integer.MIN_VALUE;
                    panel.renderForegroundContent(graphics, font, routedMouseX, routedMouseY, partialTicks);
                    if (hover) {
                        Component chromeTip = panel.titleBarTooltip(mouseX, mouseY);
                        if (chromeTip != null) {
                            graphics.renderComponentTooltip(font, List.of(chromeTip), mouseX, mouseY);
                        }
                    }
                }
                if (contentEditModuleId != null) {
                    ModulePanel editing = panelsByModuleId.get(contentEditModuleId);
                    if (editing != null && editing.visible) {
                        dimUneditedPanels(graphics, root, editing);
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
        if (workspace == null || panel == null || panelsByModuleId.get(panel.id()) != panel) {
            return false;
        }
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
     * Whether a press at the point may reach {@code panel}: no root at all covers the point (chrome
     * hanging just outside the leaf, like an edge scrollbar), or the topmost one is the panel's own.
     */
    public boolean isPointReachable(ModulePanel panel, double mouseX, double mouseY) {
        ensureProjection();
        FloatingRoot top = topRootAt(mouseX, mouseY, null);
        if (top == null) {
            return true;
        }
        LeafNode leaf = leafForPanel(panel);
        return leaf != null && top.rootId().equals(rootContainingNode(viewportWorkspace, leaf.nodeId()).rootId());
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

    /** Pose-stack Z for the vanilla slot highlight overlay in the same root as the slot. */
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
        for (FloatingRoot root : paintOrderRoots()) {
            if (root.rootId().equals(target.rootId())) {
                return layer;
            }
            layer++;
        }
        return 0;
    }

    private static final int CONTENT_EDIT_DIM = 0x99000000;

    /** Dim every visible leaf except the one being content-edited. */
    private void dimUneditedPanels(GuiGraphics graphics, FloatingRoot root, ModulePanel editing) {
        for (ModulePanel panel : visiblePanels(root)) {
            if (panel == editing) {
                continue;
            }
            int left = panel.x - panel.outsideHitLeftWidth();
            int top = panel.y - panel.outsideHitTop();
            int right = panel.x + panel.width + panel.outsideHitWidth();
            int bottom = panel.y + panel.height;
            if (right > left && bottom > top) {
                graphics.fill(left, top, right, bottom, CONTENT_EDIT_DIM);
            }
        }
    }

    public boolean ownsSlot(Slot slot) {
        for (ModulePanel panel : panels) {
            if (panel != null && panel.ownsSlot(slot)) {
                return true;
            }
        }
        return false;
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
                if (isEffectivelyVisible(panel) && panel.ownsSlot(slot)) {
                    cached = java.util.Optional.of(panel);
                }
            }
            cache.put(slot, cached);
        }
        return cached.orElse(null);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ensureProjection();
        if (handleContentEditClick(mouseX, mouseY, button)) {
            return true;
        }
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
            activeDividerBounds = divider.bounds();
            resizeLastX = mouseX;
            resizeLastY = mouseY;
            gestureStartWorkspace = beforeInteraction;
            return true;
        }

        boolean compact = workspace != null && workspace.drawsPerLeafFrames();
        boolean ctrlResize = compact && Screen.hasControlDown();
        DockRect resizeBounds = resizeHandleBounds(root);
        if (ctrlResize
                && inRootResizeHandle(resizeBounds, mouseX, mouseY)
                && (editingLayout || canResizeNode(root.content()))) {
            mode = Mode.RESIZE_ROOT;
            activeRootId = root.rootId();
            grabOffsetX = mouseX - resizeBounds.right();
            grabOffsetY = mouseY - resizeBounds.bottom();
            gestureStartWorkspace = beforeInteraction;
            return true;
        }
        if (!compact && inRootResizeHandle(root.bounds(), mouseX, mouseY)
                && (editingLayout || canResizeNode(root.content()))) {
            mode = Mode.RESIZE_ROOT;
            activeRootId = root.rootId();
            grabOffsetX = mouseX - root.bounds().right();
            grabOffsetY = mouseY - root.bounds().bottom();
            gestureStartWorkspace = beforeInteraction;
            return true;
        }
        if (compact) {
            ModulePanel compactLeaf = leafAt(root, mouseX, mouseY);
            LeafNode compactLeafNode = leafNodeAt(root, mouseX, mouseY);
            if (compactLeaf != null && compactLeafNode != null && compactLeaf.inResizeHandle(mouseX, mouseY)
                    && (editingLayout || canResizeNode(root.content()))) {
                if (ctrlResize) {
                    mode = Mode.RESIZE_ROOT;
                    activeRootId = root.rootId();
                    grabOffsetX = mouseX - resizeBounds.right();
                    grabOffsetY = mouseY - resizeBounds.bottom();
                } else {
                    mode = Mode.RESIZE_LEAF;
                    activeRootId = root.rootId();
                    activeLeafNodeId = compactLeafNode.nodeId();
                    grabOffsetX = mouseX - (compactLeaf.x + compactLeaf.width);
                    grabOffsetY = mouseY - (compactLeaf.y + compactLeaf.height);
                }
                gestureStartWorkspace = beforeInteraction;
                return true;
            }
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
        if (gestureCanonicalWorkspace == null) {
            gestureCanonicalWorkspace = workspace;
            gestureCanonicalViewport = viewportWorkspace;
            gestureStructural = false;
        }
        ensureProjection();
        if (mode == Mode.NUDGE_CONTENT) {
            lastContentClickWasDrag = true;
            nudgeContent(mouseX, mouseY);
            return true;
        }
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
            lastContentClickWasDrag = true;
        }
        if (mode == Mode.DRAG_ROOT) {
            lastContentClickWasDrag = true;
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
        if (mode == Mode.RESIZE_LEAF) {
            resizeCompactLeaf(mouseX, mouseY);
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
        DockWorkspace preview = workspace;
        DockWorkspace canonical = gestureCanonicalWorkspace;
        boolean structural = gestureStructural;
        resetGestureState();
        if (canonical != null && !preview.equals(canonical)) {
            persistenceBlockedAfterLoadFailure = false;
            workspace = preview;
            workspaceRevision++;
            projectionDirty = true;
            if (structural) {
                structureVersion++;
            }
            ensureProjection();
        }
        commitInteraction(beforeInteraction);
    }

    /** Commits a live pointer gesture exactly once; safe to call during screen removal. */
    public void finishPendingGesture() {
        if (mode != Mode.NONE || gestureCanonicalWorkspace != null) {
            finishGesture();
        } else {
            commitPanelInteraction();
        }
    }

    /** Drops a live gesture preview and restores the canonical revision without saving. */
    private void discardPendingGesture() {
        DockWorkspace canonical = gestureCanonicalWorkspace;
        DockWorkspace canonicalViewport = gestureCanonicalViewport;
        resetGestureState();
        if (canonical == null || canonical.equals(workspace)) {
            return;
        }
        workspace = canonical;
        viewportWorkspace = canonicalViewport == null
                ? clampWorkspaceToViewport(canonical)
                : canonicalViewport;
        projectionDirty = true;
        ensureProjection();
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
        var movedBounds = new HashMap<String, DockRect>();
        for (FloatingRoot root : viewportWorkspace.roots()) {
            DockRect bounds = root.bounds();
            movedBounds.put(root.rootId(), new DockRect(
                    bounds.x() + dx,
                    bounds.y() + dy,
                    bounds.width(),
                    bounds.height()));
        }
        DockWorkspace next = editor.setRootBounds(workspace, movedBounds);
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
        activeDividerBounds = null;
        moveAllRoots = false;
        dropCandidate = null;
        dragHoverBounds = null;
        gestureStartWorkspace = null;
        gestureCanonicalWorkspace = null;
        gestureCanonicalViewport = null;
        gestureStructural = false;
        pendingFocusWorkspace = null;
        contentSnapX = false;
        contentSnapY = false;
    }

    /** Persists only a dirty workspace revision; repeated close/store hooks perform no file IO. */
    public void save() {
        if (persistence == null || workspace == null || editingLayout || persistedRevision == workspaceRevision
                || persistenceBlockedAfterLoadFailure || gestureCanonicalWorkspace != null) {
            return;
        }
        try {
            persistence.save(workspace);
            persistedRevision = workspaceRevision;
            captureActivePreset();
            savePresetBank();
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
        viewCellsVisible = true;
        moreSettingsVisible = false;
        pullItemsRecipeButton = true;
        moreSettingsOrder = new ArrayList<>();
        com.lhy.mest.integration.MestPullItemsSupport.setEnabled(pullItemsRecipeButton);
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
            if (object.has("viewCellsVisible")) {
                viewCellsVisible = object.get("viewCellsVisible").getAsBoolean();
            }
            if (object.has("moreSettingsVisible")) {
                moreSettingsVisible = object.get("moreSettingsVisible").getAsBoolean();
            }
            if (object.has("pullItemsRecipeButton")) {
                pullItemsRecipeButton = object.get("pullItemsRecipeButton").getAsBoolean();
            }
            moreSettingsOrder = readStringList(object, "moreSettingsOrder");
            PatternAccessPanel.readPreferences(object);
            com.lhy.mest.integration.MestPullItemsSupport.setEnabled(pullItemsRecipeButton);
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
            object.addProperty("viewCellsVisible", viewCellsVisible);
            object.addProperty("moreSettingsVisible", moreSettingsVisible);
            object.addProperty("pullItemsRecipeButton", pullItemsRecipeButton);
            object.add("moreSettingsOrder", toStringArray(moreSettingsOrder));
            PatternAccessPanel.writePreferences(object);
            Files.writeString(preferencesPath, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to write terminal layout preferences", e);
        }
    }

    private void loadPresetBank(DockWorkspace current) {
        DockWorkspace filler = DockWorkspaceDefaults.create(persistenceCatalog, migrationContext);
        LayoutPresetBank.Data fallback = LayoutPresetBank.create(current, filler, null);
        Path file = configDir.resolve(LayoutPresetBank.PRESETS_FILE);
        presets = LayoutPresetBank.load(file, layoutCodec, fallback);
        if (!Files.isRegularFile(file)) {
            savePresetBankQuietly();
        }
    }

    private void captureActivePreset() {
        if (presets == null || workspace == null) {
            return;
        }
        presets = presets.withWorkspace(presets.active(), workspace);
    }

    private void savePresetBank() throws IOException {
        if (presets == null || layoutCodec == null || configDir == null) {
            return;
        }
        LayoutPresetBank.save(configDir.resolve(LayoutPresetBank.PRESETS_FILE), presets, layoutCodec);
    }

    private void savePresetBankQuietly() {
        try {
            savePresetBank();
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to write terminal layout presets", e);
        }
    }

    private void backupCurrentPresets() throws IOException {
        if (presets == null || layoutCodec == null || configDir == null) {
            return;
        }
        captureActivePreset();
        Path backupDir = configDir.resolve("backup");
        LayoutPresetBank.clearDirectory(backupDir);
        Files.createDirectories(backupDir);
        LayoutPresetBank.save(backupFile(), presets, layoutCodec);
    }

    private Path backupFile() {
        return configDir.resolve("backup").resolve(LayoutPresetBank.PRESETS_FILE);
    }

    private LayoutPresetBank.Data importFromShareFolders() throws IOException, DockLayoutFormatException {
        Path share = configDir.resolve("share");
        if (!Files.isDirectory(share)) {
            return null;
        }
        LayoutPresetBank.Data data = presets.copy();
        boolean any = false;
        for (int index = 0; index < LayoutPresetBank.SLOT_COUNT; index++) {
            Path slotDir = share.resolve(LayoutPresetBank.shareSlotFolder(index));
            Path layoutFile = slotDir.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE);
            if (!Files.isRegularFile(layoutFile)) {
                continue;
            }
            any = true;
            LayoutPresetBank.SlotDocument document = LayoutPresetBank.decodeSlot(
                    Files.readString(layoutFile, StandardCharsets.UTF_8), layoutCodec);
            data = data.withWorkspace(index, document.workspace());
            String name = document.name();
            Path nameFile = slotDir.resolve(LayoutPresetBank.SHARE_NAME_FILE);
            if (name.isBlank() && Files.isRegularFile(nameFile)) {
                name = LayoutPresetBank.sanitizeName(Files.readString(nameFile, StandardCharsets.UTF_8));
            }
            data = data.withName(index, name);
        }
        return any ? data : null;
    }

    private String readImportJson() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            String clipboard = minecraft.keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isBlank()) {
                try {
                    LayoutPresetBank.read(clipboard, layoutCodec, presets);
                    return clipboard;
                } catch (IOException | RuntimeException ignored) {
                    // Fall through to share files.
                }
            }
        }
        Path share = configDir.resolve("share");
        Path presetsFile = share.resolve(LayoutPresetBank.SHARE_PRESETS_FILE);
        Path layoutFile = share.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE);
        try {
            if (Files.isRegularFile(presetsFile)) {
                return Files.readString(presetsFile, StandardCharsets.UTF_8);
            }
            if (Files.isRegularFile(layoutFile)) {
                return Files.readString(layoutFile, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            MESplicedterminal.LOGGER.warn("Failed to read shared terminal layout", e);
        }
        return null;
    }

    private static String defaultPresetName(int slot) {
        return Component.translatable("gui.mesplicedterminal.layout_preset", slot + 1).getString();
    }

    private static List<String> readStringList(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return values;
        }
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static JsonArray toStringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
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
        if (!projectionDirty || workspace == null || viewportWorkspace == null || layoutEngine == null) {
            return;
        }
        long started = DEBUG_DOCK_TIMING ? System.nanoTime() : 0L;
        leavesCache.clear();
        paintOrderCache = null;
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
            DockRect window = composite
                    ? (workspace.drawsPerLeafFrames() ? occupiedUnion(root, next) : root.bounds())
                    : null;
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
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                var placement = next.visibleLeaf(leaf.nodeId());
                boolean visible = placement.isPresent();
                if (placement.isPresent()) {
                    DockRect bounds = placement.get().bounds();
                    panel.x = bounds.x();
                    panel.y = bounds.y();
                    panel.width = bounds.width();
                    panel.height = bounds.height();
                }
                panel.visible = visible;
                panel.hosted = false;
                panel.spliced = composite;
                panel.splicedWindow = window;
                panel.contentEditing = leaf.moduleId().equals(contentEditModuleId);
                ModuleLayoutPolicy policy = workspace.policyFor(leaf.moduleId());
                panel.setPinControl(visible && !anchored && policy.floating(), policy.pinned());
            }
            DockChromeLayout.markRightmostLeaves(root, next, panelsByModuleId);
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
                panel.contentRightInset = panel.preferredContentRightInset();
                ContentOffset offset = ContentNudge.clamp(
                        workspace.contentOffset(leaf.moduleId()).x(),
                        workspace.contentOffset(leaf.moduleId()).y(),
                        panel.contentSlackX(),
                        panel.contentSlackY());
                panel.contentOffsetX = offset.x();
                panel.contentOffsetY = offset.y();
                panel.layoutSlots();
            }
        }
        DockChromeLayout.joinOutsideRails(panelsByModuleId.values());
        projection = next;
        projectionDirty = false;
        if (DEBUG_DOCK_TIMING) {
            projectionCount++;
            projectionNanos += System.nanoTime() - started;
            if (projectionCount % 120 == 0) {
                MESplicedterminal.LOGGER.debug(
                        "Dock projection timing: count={}, average={}us",
                        projectionCount,
                        projectionNanos / projectionCount / 1_000L);
            }
        }
    }

    private void replaceWorkspace(DockWorkspace changed, boolean structural) {
        if (gestureCanonicalWorkspace != null && mode != Mode.NONE) {
            replaceGestureWorkspace(changed, structural);
            return;
        }
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

    private void replaceGestureWorkspace(DockWorkspace changed, boolean structural) {
        if (changed.equals(workspace)) {
            return;
        }
        workspace = changed;
        DockWorkspace nextViewport = clampGestureViewport(changed, structural);
        if (nextViewport.equals(viewportWorkspace)) {
            // Geometry is identical to what is already projected, so the live drag
            // preview needs no re-projection. This is the common case for a single
            // root-translate gesture where every pointer move stays in-bounds.
            gestureStructural |= structural;
            return;
        }
        viewportWorkspace = nextViewport;
        projectionDirty = true;
        gestureStructural |= structural;
        ensureProjection();
    }

    /**
     * Clamps a live gesture preview to the viewport. During a pure root-translate
     * gesture the recursive layout is unchanged (only one root's absolute position
     * moved, already clamped by the caller), so the full re-projection normally
     * used to measure root minimum sizes is skipped and the cached minimums from
     * the last projection are reused. Any root lacking a cached minimum falls back
     * to the full clamp, as does every other gesture mode (resize, snap, splice).
     */
    private DockWorkspace clampGestureViewport(DockWorkspace changed, boolean structural) {
        boolean translateOnly = mode == Mode.DRAG_ROOT && !moveAllRoots && !structural;
        if (translateOnly) {
            boolean allMeasured = true;
            for (FloatingRoot root : changed.roots()) {
                if (rootMinimum(root.rootId()).equals(DockSize.ZERO)) {
                    allMeasured = false;
                    break;
                }
            }
            if (allMeasured) {
                var clampedRoots = new ArrayList<FloatingRoot>(changed.roots().size());
                for (FloatingRoot root : changed.roots()) {
                    clampedRoots.add(
                            root.withBounds(clampRectToViewport(root.bounds(), rootMinimum(root.rootId()))));
                }
                return changed.withRoots(clampedRoots);
            }
        }
        return clampWorkspaceToViewport(changed);
    }

    static void joinOutsideRails(Iterable<ModulePanel> panels) {
        DockChromeLayout.joinOutsideRails(panels);
    }

    private void rememberUndoPoint(DockWorkspace snapshot) {
        if (gestureCanonicalWorkspace != null) {
            // Gesture previews must not enter the undo history; only the committed result does.
            return;
        }
        if (snapshot == null || snapshot.equals(workspace)) {
            return;
        }
        if (undoHistory.remember(snapshot)) {
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
        if (editorInsetLeft == 0 && editorInsetTop == 0
                && editorInsetRight == 0 && editorInsetBottom == 0) {
            return;
        }
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
        if (workspace != null && workspace.prefersCompactBounds()) {
            resizeCompactDivider(split, mouseX, mouseY);
            return;
        }
        DockWorkspace next = withSplitRatioAt(workspace, split, mouseX, mouseY);
        if (next != workspace) {
            replaceWorkspace(next, false);
        }
    }

    private void resizeCompactDivider(SplitNode split, double mouseX, double mouseY) {
        DockRect seam = activeDividerBounds;
        if (seam == null) {
            return;
        }
        int delta = split.axis() == DockAxis.VERTICAL
                ? (int) Math.round(mouseY - resizeLastY)
                : (int) Math.round(mouseX - resizeLastX);
        List<LeafNode> firstTouch = leavesOnSeam(split.first(), seam, split.axis(), true);
        List<LeafNode> secondTouch = leavesOnSeam(split.second(), seam, split.axis(), false);
        if (firstTouch.isEmpty() && secondTouch.isEmpty()) {
            return;
        }
        for (LeafNode leaf : firstTouch) {
            delta = Math.max(delta, minExtent(leaf, split.axis()) - axisExtent(leaf, split.axis()));
        }
        for (LeafNode leaf : secondTouch) {
            delta = Math.min(delta, axisExtent(leaf, split.axis()) - minExtent(leaf, split.axis()));
        }
        if (delta == 0) {
            return;
        }
        FloatingRoot root = rootById(activeRootId);
        var restore = new java.util.LinkedHashMap<>(workspace.restoreSizes());
        if (root != null) {
            for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
                projection.visibleLeaf(leaf.nodeId())
                        .ifPresent(visible -> restore.put(leaf.nodeId(), visible.bounds().size()));
            }
        }
        applySeamDelta(restore, firstTouch, split.axis(), delta);
        applySeamDelta(restore, secondTouch, split.axis(), -delta);
        DockWorkspace next = workspace.withRestoreSizes(restore);

        int firstMain = subtreeMainAfterDelta(split.first(), firstTouch, split.axis(), delta);
        int secondMain = subtreeMainAfterDelta(split.second(), secondTouch, split.axis(), -delta);
        int available = Math.max(1, firstMain + secondMain);
        next = editor.setSplitRatio(next, split.nodeId(), clampStoredRatio(split, available, (double) firstMain / available));

        if (root != null) {
            DockSize fitted = layoutEngine.preferredSize(next, root.content());
            next = editor.setRootBounds(next, root.rootId(), clampRectToViewport(
                    new DockRect(
                            root.bounds().x(), root.bounds().y(),
                            Math.max(1, fitted.width()), Math.max(1, fitted.height())),
                    rootMinimum(root.rootId())));
        }
        if (!next.equals(workspace)) {
            replaceWorkspace(next, false);
            resizeLastX = mouseX;
            resizeLastY = mouseY;
            activeDividerBounds = closestDividerBounds(split.nodeId(), mouseX, mouseY);
        }
    }

    private DockRect closestDividerBounds(String splitNodeId, double mouseX, double mouseY) {
        DockRect closest = null;
        double best = Double.MAX_VALUE;
        for (LayoutProjection.DividerPlacement divider : projection.dividers()) {
            if (!divider.splitNodeId().equals(splitNodeId)) {
                continue;
            }
            DockRect bounds = divider.bounds();
            double dx = mouseX - (bounds.x() + bounds.width() / 2.0);
            double dy = mouseY - (bounds.y() + bounds.height() / 2.0);
            double distance = dx * dx + dy * dy;
            if (distance < best) {
                closest = bounds;
                best = distance;
            }
        }
        return closest;
    }

    private void applySeamDelta(
            java.util.Map<String, DockSize> restore, List<LeafNode> leaves, DockAxis axis, int delta) {
        for (LeafNode leaf : leaves) {
            DockRect bounds = projection.boundsFor(leaf.nodeId()).orElse(null);
            if (bounds == null) {
                continue;
            }
            int width = axis == DockAxis.HORIZONTAL ? Math.max(1, bounds.width() + delta) : bounds.width();
            int height = axis == DockAxis.VERTICAL ? Math.max(1, bounds.height() + delta) : bounds.height();
            restore.put(leaf.nodeId(), new DockSize(width, height));
        }
    }

    private int subtreeMainAfterDelta(LayoutNode side, List<LeafNode> touching, DockAxis axis, int delta) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        boolean any = false;
        for (LeafNode leaf : LayoutTrees.leaves(side)) {
            DockRect bounds = projection.boundsFor(leaf.nodeId()).orElse(null);
            if (bounds == null) {
                continue;
            }
            any = true;
            int start;
            int end;
            if (axis == DockAxis.VERTICAL) {
                start = bounds.y();
                end = bounds.bottom() + (containsLeaf(touching, leaf) ? delta : 0);
            } else {
                start = bounds.x();
                end = bounds.right() + (containsLeaf(touching, leaf) ? delta : 0);
            }
            min = Math.min(min, start);
            max = Math.max(max, end);
        }
        return any ? Math.max(1, max - min) : 1;
    }

    private static boolean containsLeaf(List<LeafNode> leaves, LeafNode leaf) {
        for (LeafNode candidate : leaves) {
            if (candidate.nodeId().equals(leaf.nodeId())) {
                return true;
            }
        }
        return false;
    }

    private int axisExtent(LeafNode leaf, DockAxis axis) {
        DockRect bounds = projection.boundsFor(leaf.nodeId()).orElse(null);
        if (bounds == null) {
            return 1;
        }
        return axis == DockAxis.VERTICAL ? bounds.height() : bounds.width();
    }

    private int minExtent(LeafNode leaf, DockAxis axis) {
        ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
        if (panel == null) {
            return 1;
        }
        return axis == DockAxis.VERTICAL ? panel.minHeight() : panel.minWidth();
    }

    private List<LeafNode> leavesOnSeam(LayoutNode side, DockRect seam, DockAxis axis, boolean firstSide) {
        var touching = new ArrayList<LeafNode>();
        for (LeafNode leaf : LayoutTrees.leaves(side)) {
            DockRect bounds = projection.boundsFor(leaf.nodeId()).orElse(null);
            if (bounds == null) {
                continue;
            }
            boolean onEdge = axis == DockAxis.VERTICAL
                    ? (firstSide
                            ? Math.abs(bounds.bottom() - seam.y()) <= 1
                            : Math.abs(bounds.y() - seam.y()) <= 1)
                        && bounds.x() < seam.right() && bounds.right() > seam.x()
                    : (firstSide
                            ? Math.abs(bounds.right() - seam.x()) <= 1
                            : Math.abs(bounds.x() - seam.x()) <= 1)
                        && bounds.y() < seam.bottom() && bounds.bottom() > seam.y();
            if (onEdge) {
                touching.add(leaf);
            }
        }
        return touching;
    }

    private DockRect visualDividerBounds(LayoutProjection.DividerPlacement divider) {
        return divider.bounds();
    }

    /**
     * Compact-mode per-module grip: this leaf keeps the new size, siblings keep theirs,
     * and the window grows or shrinks to fit. The split divider remains the tool for
     * redistributing space between siblings.
     */
    private void resizeCompactLeaf(double mouseX, double mouseY) {
        if (activeLeafNodeId == null || activeRootId == null) {
            return;
        }
        int targetRight = (int) Math.round(mouseX - grabOffsetX);
        int targetBottom = (int) Math.round(mouseY - grabOffsetY);
        DockRect leafBounds = projection.boundsFor(activeLeafNodeId).orElse(null);
        if (leafBounds == null) {
            return;
        }
        String moduleId = projection.visibleLeaf(activeLeafNodeId)
                .map(LayoutProjection.LeafPlacement::moduleId)
                .orElse(null);
        ModulePanel panel = moduleId == null ? null : panelsByModuleId.get(moduleId);
        int minW = panel == null ? 1 : panel.minWidth();
        int minH = panel == null ? 1 : panel.minHeight();
        int newW = Math.max(minW, targetRight - leafBounds.x());
        int newH = Math.max(minH, targetBottom - leafBounds.y());

        FloatingRoot root = rootById(activeRootId);
        var restore = new java.util.LinkedHashMap<>(workspace.restoreSizes());
        for (LeafNode sibling : LayoutTrees.leaves(root.content())) {
            if (sibling.nodeId().equals(activeLeafNodeId)) {
                continue;
            }
            projection.visibleLeaf(sibling.nodeId())
                    .ifPresent(placement -> restore.put(sibling.nodeId(), placement.bounds().size()));
        }
        restore.put(activeLeafNodeId, new DockSize(newW, newH));
        DockWorkspace next = workspace.withRestoreSizes(restore);

        int rootX = root.bounds().x();
        int rootY = root.bounds().y();
        DockSize fitted = layoutEngine.preferredSize(next, root.content());

        next = editor.setRootBounds(next, root.rootId(), clampRectToViewport(
                new DockRect(rootX, rootY, Math.max(1, fitted.width()), Math.max(1, fitted.height())),
                rootMinimum(root.rootId())));
        if (!next.equals(workspace)) {
            replaceWorkspace(next, false);
        }
    }

    private double clampStoredRatio(SplitNode split, int available, double ratio) {
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
        return Math.max(minimumRatio, Math.min(maximumRatio, ratio));
    }

    private DockWorkspace withSplitRatioAt(DockWorkspace current, SplitNode split, double mouseX, double mouseY) {
        DockRect bounds = projection.boundsFor(split.nodeId()).orElse(null);
        if (bounds == null) {
            return current;
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
        return editor.setSplitRatio(current, split.nodeId(), ratio);
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
        if (!draggedCanSplice() || !moduleCanSplice(targetLeaf.moduleId())) {
            dropCandidate = null;
            return;
        }
        DockEdge edge = dropEdge(bounds, mouseX, mouseY);
        dropCandidate = edge == null ? null : new DropCandidate(targetLeaf.nodeId(), edge, dropBounds(bounds, edge));
    }

    private boolean moduleCanSplice(String moduleId) {
        ModulePanel panel = panelsByModuleId.get(moduleId);
        return panel == null || panel.canSplice();
    }

    private boolean draggedCanSplice() {
        FloatingRoot dragged = rootById(activeRootId);
        if (dragged == null) {
            return true;
        }
        for (LeafNode leaf : LayoutTrees.leaves(dragged.content())) {
            if (!moduleCanSplice(leaf.moduleId())) {
                return false;
            }
        }
        return true;
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
        if (workspace == null || !workspace.prefersCompactBounds()) {
            ratio = Math.max(0.2, Math.min(0.8, ratio));
        }

        String splitNodeId = "split:" + NodeIds.random();
        var restore = new java.util.LinkedHashMap<>(workspace.restoreSizes());
        for (LeafNode leaf : LayoutTrees.leaves(targetViewportRoot.content())) {
            projection.visibleLeaf(leaf.nodeId())
                    .ifPresent(visible -> restore.put(leaf.nodeId(), visible.bounds().size()));
        }
        for (LeafNode leaf : LayoutTrees.leaves(draggedRoot.content())) {
            projection.visibleLeaf(leaf.nodeId())
                    .ifPresent(visible -> restore.put(leaf.nodeId(), visible.bounds().size()));
        }
        DockWorkspace joined = editor.insertSplit(
                workspace.withRestoreSizes(restore),
                dragged.nodeId(),
                candidate.targetNodeId(),
                candidate.edge(),
                splitNodeId,
                ratio);
        String joinedRootId = rootContainingNode(joined, splitNodeId).rootId();
        DockRect expandedBounds = expandedDockBounds(
                targetViewportRoot.bounds(), draggedRoot.bounds(), candidate.edge());
        if (joined.prefersCompactBounds()) {
            LayoutNode joinedContent = rootContainingNode(joined, splitNodeId).content();
            DockSize fitted = layoutEngine.preferredSize(joined, joinedContent);
            expandedBounds = clampRectToViewport(new DockRect(
                    expandedBounds.x(), expandedBounds.y(),
                    Math.max(1, fitted.width()), Math.max(1, fitted.height())), DockSize.ZERO);
        }
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

    /**
     * Top-to-bottom paint order so a joined right-edge well is committed before siblings draw
     * tabs or scroller handles on top of it.
     */
    private List<LeafNode> paintLeaves(FloatingRoot root) {
        List<LeafNode> leaves = leavesOf(root);
        if (leaves.size() < 2) {
            return leaves;
        }
        List<LeafNode> ordered = new ArrayList<>(leaves);
        ordered.sort(Comparator.comparingInt(leaf -> {
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            return panel == null ? 0 : panel.y;
        }));
        return ordered;
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

    public CursorHelper.Shape pointerCursor(double mouseX, double mouseY) {
        ensureProjection();
        if (mode == Mode.RESIZE_ROOT || mode == Mode.RESIZE_LEAF) {
            return CursorHelper.Shape.NWSE;
        }
        if (mode == Mode.RESIZE_DIVIDER) {
            return dividerCursor(activeSplitId);
        }
        if (mode == Mode.DRAG_ROOT || mode == Mode.PENDING_LEAF_DRAG) {
            return CursorHelper.Shape.HAND;
        }
        FloatingRoot root = topRootAt(mouseX, mouseY, null);
        if (root == null) {
            return CursorHelper.Shape.ARROW;
        }
        boolean lockedAnchored = layoutLocked && !editingLayout && isAnchoredRoot(root);
        boolean compact = workspace != null && workspace.drawsPerLeafFrames();
        if (!lockedAnchored && compact && Screen.hasControlDown()
                && inRootResizeHandle(resizeHandleBounds(root), mouseX, mouseY)
                && (editingLayout || canResizeNode(root.content()))) {
            return CursorHelper.Shape.NWSE;
        }
        if (!lockedAnchored && !compact
                && inRootResizeHandle(root.bounds(), mouseX, mouseY)
                && (editingLayout || canResizeNode(root.content()))) {
            return CursorHelper.Shape.NWSE;
        }
        if (!lockedAnchored && compact) {
            ModulePanel compactLeaf = leafAt(root, mouseX, mouseY);
            if (compactLeaf != null && compactLeaf.inResizeHandle(mouseX, mouseY)
                    && (editingLayout || canResizeNode(root.content()))) {
                return CursorHelper.Shape.NWSE;
            }
        }
        if (!lockedAnchored) {
            DividerHit divider = dividerAt(root, mouseX, mouseY);
            if (divider != null) {
                return dividerCursor(divider.splitNodeId());
            }
        }
        if (!lockedAnchored) {
            ModulePanel leaf = leafAt(root, mouseX, mouseY);
            LeafNode leafNode = leafNodeAt(root, mouseX, mouseY);
            if (leaf != null && leafNode != null
                    && leaf.inTitleBar(mouseX, mouseY)
                    && !leaf.inPinButton(mouseX, mouseY)
                    && (editingLayout || policyFor(leafNode).movable())) {
                return CursorHelper.Shape.HAND;
            }
        }
        return CursorHelper.Shape.ARROW;
    }

    private CursorHelper.Shape dividerCursor(String splitNodeId) {
        LayoutNode node = findNode(splitNodeId);
        if (node instanceof SplitNode split && split.axis() == DockAxis.VERTICAL) {
            return CursorHelper.Shape.NS;
        }
        return CursorHelper.Shape.EW;
    }

    private List<FloatingRoot> paintOrderRoots() {
        if (paintOrderCache != null) {
            return paintOrderCache;
        }
        var unpinned = new ArrayList<FloatingRoot>();
        var pinned = new ArrayList<FloatingRoot>();
        for (FloatingRoot root : viewportWorkspace.roots()) {
            if (!rootEffectivelyVisible(root.rootId())) {
                continue;
            }
            if (isPinnedRoot(root)) {
                pinned.add(root);
            } else {
                unpinned.add(root);
            }
        }
        var order = new ArrayList<FloatingRoot>(unpinned.size() + pinned.size());
        order.addAll(unpinned);
        order.addAll(pinned);
        paintOrderCache = List.copyOf(order);
        return paintOrderCache;
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
            DockRect bounds = visualDividerBounds(divider);
            if (bounds == null || bounds.width() <= 0 && bounds.height() <= 0) {
                continue;
            }
            DockRect hitBounds = new DockRect(
                    bounds.x() - DIVIDER_HIT_PADDING,
                    bounds.y() - DIVIDER_HIT_PADDING,
                    Math.max(1, bounds.width()) + 2 * DIVIDER_HIT_PADDING,
                    Math.max(1, bounds.height()) + 2 * DIVIDER_HIT_PADDING);
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

    private List<ModulePanel> visiblePanels(FloatingRoot root) {
        var visible = new ArrayList<ModulePanel>();
        for (LeafNode leaf : leavesOf(root)) {
            if (projection.visibleLeaf(leaf.nodeId()).isEmpty()) {
                continue;
            }
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            if (panel != null && panel.visible && !panel.isOutsideChrome()) {
                visible.add(panel);
            }
        }
        return visible;
    }

    private static DockRect occupiedRect(ModulePanel panel) {
        return new DockRect(panel.x, panel.y, panel.width, panel.height);
    }

    private static int sharedEdges(ModulePanel panel, List<ModulePanel> siblings) {
        int edges = 0;
        DockRect self = occupiedRect(panel);
        for (ModulePanel other : siblings) {
            if (other == panel) {
                continue;
            }
            DockRect peer = occupiedRect(other);
            boolean overlapX = self.x() < peer.right() && self.right() > peer.x();
            boolean overlapY = self.y() < peer.bottom() && self.bottom() > peer.y();
            if (overlapX && self.bottom() == peer.y()) {
                edges |= ModulePanel.EDGE_BOTTOM;
            }
            if (overlapX && self.y() == peer.bottom()) {
                edges |= ModulePanel.EDGE_TOP;
            }
            if (overlapY && self.right() == peer.x()) {
                edges |= ModulePanel.EDGE_RIGHT;
            }
            if (overlapY && self.x() == peer.right()) {
                edges |= ModulePanel.EDGE_LEFT;
            }
        }
        return edges;
    }

    private DockRect resizeHandleBounds(FloatingRoot root) {
        if (workspace != null && workspace.drawsPerLeafFrames() && visibleLeafCount(root) > 1) {
            DockRect union = contentChromeBounds(root);
            if (union != null && union.width() > 0 && union.height() > 0) {
                return union;
            }
        }
        return root.bounds();
    }

    private DockRect occupiedUnion(FloatingRoot root, LayoutProjection source) {
        DockRect union = null;
        for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
            var placement = source.visibleLeaf(leaf.nodeId());
            if (placement.isEmpty()) {
                continue;
            }
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            if (panel != null && panel.isOutsideChrome()) {
                continue;
            }
            union = union == null ? placement.get().bounds() : union.union(placement.get().bounds());
        }
        return union;
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

    private void renderShellRules(GuiGraphics graphics, FloatingRoot root, DividerHit hoveredDivider) {
        // Section rules are an editing aid: they mark where a shell splits while arranging panels.
        // In the terminal they only read as stray grey seams, so keep them to the layout editor.
        if (!editingLayout) {
            return;
        }
        var rules = new ArrayList<LayoutProjection.DividerPlacement>();
        for (LayoutProjection.DividerPlacement divider : projection.dividers()) {
            if (LayoutTrees.contains(root.content(), divider.splitNodeId())) {
                rules.add(divider);
            }
        }
        ModulePanel.drawSectionRules(
                graphics,
                rules,
                hoveredDivider == null ? null : hoveredDivider.splitNodeId());
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
            DockRect bounds = visualDividerBounds(divider);
            if (bounds == null) {
                continue;
            }
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

    public void hideModule(ModulePanel panel) {
        ensureProjection();
        LeafNode leaf = leafForPanel(panel);
        if (!workspace.policyFor(leaf.moduleId()).visible()) {
            return;
        }
        replaceWorkspace(
                editor.setLeafVisible(workspace, leaf.nodeId(), false, null, null, null),
                false);
    }

    public void revealModule(ModulePanel panel) {
        ensureProjection();
        LeafNode leaf = leafForPanel(panel);
        DockWorkspace next = workspace;
        if (!workspace.policyFor(leaf.moduleId()).visible()) {
            next = editor.setLeafVisible(next, leaf.nodeId(), true, null, null, null);
        }
        FloatingRoot root = rootContainingNode(next, leaf.nodeId());
        if (root != null) {
            next = editor.raiseRoot(next, root.rootId());
        }
        if (next != workspace) {
            replaceWorkspace(next, false);
        }
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
}
