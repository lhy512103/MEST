package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

class LayoutEngineTest {
    @Test
    void recursivelyArrangesVisibleBranchesAndCollapsesHiddenOnes() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        LayoutNode nested = new SplitNode(
                "split-outer",
                DockAxis.HORIZONTAL,
                0.4,
                new LeafNode("leaf-a", "a", true),
                new SplitNode(
                        "split-inner",
                        DockAxis.VERTICAL,
                        0.5,
                        new LeafNode("leaf-b", "b", false),
                        new LeafNode("leaf-c", "c", true)));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 200, 100), nested)));

        LayoutProjection projection = new LayoutEngine(
                catalog,
                new LayoutStyle(DockInsets.NONE, 4)).project(workspace);

        assertEquals(new DockRect(0, 0, 78, 100), projection.visibleLeaf("leaf-a").orElseThrow().bounds());
        assertEquals(new DockRect(82, 0, 118, 100), projection.visibleLeaf("leaf-c").orElseThrow().bounds());
        assertTrue(projection.visibleLeaf("leaf-b").isEmpty());
        assertTrue(projection.isEffectivelyVisible("split-inner"));
        assertFalse(projection.isEffectivelyVisible("leaf-b"));
        assertEquals(1, projection.dividers().size(), "collapsed split must not emit a divider");
    }

    @Test
    void clampsBothChildrenToTheirMinimumWhenSpaceIsSufficient() {
        var metrics = new LinkedHashMap<String, ModuleMetrics>();
        metrics.put("wide", new ModuleMetrics(new DockSize(70, 20), new DockSize(70, 20)));
        metrics.put("narrow", new ModuleMetrics(new DockSize(20, 20), new DockSize(20, 20)));
        ModuleCatalog catalog = new ModuleCatalog(metrics);
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.HORIZONTAL,
                0.1,
                new LeafNode("leaf-wide", "wide", true),
                new LeafNode("leaf-narrow", "narrow", true));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 100, 40), split)));

        LayoutProjection projection = new LayoutEngine(
                catalog,
                new LayoutStyle(DockInsets.NONE, 4)).project(workspace);

        assertEquals(70, projection.visibleLeaf("leaf-wide").orElseThrow().bounds().width());
        assertEquals(26, projection.visibleLeaf("leaf-narrow").orElseThrow().bounds().width());
        assertEquals(new DockRect(70, 0, 4, 40), projection.dividers().getFirst().bounds());
    }

    @Test
    void retainsRootAndLeafPaintOrder() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b", "c");
        DockWorkspace workspace = new DockWorkspace(List.of(
                ModelTestFixtures.root("root-c", "leaf-c", "c", 0),
                ModelTestFixtures.root("root-a", "leaf-a", "a", 20),
                ModelTestFixtures.root("root-b", "leaf-b", "b", 40)));

        LayoutProjection projection = new LayoutEngine(
                catalog,
                new LayoutStyle(DockInsets.NONE, 4)).project(workspace);

        assertEquals(
                List.of("root-c", "root-a", "root-b"),
                projection.roots().stream().map(LayoutProjection.RootPlacement::rootId).toList());
        assertEquals(
                List.of("c", "a", "b"),
                projection.visibleLeavesInPaintOrder().stream()
                        .map(LayoutProjection.LeafPlacement::moduleId)
                        .toList());
    }
}
