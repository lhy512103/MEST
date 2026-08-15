package com.lhy.mest.client.dock.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Pure recursive measure/arrange engine. It performs no rendering or file IO. */
public final class LayoutEngine {
    private final ModuleCatalog catalog;
    private final LayoutStyle style;

    public LayoutEngine(ModuleCatalog catalog, LayoutStyle style) {
        if (catalog == null || style == null) {
            throw new NullPointerException("catalog and style are required");
        }
        this.catalog = catalog;
        this.style = style;
    }

    public LayoutProjection project(DockWorkspace workspace) {
        WorkspaceValidator.validateStrict(workspace, catalog);
        var builder = new ProjectionBuilder();

        for (FloatingRoot root : workspace.roots()) {
            DockSize contentMinimum = measure(workspace, root.content(), builder.minimumSizes);
            DockSize rootMinimum = contentMinimum.isEmpty()
                    ? DockSize.ZERO
                    : new DockSize(
                            add(contentMinimum.width(), style.rootInsets().horizontal()),
                            add(contentMinimum.height(), style.rootInsets().vertical()));
            DockRect contentBounds = root.bounds().inset(style.rootInsets());
            boolean effectivelyVisible = !contentMinimum.isEmpty();
            builder.roots.add(new LayoutProjection.RootPlacement(
                    root.rootId(), root.bounds(), contentBounds, rootMinimum, effectivelyVisible));
            if (effectivelyVisible) {
                arrange(root.content(), contentBounds, builder);
            }
        }

        return builder.build();
    }

    private DockSize measure(
            DockWorkspace workspace,
            LayoutNode node,
            Map<String, DockSize> measurements) {
        DockSize result;
        if (node instanceof LeafNode leaf) {
            result = workspace.policyFor(leaf.moduleId()).visible()
                    ? catalog.metrics(leaf.moduleId()).minimumSize()
                    : DockSize.ZERO;
        } else {
            var split = (SplitNode) node;
            DockSize first = measure(workspace, split.first(), measurements);
            DockSize second = measure(workspace, split.second(), measurements);
            if (first.isEmpty()) {
                result = second;
            } else if (second.isEmpty()) {
                result = first;
            } else if (split.axis() == DockAxis.HORIZONTAL) {
                result = new DockSize(
                        add(add(first.width(), style.dividerThickness()), second.width()),
                        Math.max(first.height(), second.height()));
            } else {
                result = new DockSize(
                        Math.max(first.width(), second.width()),
                        add(add(first.height(), style.dividerThickness()), second.height()));
            }
        }
        measurements.put(node.nodeId(), result);
        return result;
    }

    private void arrange(LayoutNode node, DockRect bounds, ProjectionBuilder builder) {
        DockSize minimum = builder.minimumSizes.get(node.nodeId());
        if (minimum == null || minimum.isEmpty()) {
            return;
        }
        builder.visibleNodeBounds.put(node.nodeId(), bounds);
        builder.effectivelyVisibleNodeIds.add(node.nodeId());

        if (node instanceof LeafNode leaf) {
            var placement = new LayoutProjection.LeafPlacement(leaf.nodeId(), leaf.moduleId(), bounds);
            builder.visibleLeavesByNodeId.put(leaf.nodeId(), placement);
            builder.visibleLeavesInPaintOrder.add(placement);
            return;
        }

        var split = (SplitNode) node;
        DockSize firstMinimum = builder.minimumSizes.get(split.first().nodeId());
        DockSize secondMinimum = builder.minimumSizes.get(split.second().nodeId());
        boolean firstVisible = !firstMinimum.isEmpty();
        boolean secondVisible = !secondMinimum.isEmpty();
        if (!firstVisible) {
            arrange(split.second(), bounds, builder);
            return;
        }
        if (!secondVisible) {
            arrange(split.first(), bounds, builder);
            return;
        }

        int totalExtent = split.axis().extent(bounds);
        int dividerExtent = Math.min(style.dividerThickness(), totalExtent);
        int available = totalExtent - dividerExtent;
        int firstExtent = allocateFirst(
                available,
                split.ratio(),
                split.axis().extent(firstMinimum),
                split.axis().extent(secondMinimum));
        int secondExtent = available - firstExtent;

        DockRect firstBounds;
        DockRect dividerBounds;
        DockRect secondBounds;
        if (split.axis() == DockAxis.HORIZONTAL) {
            firstBounds = new DockRect(bounds.x(), bounds.y(), firstExtent, bounds.height());
            dividerBounds = new DockRect(bounds.x() + firstExtent, bounds.y(), dividerExtent, bounds.height());
            secondBounds = new DockRect(
                    dividerBounds.right(), bounds.y(), secondExtent, bounds.height());
        } else {
            firstBounds = new DockRect(bounds.x(), bounds.y(), bounds.width(), firstExtent);
            dividerBounds = new DockRect(bounds.x(), bounds.y() + firstExtent, bounds.width(), dividerExtent);
            secondBounds = new DockRect(
                    bounds.x(), dividerBounds.bottom(), bounds.width(), secondExtent);
        }
        builder.dividers.add(new LayoutProjection.DividerPlacement(
                split.nodeId(), split.axis(), dividerBounds));
        arrange(split.first(), firstBounds, builder);
        arrange(split.second(), secondBounds, builder);
    }

    private static int allocateFirst(int available, double ratio, int firstMinimum, int secondMinimum) {
        if (available <= 0) {
            return 0;
        }
        int desired = (int) Math.round(available * ratio);
        long combinedMinimum = (long) firstMinimum + secondMinimum;
        if (combinedMinimum <= available) {
            return Math.max(firstMinimum, Math.min(available - secondMinimum, desired));
        }
        if (combinedMinimum == 0) {
            return Math.max(0, Math.min(available, desired));
        }
        return (int) Math.round((double) available * firstMinimum / combinedMinimum);
    }

    private static int add(int first, int second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException e) {
            throw new WorkspaceValidationException("layout size overflow");
        }
    }

    private static final class ProjectionBuilder {
        private final ArrayList<LayoutProjection.RootPlacement> roots = new ArrayList<>();
        private final LinkedHashMap<String, DockSize> minimumSizes = new LinkedHashMap<>();
        private final LinkedHashMap<String, DockRect> visibleNodeBounds = new LinkedHashMap<>();
        private final LinkedHashMap<String, LayoutProjection.LeafPlacement> visibleLeavesByNodeId =
                new LinkedHashMap<>();
        private final ArrayList<LayoutProjection.LeafPlacement> visibleLeavesInPaintOrder = new ArrayList<>();
        private final ArrayList<LayoutProjection.DividerPlacement> dividers = new ArrayList<>();
        private final LinkedHashSet<String> effectivelyVisibleNodeIds = new LinkedHashSet<>();

        private LayoutProjection build() {
            return new LayoutProjection(
                    roots,
                    minimumSizes,
                    visibleNodeBounds,
                    visibleLeavesByNodeId,
                    visibleLeavesInPaintOrder,
                    dividers,
                    effectivelyVisibleNodeIds);
        }
    }
}
