package com.lhy.mest.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.workspace.DockLayoutCodec;
import com.lhy.mest.client.dock.workspace.DockLayoutPersistence;

class DockManagerTest {
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

        assertTrue(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner, hiddenHigher)), owner.rootId(), new DockRect(82, 1, 16, 18)));
    }

    @Test
    void areaMustBelongToTheOwningRoot() {
        FloatingRoot owner = root("owner", new DockRect(0, 0, 100, 80), true);

        assertFalse(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner)), owner.rootId(), new DockRect(90, 1, 16, 18)));
        assertFalse(DockManager.isAreaUnobscured(
                new DockWorkspace(List.of(owner)), "missing", new DockRect(10, 1, 16, 18)));
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
