package com.lhy.mest.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;

import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;

class DockManagerTest {
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
        setField(manager, "mode", Enum.valueOf(modeClass(), "DRAG_ROOT"));
        return manager;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> modeClass() {
        try {
            return (Class<? extends Enum>) Class.forName(
                    "com.lhy.mest.client.dock.DockManager$Mode");
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
    void ctrlDragIgnoresHiddenRootsWhenClampingTheGroup() throws ReflectiveOperationException {
        FloatingRoot visible = root("visible", new DockRect(120, 28, 200, 150), true);
        FloatingRoot hidden = root("hidden", new DockRect(120, 28, 500, 400), false);
        DockWorkspace workspace = new DockWorkspace(
                List.of(visible, hidden),
                Map.of(
                        "module-visible", new ModuleLayoutPolicy(true, true, true),
                        "module-hidden", new ModuleLayoutPolicy(false, true, true)));

        DockManager manager = new DockManager();
        setField(manager, "workspace", workspace);
        setField(manager, "viewportWorkspace", workspace);
        setField(manager, "screenWidth", 800);
        setField(manager, "screenHeight", 600);
        setField(manager, "editingLayout", true);
        setField(manager, "editorInsetLeft", 120);
        setField(manager, "editorInsetTop", 28);
        setField(manager, "editorInsetRight", 140);
        setField(manager, "editorInsetBottom", 0);

        assertEquals(340, manager.clampedVisibleGroupDelta(400, true));
        assertEquals(422, manager.clampedVisibleGroupDelta(500, false));
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

        @Override
        public Optional<DockLayoutCodec.DecodedLayout> load() {
            return Optional.empty();
        }

        @Override
        public void save(DockWorkspace workspace) {
            saveCount++;
            savedWorkspace = workspace;
        }
    }
}
