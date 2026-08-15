package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DockWorkspaceEditorTest {
    @Test
    void insertsNestedSplitsThenDetachesAndCompressesTheParent() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspaceEditor editor = new DockWorkspaceEditor(catalog);
        DockWorkspace initial = workspace();

        DockWorkspace first = editor.insertSplit(
                initial, "leaf-a", "leaf-b", DockEdge.LEFT, "split-ab", 0.4);
        assertEquals(List.of("root-b", "root-c"), rootIds(first));
        SplitNode splitAb = assertInstanceOf(SplitNode.class, first.roots().getFirst().content());
        assertEquals("leaf-a", splitAb.first().nodeId());
        assertEquals("leaf-b", splitAb.second().nodeId());

        DockWorkspace nested = editor.insertSplit(
                first, "leaf-c", "leaf-b", DockEdge.BOTTOM, "split-bc", 0.5);
        assertEquals(List.of("root-b"), rootIds(nested));
        SplitNode outer = assertInstanceOf(SplitNode.class, nested.roots().getFirst().content());
        SplitNode inner = assertInstanceOf(SplitNode.class, outer.second());
        assertEquals("leaf-b", inner.first().nodeId());
        assertEquals("leaf-c", inner.second().nodeId());

        DockWorkspace detached = editor.detach(
                nested, "split-bc", "root-bc", new DockRect(200, 40, 160, 100));
        assertEquals(List.of("root-b", "root-bc"), rootIds(detached));
        assertEquals("leaf-a", detached.roots().getFirst().content().nodeId());
        assertEquals("split-bc", detached.roots().getLast().content().nodeId());
    }

    @Test
    void hidingLeafPreservesStructureButChangesEffectiveVisibility() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspaceEditor editor = new DockWorkspaceEditor(catalog);
        DockWorkspace joined = editor.insertSplit(
                workspace(), "leaf-a", "leaf-b", DockEdge.LEFT, "split-ab", 0.5);

        DockWorkspace hidden = editor.setLeafVisible(joined, "leaf-a", false);
        SplitNode split = assertInstanceOf(SplitNode.class, hidden.roots().getFirst().content());
        assertEquals("split-ab", split.nodeId(), "visibility must not rewrite the tree");
        assertTrue(((LeafNode) split.first()).visible(), "tree visibility is normalized structural data");
        assertFalse(hidden.policyFor("a").visible());

        LayoutProjection projection = new LayoutEngine(
                catalog,
                new LayoutStyle(DockInsets.NONE, 4)).project(hidden);
        assertFalse(projection.isEffectivelyVisible("leaf-a"));
        assertEquals(
                hidden.roots().getFirst().bounds(),
                projection.visibleLeaf("leaf-b").orElseThrow().bounds());
    }

    @Test
    void rejectsDockingAnAncestorWithItsDescendant() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspaceEditor editor = new DockWorkspaceEditor(catalog);
        DockWorkspace joined = editor.insertSplit(
                workspace(), "leaf-a", "leaf-b", DockEdge.LEFT, "split-ab", 0.5);

        assertThrows(
                IllegalArgumentException.class,
                () -> editor.insertSplit(
                        joined, "leaf-a", "split-ab", DockEdge.LEFT, "split-new", 0.5));
    }

    @Test
    void raisesRootWithoutReorderingOthers() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspace raised = new DockWorkspaceEditor(catalog).raiseRoot(workspace(), "root-a");
        assertEquals(List.of("root-b", "root-c", "root-a"), rootIds(raised));
    }

    @Test
    void updatesNestedSplitRatioWithoutRewritingItsChildren() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspaceEditor editor = new DockWorkspaceEditor(catalog);
        DockWorkspace first = editor.insertSplit(
                workspace(), "leaf-a", "leaf-b", DockEdge.LEFT, "split-ab", 0.4);
        DockWorkspace nested = editor.insertSplit(
                first, "leaf-c", "leaf-b", DockEdge.BOTTOM, "split-bc", 0.5);

        DockWorkspace resized = editor.setSplitRatio(nested, "split-bc", 0.7);

        SplitNode outer = assertInstanceOf(SplitNode.class, resized.roots().getFirst().content());
        SplitNode inner = assertInstanceOf(SplitNode.class, outer.second());
        assertEquals(0.7, inner.ratio());
        assertEquals("leaf-b", inner.first().nodeId());
        assertEquals("leaf-c", inner.second().nodeId());
    }

    @Test
    void supportsFourLevelsAndDetachingADeepSubtree() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c", "d", "e");
        DockWorkspaceEditor editor = new DockWorkspaceEditor(catalog);
        DockWorkspace current = new DockWorkspace(List.of(
                ModelTestFixtures.root("root-a", "leaf-a", "a", 0),
                ModelTestFixtures.root("root-b", "leaf-b", "b", 20),
                ModelTestFixtures.root("root-c", "leaf-c", "c", 40),
                ModelTestFixtures.root("root-d", "leaf-d", "d", 60),
                ModelTestFixtures.root("root-e", "leaf-e", "e", 80)));
        current = editor.insertSplit(current, "leaf-a", "leaf-b", DockEdge.LEFT, "split-ab", 0.5);
        current = editor.insertSplit(current, "leaf-c", "leaf-b", DockEdge.TOP, "split-bc", 0.5);
        current = editor.insertSplit(current, "leaf-d", "leaf-b", DockEdge.RIGHT, "split-bd", 0.5);
        current = editor.insertSplit(current, "leaf-e", "leaf-b", DockEdge.BOTTOM, "split-be", 0.5);

        LayoutProjection projection = new LayoutEngine(
                catalog,
                new LayoutStyle(DockInsets.NONE, 4)).project(current);
        assertEquals(5, projection.visibleLeavesInPaintOrder().size());

        DockWorkspace detached = editor.detach(
                current, "split-be", "root-be", new DockRect(200, 40, 160, 100));
        WorkspaceValidator.validateStrict(detached, catalog);
        assertEquals(2, detached.roots().size());
        assertEquals("split-be", detached.roots().getLast().content().nodeId());
    }

    private static DockWorkspace workspace() {
        return new DockWorkspace(List.of(
                ModelTestFixtures.root("root-a", "leaf-a", "a", 0),
                ModelTestFixtures.root("root-b", "leaf-b", "b", 20),
                ModelTestFixtures.root("root-c", "leaf-c", "c", 40)));
    }

    private static List<String> rootIds(DockWorkspace workspace) {
        return workspace.roots().stream().map(FloatingRoot::rootId).toList();
    }
}
