package com.lhy.mest.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.network.chat.Component;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockInsets;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.model.ModuleMetrics;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;
import com.lhy.mest.client.dock.workspace.LayoutPresetBank;
import com.lhy.mest.client.dock.workspace.LegacyMigrationContext;

class DockManagerTest {
    @TempDir
    Path configDir;

    @Test
    @SuppressWarnings("unchecked")
    void joinedFloatingPanelsStayJoinedForTransientAutoDismissal() throws ReflectiveOperationException {
        TestPanel cache = new TestPanel("cache", false);
        TestPanel toolkit = new TestPanel("toolkit", false);
        TestPanel standalone = new TestPanel("standalone", false);
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-joined", new DockRect(0, 0, 240, 100),
                        new SplitNode("split-cache-toolkit", DockAxis.HORIZONTAL, 0.5,
                                new LeafNode("leaf-cache", cache.id(), true),
                                new LeafNode("leaf-toolkit", toolkit.id(), true)), true, false, 1, false),
                new FloatingRoot("root-alone", new DockRect(250, 0, 100, 80),
                        new LeafNode("leaf-alone", standalone.id(), true), true, false)));
        DockManager manager = new DockManager();
        setField(manager, "workspace", workspace);
        Map<String, ModulePanel> registered = (Map<String, ModulePanel>) getField(manager, "panelsByModuleId");
        registered.put(cache.id(), cache);
        registered.put(toolkit.id(), toolkit);
        registered.put(standalone.id(), standalone);

        // Do not rely on the transient rendered "spliced" flag: a hidden leaf still has a split tree.
        assertFalse(toolkit.spliced);
        assertTrue(manager.isJoinedWindow(toolkit));
        assertTrue(manager.isJoinedWindow(cache));
        assertFalse(manager.isJoinedWindow(standalone));
        assertFalse(manager.isJoinedWindow(null));
    }

    @Test
    void joinedWindowDetachesOnlyWithAltOutsideEditor() {
        assertFalse(DockManager.shouldDetachJoinedLeaf(false, false, false));
        assertTrue(DockManager.shouldDetachJoinedLeaf(false, true, false));
        assertTrue(DockManager.shouldDetachJoinedLeaf(true, false, false));
        assertFalse(DockManager.shouldDetachJoinedLeaf(false, true, true));
        assertFalse(DockManager.shouldDetachJoinedLeaf(true, true, true));
    }

    @Test
    void previewSaveIsSuppressedAndPendingGestureCommitsOnce() throws ReflectiveOperationException {
        DockWorkspace before = new DockWorkspace(List.of(root("a", new DockRect(0, 0, 100, 80), true)));
        DockWorkspace preview = new DockWorkspace(List.of(root("a", new DockRect(10, 0, 100, 80), true)));
        DockManager manager = gestureManager(before, preview);
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");

        manager.save();
        assertEquals(0, persistence.saveCount);
        manager.finishPendingGesture();

        assertEquals(1, persistence.saveCount);
        assertSame(preview, persistence.savedWorkspace);
        assertEquals(2L, getLongField(manager, "workspaceRevision"));
        assertEquals(1, ((WorkspaceUndoHistory) getField(manager, "undoHistory")).size());
    }

    @Test
    void noOpPendingGestureDoesNotCreateRevisionOrUndo() throws ReflectiveOperationException {
        DockWorkspace workspace = new DockWorkspace(List.of(root("a", new DockRect(0, 0, 100, 80), true)));
        DockManager manager = gestureManager(workspace, workspace);
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");
        manager.finishPendingGesture();
        assertEquals(1L, getLongField(manager, "workspaceRevision"));
        assertFalse(manager.canUndoLayout());
        assertEquals(0, persistence.saveCount);
    }

    @Test
    void cancelRestoresCanonicalPreview() throws ReflectiveOperationException {
        DockWorkspace before = new DockWorkspace(List.of(root("a", new DockRect(0, 0, 100, 80), true)));
        DockWorkspace preview = new DockWorkspace(List.of(root("a", new DockRect(10, 0, 100, 80), true)));
        DockManager manager = gestureManager(before, preview);
        setField(manager, "editingLayout", true);
        setField(manager, "editingOriginal", before);
        manager.cancelLayoutEditing();
        assertSame(before, manager.workspaceSnapshot());
        assertFalse(manager.canUndoLayout());
    }

    @Test
    void commitLayoutEditingFinalizesPendingGesture() throws ReflectiveOperationException {
        DockWorkspace before = new DockWorkspace(List.of(root("a", new DockRect(0, 0, 100, 80), true)));
        DockWorkspace preview = new DockWorkspace(List.of(root("a", new DockRect(10, 0, 100, 80), true)));
        DockManager manager = gestureManager(before, preview);
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");
        setField(manager, "editingLayout", true);
        setField(manager, "editingOriginal", before);
        setField(manager, "centerOnReturn", false);
        manager.commitLayoutEditing();
        assertSame(preview, manager.workspaceSnapshot());
        assertEquals(2L, getLongField(manager, "workspaceRevision"));
        assertEquals(1, ((WorkspaceUndoHistory) getField(manager, "undoHistory")).size());
        assertEquals(1, persistence.saveCount);
    }

    private static DockManager gestureManager(DockWorkspace before, DockWorkspace preview)
            throws ReflectiveOperationException {
        DockManager manager = new DockManager();
        CountingPersistence persistence = new CountingPersistence();
        setField(manager, "workspace", preview);
        setField(manager, "viewportWorkspace", preview);
        setField(manager, "workspaceRevision", 1L);
        setField(manager, "persistedRevision", 1L);
        setField(manager, "persistence", persistence);
        setField(manager, "gestureStartWorkspace", before);
        setField(manager, "gestureCanonicalWorkspace", before);
        setField(manager, "gestureCanonicalViewport", before);
        setField(manager, "mode", dragRootMode());
        return manager;
    }

    private static Object dragRootMode() {
        try {
            Class<?> type = Class.forName("com.lhy.mest.client.dock.DockManager$Mode");
            for (Object constant : type.getEnumConstants()) {
                if ("DRAG_ROOT".equals(constant.toString())) {
                    return constant;
                }
            }
            throw new AssertionError("DRAG_ROOT missing");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
    @Test
    void unchangedPresetStillRepairsPersistenceWithoutCreatingUndo() throws ReflectiveOperationException {
        DockManager manager = new DockManager();
        DockWorkspace workspace = new DockWorkspace(List.of(
                root("owner", new DockRect(0, 0, 100, 80), true)));
        CountingPersistence persistence = new CountingPersistence();
        setField(manager, "workspace", workspace);
        setField(manager, "viewportWorkspace", workspace);
        setField(manager, "workspaceRevision", 1L);
        setField(manager, "persistedRevision", -1L);
        setField(manager, "persistenceBlockedAfterLoadFailure", true);
        setField(manager, "persistence", persistence);

        Method applyPreset = DockManager.class.getDeclaredMethod(
                "applyPresetWorkspace", DockWorkspace.class);
        applyPreset.setAccessible(true);
        applyPreset.invoke(manager, workspace);

        assertFalse(manager.canUndoLayout());
        assertEquals(1, persistence.saveCount);
        assertSame(workspace, persistence.savedWorkspace);
        assertFalse(getBooleanField(manager, "persistenceBlockedAfterLoadFailure"));
        assertEquals(1L, getLongField(manager, "persistedRevision"));
    }

    @Test
    void undoHistoryKeepsMultipleSnapshots() throws ReflectiveOperationException {
        DockManager manager = new DockManager();
        DockWorkspace current = new DockWorkspace(List.of(
                root("current", new DockRect(20, 0, 100, 80), true)));
        DockWorkspace first = new DockWorkspace(List.of(
                root("first", new DockRect(0, 0, 100, 80), true)));
        DockWorkspace second = new DockWorkspace(List.of(
                root("second", new DockRect(10, 0, 100, 80), true)));
        setField(manager, "workspace", current);

        Method remember = DockManager.class.getDeclaredMethod("rememberUndoPoint", DockWorkspace.class);
        remember.setAccessible(true);
        remember.invoke(manager, first);
        remember.invoke(manager, second);

        assertTrue(manager.canUndoLayout());
        WorkspaceUndoHistory history = (WorkspaceUndoHistory) getField(manager, "undoHistory");
        assertEquals(2, history.size());
        assertEquals(second, history.pop());
    }

    @Test
    void panelWidgetAreaIsHiddenWhenAnyHigherVisibleRootOverlapsIt() {
        FloatingRoot owner = root("owner", new DockRect(0, 0, 100, 80), true);
        FloatingRoot higher = root("higher", new DockRect(80, 0, 100, 80), true);

        assertFalse(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner, higher)), owner.rootId(), new DockRect(82, 1, 16, 18)));
        assertTrue(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner, higher)), owner.rootId(), new DockRect(60, 1, 16, 18)));
    }

    @Test
    void hiddenHigherRootDoesNotOccludePanelWidgets() {
        FloatingRoot owner = root("owner", new DockRect(0, 0, 100, 80), true);
        FloatingRoot hiddenHigher = root("higher", new DockRect(80, 0, 100, 80), false);
        DockWorkspace workspace = new DockWorkspace(
                List.of(owner, hiddenHigher),
                Map.of(
                        "module-owner", new ModuleLayoutPolicy(true, true, true),
                        "module-higher", new ModuleLayoutPolicy(false, true, true)));

        assertTrue(DockManager.isAreaUnobscured(
                workspace, owner.rootId(), new DockRect(82, 1, 16, 18)));
    }

    @Test
    void areaMustBelongToTheOwningRoot() {
        FloatingRoot owner = root("owner", new DockRect(0, 0, 100, 80), true);

        assertFalse(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner)), owner.rootId(), new DockRect(90, 1, 16, 18)));
        assertFalse(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner)), "missing", new DockRect(10, 1, 16, 18)));
    }

    @Test
    void outsideTabsDoNotSuppressAdjacentScrollbarRail() {
        DockRect window = new DockRect(0, 0, 100, 160);
        TestPanel encodingTabs = new TestPanel("encoding", false);
        encodingTabs.x = 0;
        encodingTabs.y = 0;
        encodingTabs.width = 100;
        encodingTabs.height = 80;
        encodingTabs.splicedWindow = window;

        TestPanel patternAccess = new TestPanel("access", true);
        patternAccess.x = 0;
        patternAccess.y = 80;
        patternAccess.width = 100;
        patternAccess.height = 80;
        patternAccess.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(encodingTabs, patternAccess));

        assertFalse(encodingTabs.drawOutsideRail);
        assertTrue(patternAccess.drawOutsideRail);
        assertEquals(80, patternAccess.joinedRailY);
        assertEquals(80, patternAccess.joinedRailH);
    }

    @Test
    void encodingTabsJoinAdjacentScrollbarRail() {
        DockRect window = new DockRect(0, 0, 100, 160);
        TestPanel encodingTabs = new TestPanel("encoding", true);
        encodingTabs.x = 0;
        encodingTabs.y = 0;
        encodingTabs.width = 100;
        encodingTabs.height = 80;
        encodingTabs.splicedWindow = window;

        TestPanel patternAccess = new TestPanel("access", true);
        patternAccess.x = 0;
        patternAccess.y = 80;
        patternAccess.width = 100;
        patternAccess.height = 80;
        patternAccess.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(encodingTabs, patternAccess));

        assertTrue(encodingTabs.drawOutsideRail);
        assertEquals(0, encodingTabs.joinedRailY);
        assertEquals(160, encodingTabs.joinedRailH);
        assertFalse(patternAccess.drawOutsideRail);
    }

    @Test
    void encodingTabsRemainOutsideWhenStackedWithOutsideRail() {
        TestPanel encoding = new TestPanel("encoding", false);
        encoding.x = 0;
        encoding.y = 0;
        encoding.width = 100;
        encoding.height = 80;
        encoding.rightmostInWindow = true;

        TestPanel access = new TestPanel("access", true);
        access.x = 0;
        access.y = 80;
        access.width = 100;
        access.height = 80;
        access.rightmostInWindow = true;

        assertTrue(encoding.rightmostInWindow);
        assertTrue(access.hasJoinableOutsideRail());
    }

    @Test
    void adjacentScrollRailsStillShareOneContinuousBackground() {
        DockRect window = new DockRect(0, 0, 100, 160);
        TestPanel upper = new TestPanel("upper", true);
        upper.x = 0;
        upper.y = 0;
        upper.width = 100;
        upper.height = 80;
        upper.splicedWindow = window;

        TestPanel lower = new TestPanel("lower", true);
        lower.x = 0;
        lower.y = 80;
        lower.width = 100;
        lower.height = 80;
        lower.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(upper, lower));

        assertTrue(upper.drawOutsideRail);
        assertEquals(0, upper.joinedRailY);
        assertEquals(160, upper.joinedRailH);
        assertFalse(lower.drawOutsideRail);
    }

    @Test
    void railsWithAGapSplitIntoSeparateClusters() {
        DockRect window = new DockRect(0, 0, 100, 200);
        TestPanel upper = new TestPanel("upper", true);
        upper.x = 0;
        upper.y = 0;
        upper.width = 100;
        upper.height = 80;
        upper.splicedWindow = window;

        TestPanel lower = new TestPanel("lower", true);
        lower.x = 0;
        lower.y = 90;
        lower.width = 100;
        lower.height = 80;
        lower.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(upper, lower));

        assertTrue(upper.drawOutsideRail);
        assertEquals(upper.y, upper.joinedRailY);
        assertEquals(upper.height, upper.joinedRailH);
        assertTrue(lower.drawOutsideRail);
        assertEquals(lower.y, lower.joinedRailY);
        assertEquals(lower.height, lower.joinedRailH);
    }

    @Test
    void overlappingVerticalRangesStillJoinEvenWithoutTouching() {
        DockRect window = new DockRect(0, 0, 100, 200);
        TestPanel upper = new TestPanel("upper", true);
        upper.x = 0;
        upper.y = 0;
        upper.width = 100;
        upper.height = 80;
        upper.splicedWindow = window;

        TestPanel lower = new TestPanel("lower", true);
        lower.x = 0;
        lower.y = 80;
        lower.width = 100;
        lower.height = 80;
        lower.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(upper, lower));

        assertTrue(upper.drawOutsideRail);
        assertEquals(0, upper.joinedRailY);
        assertEquals(160, upper.joinedRailH);
        assertFalse(lower.drawOutsideRail);
    }

    @Test
    void hiddenLeavesAreExcludedFromRailJoining() {
        DockRect window = new DockRect(0, 0, 100, 160);
        TestPanel visible = new TestPanel("visible", true);
        visible.x = 0;
        visible.y = 0;
        visible.width = 100;
        visible.height = 80;
        visible.visible = true;
        visible.splicedWindow = window;

        TestPanel hidden = new TestPanel("hidden", true);
        hidden.x = 0;
        hidden.y = 80;
        hidden.width = 100;
        hidden.height = 80;
        hidden.visible = false;
        hidden.splicedWindow = window;

        DockManager.joinOutsideRails(List.of(visible, hidden));

        assertTrue(visible.drawOutsideRail);
        assertEquals(visible.y, visible.joinedRailY);
        assertEquals(visible.height, visible.joinedRailH);
        assertTrue(hidden.drawOutsideRail);
        assertEquals(hidden.y, hidden.joinedRailY);
        assertEquals(hidden.height, hidden.joinedRailH);
    }

    @Test
    void editorRenamePersistsWithoutChangingOrRewritingTheWorkspace() throws Exception {
        DockManager manager = presetManager();
        DockWorkspace original = manager.workspaceSnapshot();
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");

        manager.beginLayoutEditing();
        manager.setPresetName(0, "Renamed");
        assertEquals("First", readPresets(manager).names()[0]);
        manager.commitLayoutEditing();

        assertEquals("Renamed", readPresets(manager).names()[0]);
        assertSame(original, manager.workspaceSnapshot());
        assertEquals(1L, getLongField(manager, "workspaceRevision"));
        assertEquals(0, persistence.saveCount);
        assertEquals(getLongField(manager, "presetsRevision"),
                getLongField(manager, "persistedPresetsRevision"));
    }

    @Test
    void switchingBetweenIdenticalLayoutsPersistsTheActivePreset() throws Exception {
        DockManager manager = presetManager();
        DockWorkspace original = manager.workspaceSnapshot();
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");

        manager.selectPreset(1);
        manager.save();

        assertEquals(1, readPresets(manager).active());
        assertSame(original, manager.workspaceSnapshot());
        assertEquals(1L, getLongField(manager, "workspaceRevision"));
        assertEquals(0, persistence.saveCount);
        assertFalse(manager.canUndoLayout());
    }

    @Test
    void cancellingMetadataOnlyEditingRestoresNamesActiveSlotAndCleanState() throws Exception {
        DockManager manager = presetManager();
        long originalRevision = getLongField(manager, "presetsRevision");
        String originalFile = Files.readString(configDir.resolve(LayoutPresetBank.PRESETS_FILE));

        manager.beginLayoutEditing();
        manager.setPresetName(0, "Discard me");
        manager.selectPreset(1);
        manager.cancelLayoutEditing();
        manager.save();

        assertEquals("First", manager.presetName(0));
        assertEquals(0, manager.activePreset());
        assertEquals(originalRevision, getLongField(manager, "presetsRevision"));
        assertEquals(originalRevision, getLongField(manager, "persistedPresetsRevision"));
        assertEquals(originalFile, Files.readString(configDir.resolve(LayoutPresetBank.PRESETS_FILE)));
        assertEquals(0, ((CountingPersistence) getField(manager, "persistence")).saveCount);
    }

    @Test
    void cancellingEditingPreservesAnEarlierFailedPresetWriteForRetry() throws Exception {
        DockManager manager = presetManager();
        Path blocker = blockPresetFile();
        manager.setPresetName(0, "Keep pending");
        long pendingRevision = getLongField(manager, "presetsRevision");

        manager.beginLayoutEditing();
        manager.setPresetName(0, "Discard this");
        manager.selectPreset(1);
        manager.cancelLayoutEditing();
        assertEquals(pendingRevision, getLongField(manager, "presetsRevision"));
        assertTrue(pendingRevision != getLongField(manager, "persistedPresetsRevision"));

        Files.delete(blocker);
        Files.delete(blocker.getParent());
        manager.save();
        assertEquals("Keep pending", readPresets(manager).names()[0]);
        assertEquals(0, readPresets(manager).active());
        assertEquals(pendingRevision, getLongField(manager, "persistedPresetsRevision"));
    }

    @Test
    void loadedPresetBankStartsCleanAndNoOpSavesDoNotRewriteFiles() throws Exception {
        DockManager manager = presetManager();
        assertEquals(getLongField(manager, "presetsRevision"),
                getLongField(manager, "persistedPresetsRevision"));
        Path file = configDir.resolve(LayoutPresetBank.PRESETS_FILE);
        String originalFile = Files.readString(file);
        // Valid JSON with extra whitespace makes an unnecessary serialization observable.
        Files.writeString(file, originalFile + "\n\n");

        manager.save();
        manager.save();

        assertEquals(originalFile + "\n\n", Files.readString(file));
        assertEquals(0, ((CountingPersistence) getField(manager, "persistence")).saveCount);
    }

    @Test
    void failedPresetCreationDuringLoadRemainsRetryable() throws Exception {
        DockManager manager = presetManager();
        Path blocker = blockPresetFile();
        loadPresets(manager, manager.workspaceSnapshot());
        assertTrue(getLongField(manager, "presetsRevision")
                != getLongField(manager, "persistedPresetsRevision"));
        Files.delete(blocker);
        Files.delete(blocker.getParent());

        manager.save();

        assertTrue(Files.isRegularFile(configDir.resolve(LayoutPresetBank.PRESETS_FILE)));
        assertEquals(manager.workspaceSnapshot(), readPresets(manager).workspaces()[0]);
        assertEquals(getLongField(manager, "presetsRevision"),
                getLongField(manager, "persistedPresetsRevision"));
        assertEquals(0, ((CountingPersistence) getField(manager, "persistence")).saveCount);
    }

    @Test
    void currentLayoutRepairsAStaleLoadedActivePresetWithoutRewritingLayout() throws Exception {
        DockManager manager = presetManager();
        DockWorkspace current = manager.workspaceSnapshot();
        DockWorkspace stale = current.withRoots(List.of(root("owner", new DockRect(40, 0, 100, 80), true)));
        LayoutPresetBank.Data bank = (LayoutPresetBank.Data) getField(manager, "presets");
        DockLayoutCodec codec = (DockLayoutCodec) getField(manager, "layoutCodec");
        LayoutPresetBank.save(configDir.resolve(LayoutPresetBank.PRESETS_FILE),
                bank.withActive(1).withWorkspace(1, stale), codec);
        loadPresets(manager, current);

        // init() keeps layout.json authoritative by capturing it into the loaded active slot.
        Method capture = DockManager.class.getDeclaredMethod("captureActivePreset");
        capture.setAccessible(true);
        capture.invoke(manager);
        manager.save();

        LayoutPresetBank.Data saved = readPresets(manager);
        assertEquals(1, saved.active());
        assertEquals(current, saved.workspaces()[1]);
        assertEquals("Second", saved.names()[1]);
        assertEquals(0, ((CountingPersistence) getField(manager, "persistence")).saveCount);
    }

    @Test
    void failedPresetWriteRetriesAfterLayoutWasAlreadySaved() throws Exception {
        DockManager manager = presetManager();
        Path blocker = blockPresetFile();
        DockWorkspace changed = manager.workspaceSnapshot().withRoots(
                List.of(root("owner", new DockRect(40, 0, 100, 80), true)));
        setField(manager, "workspace", changed);
        setField(manager, "workspaceRevision", 2L);
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");

        manager.save();
        assertEquals(1, persistence.saveCount);
        assertEquals(2L, getLongField(manager, "persistedRevision"));
        assertTrue(getLongField(manager, "presetsRevision")
                != getLongField(manager, "persistedPresetsRevision"));
        Files.delete(blocker);
        Files.delete(blocker.getParent());
        manager.save();

        assertEquals(changed, readPresets(manager).workspaces()[0]);
        assertEquals(1, persistence.saveCount, "retry must not rewrite the already saved layout");
        assertEquals(getLongField(manager, "presetsRevision"),
                getLongField(manager, "persistedPresetsRevision"));
    }

    @Test
    void failedDirectRenameRetriesThroughSaveWithoutAnotherRename() throws Exception {
        DockManager manager = presetManager();
        Path blocker = blockPresetFile();
        manager.setPresetName(0, "Retry me");
        Files.delete(blocker);
        Files.delete(blocker.getParent());

        manager.save();

        assertEquals("Retry me", readPresets(manager).names()[0]);
        assertEquals(0, ((CountingPersistence) getField(manager, "persistence")).saveCount);
    }

    @Test
    void layoutWriteFailureKeepsBothFilesDirtyUntilRetry() throws Exception {
        DockManager manager = presetManager();
        String originalPresets = Files.readString(configDir.resolve(LayoutPresetBank.PRESETS_FILE));
        DockWorkspace changed = manager.workspaceSnapshot().withRoots(
                List.of(root("owner", new DockRect(40, 0, 100, 80), true)));
        setField(manager, "workspace", changed);
        setField(manager, "workspaceRevision", 2L);
        CountingPersistence persistence = (CountingPersistence) getField(manager, "persistence");
        persistence.failNextSave = true;

        manager.save();
        assertEquals(1L, getLongField(manager, "persistedRevision"));
        assertEquals(originalPresets, Files.readString(configDir.resolve(LayoutPresetBank.PRESETS_FILE)));
        manager.save();

        assertEquals(changed, persistence.savedWorkspace);
        assertEquals(changed, readPresets(manager).workspaces()[0]);
        assertEquals(2L, getLongField(manager, "persistedRevision"));
    }

    @Test
    void corruptPresetLoadPreservesOriginalBeforeSubsequentRename() throws Exception {
        DockManager manager = presetManager();
        Path file = configDir.resolve(LayoutPresetBank.PRESETS_FILE);
        Files.writeString(file, "broken original presets");
        loadPresets(manager, manager.workspaceSnapshot());
        manager.setPresetName(0, "Recovered");
        assertEquals("Recovered", readPresets(manager).names()[0]);
        try (var paths = Files.list(configDir)) {
            var quarantined = paths.filter(path -> path.getFileName().toString().startsWith("presets.json.corrupt.")).toList();
            assertEquals(1, quarantined.size());
            assertEquals("broken original presets", Files.readString(quarantined.getFirst()));
        }
    }

    @Test
    void blockedPresetPersistenceCannotBeBypassedByRenameOrSave() throws Exception {
        DockManager manager = presetManager();
        Path file = configDir.resolve(LayoutPresetBank.PRESETS_FILE);
        String original = Files.readString(file);
        setField(manager, "presetPersistenceBlockedAfterLoadFailure", true);
        manager.setPresetName(0, "Not authorized to overwrite");
        manager.save();
        assertEquals(original, Files.readString(file));
        assertTrue(getLongField(manager, "presetsRevision") != getLongField(manager, "persistedPresetsRevision"));
    }

    @Test
    void backupFailurePreservesThePreviousSnapshotAndOtherFiles() throws Exception {
        DockManager manager = presetManager();
        Path backup = configDir.resolve("backup").resolve(LayoutPresetBank.PRESETS_FILE);
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "previous recoverable snapshot");
        Path unrelated = backup.getParent().resolve("manual-copy.json");
        Files.writeString(unrelated, "manual backup");
        LayoutPresetBank.Data bank = (LayoutPresetBank.Data) getField(manager, "presets");
        // A malformed inactive slot fails serialization after captureActivePreset has succeeded.
        DockWorkspace invalid = new DockWorkspace(List.of(root("unknown", new DockRect(0, 0, 100, 80), true)));
        setField(manager, "presets", bank.withWorkspace(1, invalid));
        Method backupMethod = DockManager.class.getDeclaredMethod("backupCurrentPresets");
        backupMethod.setAccessible(true);

        org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> backupMethod.invoke(manager));

        assertEquals("previous recoverable snapshot", Files.readString(backup));
        assertEquals("manual backup", Files.readString(unrelated));
    }

    @Test
    void successfulBackupReplacesOnlyTheSnapshot() throws Exception {
        DockManager manager = presetManager();
        Path backup = configDir.resolve("backup").resolve(LayoutPresetBank.PRESETS_FILE);
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "old snapshot");
        Path unrelated = backup.getParent().resolve("manual-copy.json");
        Files.writeString(unrelated, "manual backup");
        Method backupMethod = DockManager.class.getDeclaredMethod("backupCurrentPresets");
        backupMethod.setAccessible(true);
        backupMethod.invoke(manager);

        LayoutPresetBank.Data restored = LayoutPresetBank.read(Files.readString(backup),
                (DockLayoutCodec) getField(manager, "layoutCodec"),
                (LayoutPresetBank.Data) getField(manager, "presets"));
        assertEquals(manager.workspaceSnapshot(), restored.workspaces()[0]);
        assertEquals("manual backup", Files.readString(unrelated));
    }

    private DockManager presetManager() throws Exception {
        DockWorkspace workspace = new DockWorkspace(List.of(root("owner", new DockRect(0, 0, 100, 80), true)));
        ModuleCatalog catalog = new ModuleCatalog(Map.of("module-owner",
                new ModuleMetrics(new DockSize(20, 20), new DockSize(100, 80))));
        LegacyMigrationContext context = new LegacyMigrationContext(DockInsets.NONE, 0, 8, 8, 16, 800, 600);
        DockLayoutCodec codec = new DockLayoutCodec(catalog, context);
        DockManager manager = new DockManager();
        setField(manager, "workspace", workspace);
        setField(manager, "viewportWorkspace", workspace);
        setField(manager, "workspaceRevision", 1L);
        setField(manager, "persistedRevision", 1L);
        setField(manager, "persistence", new CountingPersistence());
        setField(manager, "configDir", configDir);
        setField(manager, "layoutCodec", codec);
        setField(manager, "layoutSharing", new com.lhy.mest.client.dock.workspace.LayoutSharingService(configDir, codec));
        setField(manager, "persistenceCatalog", catalog);
        setField(manager, "migrationContext", context);
        setField(manager, "centerOnReturn", false);
        LayoutPresetBank.save(configDir.resolve(LayoutPresetBank.PRESETS_FILE),
                LayoutPresetBank.create(workspace, workspace, new String[]{"First", "Second", "Third"}), codec);
        loadPresets(manager, workspace);
        return manager;
    }

    private static void loadPresets(DockManager manager, DockWorkspace current) throws Exception {
        Method load = DockManager.class.getDeclaredMethod("loadPresetBank", DockWorkspace.class);
        load.setAccessible(true);
        load.invoke(manager, current);
    }

    private LayoutPresetBank.Data readPresets(DockManager manager) throws Exception {
        return LayoutPresetBank.read(Files.readString(configDir.resolve(LayoutPresetBank.PRESETS_FILE)),
                (DockLayoutCodec) getField(manager, "layoutCodec"),
                (LayoutPresetBank.Data) getField(manager, "presets"));
    }

    private Path blockPresetFile() throws IOException {
        Path file = configDir.resolve(LayoutPresetBank.PRESETS_FILE);
        Files.delete(file);
        Files.createDirectory(file);
        Path blocker = file.resolve("keep-directory-nonempty");
        Files.writeString(blocker, "blocks atomic replacement");
        return blocker;
    }

    private static FloatingRoot root(String id, DockRect bounds, boolean visible) {
        return new FloatingRoot(
                "root-" + id,
                bounds,
                new LeafNode("leaf-" + id, "module-" + id, visible));
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = DockManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws ReflectiveOperationException {
        Field field = DockManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean getBooleanField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = DockManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static long getLongField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = DockManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static final class TestPanel extends ModulePanel {
        private final String id;
        private final boolean joinableRail;

        private TestPanel(String id, boolean joinableRail) {
            this.id = id;
            this.joinableRail = joinableRail;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Component title() {
            return Component.literal(id);
        }

        @Override
        public int defaultWidth() {
            return 100;
        }

        @Override
        public int defaultHeight() {
            return 80;
        }

        @Override
        public void layoutSlots() {
        }

        @Override
        public int outsideHitWidth() {
            return 20;
        }

        @Override
        public boolean hasJoinableOutsideRail() {
            return joinableRail;
        }
    }

    private static final class CountingPersistence implements DockLayoutPersistence {
        private int saveCount;
        private DockWorkspace savedWorkspace;
        private boolean failNextSave;

        @Override
        public Optional<DockLayoutCodec.DecodedLayout> load() {
            return Optional.empty();
        }

        @Override
        public void save(DockWorkspace workspace) throws IOException {
            saveCount++;
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("simulated layout write failure");
            }
            savedWorkspace = workspace;
        }
    }
}
