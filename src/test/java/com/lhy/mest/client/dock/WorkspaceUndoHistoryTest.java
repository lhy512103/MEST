package com.lhy.mest.client.dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;

class WorkspaceUndoHistoryTest {

    private static DockWorkspace workspace(String rootId) {
        return new DockWorkspace(List.of(
                new FloatingRoot(rootId, new DockRect(0, 0, 100, 80),
                        new LeafNode("leaf-" + rootId, rootId, true))));
    }

    @Test
    void rememberSkipsIdenticalSnapshot() {
        var history = new WorkspaceUndoHistory(4);
        var snapshot = workspace("a");
        assertTrue(history.remember(snapshot));
        assertFalse(history.remember(snapshot), "dedup against the current top");
        assertEquals(1, history.size());
    }

    @Test
    void popReturnsNullWhenEmpty() {
        assertNull(new WorkspaceUndoHistory(4).pop());
    }

    @Test
    void popReturnsAndRemovesMostRecent() {
        var history = new WorkspaceUndoHistory(4);
        var first = workspace("a");
        var second = workspace("b");
        history.remember(first);
        history.remember(second);
        assertEquals(second, history.pop());
        assertEquals(first, history.pop());
        assertTrue(history.isEmpty());
    }

    @Test
    void rememberHonorsBoundedSize() {
        var history = new WorkspaceUndoHistory(2);
        history.remember(workspace("a"));
        history.remember(workspace("b"));
        history.remember(workspace("c"));
        history.remember(workspace("d"));
        assertEquals(2, history.size());
        // The two most recent remain; the oldest two are dropped.
        assertEquals(workspace("d"), history.pop());
        assertEquals(workspace("c"), history.pop());
        assertTrue(history.isEmpty());
    }

    @Test
    void rememberIgnoresNullSnapshot() {
        var history = new WorkspaceUndoHistory(2);
        assertFalse(history.remember(null));
        assertTrue(history.isEmpty());
    }

    @Test
    void clearEmptiesHistory() {
        var history = new WorkspaceUndoHistory(2);
        history.remember(workspace("a"));
        history.clear();
        assertTrue(history.isEmpty());
    }

    @Test
    void constructorRejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceUndoHistory(0));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceUndoHistory(-1));
    }
}
