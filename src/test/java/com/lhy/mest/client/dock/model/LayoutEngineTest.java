package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        new LeafNode("leaf-b", "b", true),
                        new LeafNode("leaf-c", "c", true)));
        var policies = new LinkedHashMap<String, ModuleLayoutPolicy>();
        policies.put("a", ModuleLayoutPolicy.defaults());
        policies.put("b", new ModuleLayoutPolicy(false, true, true));
        policies.put("c", ModuleLayoutPolicy.defaults());
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 200, 100), nested)), policies);

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

    @Test
    void compactSpliceKeepsFixedLeavesAtPreferredCrossSize() {
        var metrics = new LinkedHashMap<String, ModuleMetrics>();
        metrics.put("fixed", new ModuleMetrics(new DockSize(50, 40), new DockSize(80, 60)));
        metrics.put("flex", new ModuleMetrics(new DockSize(40, 30), new DockSize(90, 80), true));
        ModuleCatalog catalog = new ModuleCatalog(metrics);
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.VERTICAL,
                0.5,
                new LeafNode("leaf-fixed", "fixed", true),
                new LeafNode("leaf-flex", "flex", true));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 200, 200), split)))
                .withCompactSplice(true);

        LayoutProjection projection = new LayoutEngine(catalog, new LayoutStyle(DockInsets.NONE, 0))
                .project(workspace);

        assertEquals(new DockRect(0, 0, 80, 100), projection.visibleLeaf("leaf-fixed").orElseThrow().bounds());
        assertEquals(new DockRect(0, 100, 90, 100), projection.visibleLeaf("leaf-flex").orElseThrow().bounds());
    }

    @Test
    void compactSpliceUsesRestoredStandaloneSizeInsteadOfDefault() {
        var metrics = new LinkedHashMap<String, ModuleMetrics>();
        metrics.put("a", new ModuleMetrics(new DockSize(50, 40), new DockSize(80, 60)));
        metrics.put("b", new ModuleMetrics(new DockSize(40, 30), new DockSize(90, 80), true));
        ModuleCatalog catalog = new ModuleCatalog(metrics);
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.VERTICAL,
                0.4,
                new LeafNode("leaf-a", "a", true),
                new LeafNode("leaf-b", "b", true));
        var restore = new LinkedHashMap<String, DockSize>();
        restore.put("leaf-a", new DockSize(160, 70));
        restore.put("leaf-b", new DockSize(110, 90));
        DockWorkspace workspace = new DockWorkspace(
                List.of(new FloatingRoot("root-main", new DockRect(0, 0, 200, 200), split)),
                Map.of(),
                restore,
                true);

        LayoutProjection projection = new LayoutEngine(catalog, new LayoutStyle(DockInsets.NONE, 0))
                .project(workspace);

        assertEquals(new DockRect(0, 0, 160, 80), projection.visibleLeaf("leaf-a").orElseThrow().bounds());
        assertEquals(new DockRect(0, 80, 110, 120), projection.visibleLeaf("leaf-b").orElseThrow().bounds());
    }

    @Test
    void compactSplicePacksTwoFixedLeavesWithoutStretchingTheGap() {
        var metrics = new LinkedHashMap<String, ModuleMetrics>();
        metrics.put("a", new ModuleMetrics(new DockSize(50, 40), new DockSize(80, 60)));
        metrics.put("b", new ModuleMetrics(new DockSize(50, 40), new DockSize(70, 50)));
        ModuleCatalog catalog = new ModuleCatalog(metrics);
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.VERTICAL,
                0.5,
                new LeafNode("leaf-a", "a", true),
                new LeafNode("leaf-b", "b", true));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 200, 200), split)))
                .withCompactSplice(true);

        LayoutProjection projection = new LayoutEngine(catalog, new LayoutStyle(DockInsets.NONE, 0))
                .project(workspace);

        assertEquals(new DockRect(0, 0, 80, 100), projection.visibleLeaf("leaf-a").orElseThrow().bounds());
        assertEquals(new DockRect(0, 100, 70, 100), projection.visibleLeaf("leaf-b").orElseThrow().bounds());
    }
}
