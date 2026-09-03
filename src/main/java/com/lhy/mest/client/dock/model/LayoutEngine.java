package com.lhy.mest.client.dock.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Pure recursive measure/arrange engine. It performs no rendering or file IO. */
public final class LayoutEngine {
    /** Matches {@code ModulePanel.CONTENT_PADDING}; stacked sections drop this inset. */
    private static final int SECTION_BOTTOM_INSET = 7;

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
        boolean shell = workspace.drawsOuterShell();

        for (FloatingRoot root : workspace.roots()) {
            DockSize contentMinimum = measure(workspace, root.content(), builder.minimumSizes);
            DockSize rootMinimum = contentMinimum.isEmpty()
                    ? DockSize.ZERO
                    : new DockSize(
                            add(contentMinimum.width(), style.rootInsets().horizontal()),
                            add(contentMinimum.height(), style.rootInsets().vertical()));
            boolean effectivelyVisible = !contentMinimum.isEmpty();
            if (effectivelyVisible && shell) {
                // The shell keeps the root's own rectangle and hands every split the cross-axis
                // extent of its parent, so the leaf union is always a perfect rectangle and a
                // single background can cover it without any seam.
                DockRect contentBounds = root.bounds().inset(style.rootInsets());
                builder.roots.add(new LayoutProjection.RootPlacement(
                        root.rootId(), root.bounds(), contentBounds, rootMinimum, true));
                desiredMeasure(workspace, root.content(), builder.desiredSizes);
                arrangeShell(root.content(), contentBounds, builder);
            } else if (effectivelyVisible && workspace.prefersCompactBounds()) {
                CompactShape shape = compactShape(workspace, root.content());
                int contentX = root.bounds().x() + style.rootInsets().left();
                int contentY = root.bounds().y() + style.rootInsets().top();
                DockRect contentBounds = new DockRect(contentX, contentY, shape.width(), shape.height());
                DockRect rootBounds = new DockRect(
                        root.bounds().x(), root.bounds().y(),
                        add(shape.width(), style.rootInsets().horizontal()),
                        add(shape.height(), style.rootInsets().vertical()));
                builder.roots.add(new LayoutProjection.RootPlacement(
                        root.rootId(), rootBounds, contentBounds, rootMinimum, true));
                emitCompact(shape, contentX, contentY, builder);
            } else {
                DockRect contentBounds = root.bounds().inset(style.rootInsets());
                builder.roots.add(new LayoutProjection.RootPlacement(
                        root.rootId(), root.bounds(), contentBounds, rootMinimum, effectivelyVisible));
                if (effectivelyVisible) {
                    arrange(root.content(), contentBounds, builder);
                }
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

    /**
     * Bottom-up pass for {@link SpliceMode#SHELL}: the size each node actually wants, using the
     * restored (or default) leaf size instead of the hard minimum. On the split axis a subtree
     * wants the sum of its children plus dividers; on the cross axis it wants the widest child,
     * which is exactly what lets the parent align both children without leaving a hole.
     */
    private DockSize desiredMeasure(
            DockWorkspace workspace,
            LayoutNode node,
            Map<String, DockSize> measurements) {
        DockSize result;
        if (node instanceof LeafNode leaf) {
            if (!workspace.policyFor(leaf.moduleId()).visible()) {
                result = DockSize.ZERO;
            } else {
                DockSize minimum = catalog.metrics(leaf.moduleId()).minimumSize();
                DockSize desired = desiredSize(leaf, workspace);
                result = new DockSize(
                        Math.max(minimum.width(), desired.width()),
                        Math.max(minimum.height(), desired.height()));
            }
        } else {
            var split = (SplitNode) node;
            DockSize first = desiredMeasure(workspace, split.first(), measurements);
            DockSize second = desiredMeasure(workspace, split.second(), measurements);
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

    /**
     * Top-down pass for {@link SpliceMode#SHELL}. Unlike {@link #arrange} it spends the split axis
     * on preferred extents and only then hands out leftover, and like {@code arrange} it always
     * gives both children the full cross-axis extent of the parent. The result is a gap-free
     * rectangle, so no seam can appear regardless of rounding.
     */
    private void arrangeShell(
            LayoutNode node,
            DockRect bounds,
            ProjectionBuilder builder) {
        DockSize minimum = builder.minimumSizes.get(node.nodeId());
        if (minimum == null || minimum.isEmpty()) {
            return;
        }
        builder.effectivelyVisibleNodeIds.add(node.nodeId());

        if (node instanceof LeafNode leaf) {
            builder.visibleNodeBounds.put(node.nodeId(), bounds);
            var placement = new LayoutProjection.LeafPlacement(leaf.nodeId(), leaf.moduleId(), bounds);
            builder.visibleLeavesByNodeId.put(leaf.nodeId(), placement);
            builder.visibleLeavesInPaintOrder.add(placement);
            return;
        }

        var split = (SplitNode) node;
        builder.visibleNodeBounds.put(node.nodeId(), bounds);
        DockSize firstMinimum = builder.minimumSizes.get(split.first().nodeId());
        DockSize secondMinimum = builder.minimumSizes.get(split.second().nodeId());
        boolean firstVisible = firstMinimum != null && !firstMinimum.isEmpty();
        boolean secondVisible = secondMinimum != null && !secondMinimum.isEmpty();
        if (!firstVisible) {
            arrangeShell(split.second(), bounds, builder);
            return;
        }
        if (!secondVisible) {
            arrangeShell(split.first(), bounds, builder);
            return;
        }

        int totalExtent = split.axis().extent(bounds);
        int dividerExtent = Math.min(style.dividerThickness(), totalExtent);
        int available = totalExtent - dividerExtent;
        int firstExtent = allocateShellFirst(
                split,
                available,
                extentOrZero(builder.desiredSizes.get(split.first().nodeId()), split.axis()),
                extentOrZero(builder.desiredSizes.get(split.second().nodeId()), split.axis()),
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
        arrangeShell(split.first(), firstBounds, builder);
        arrangeShell(split.second(), secondBounds, builder);
    }

    /**
     * Spends the split axis on preferred extents first, then gives leftover to whichever side
     * declares {@code expandVertically}, falling back to the split ratio. Falls back to
     * minimum-proportional allocation when the rectangle cannot satisfy both minima.
     */
    private int allocateShellFirst(
            SplitNode split,
            int available,
            int desiredFirst,
            int desiredSecond,
            int minFirst,
            int minSecond) {
        if (available <= 0) {
            return 0;
        }
        long combinedMinimum = (long) minFirst + minSecond;
        if (combinedMinimum >= available) {
            if (combinedMinimum == 0) {
                return Math.max(0, Math.min(available, desiredFirst));
            }
            return (int) Math.round((double) available * minFirst / combinedMinimum);
        }
        int slack = available - (desiredFirst + desiredSecond);
        if (slack <= 0) {
            long combinedDesired = (long) Math.max(0, desiredFirst) + Math.max(0, desiredSecond);
            if (combinedDesired == 0) {
                return (int) Math.round(available * split.ratio());
            }
            int squeezed = (int) Math.round((double) available * desiredFirst / combinedDesired);
            return Math.max(minFirst, Math.min(available - minSecond, squeezed));
        }
        boolean firstExpands = expandsOn(split.first(), split.axis());
        boolean secondExpands = expandsOn(split.second(), split.axis());
        if (firstExpands && !secondExpands) {
            return Math.max(minFirst, Math.min(available - minSecond, available - desiredSecond));
        }
        if (secondExpands && !firstExpands) {
            return Math.max(minFirst, Math.min(available - minSecond, desiredFirst));
        }
        int grown = (int) Math.round(desiredFirst + slack * split.ratio());
        return Math.max(minFirst, Math.min(available - minSecond, grown));
    }

    private void arrange(
            LayoutNode node,
            DockRect bounds,
            ProjectionBuilder builder) {
        DockSize minimum = builder.minimumSizes.get(node.nodeId());
        if (minimum == null || minimum.isEmpty()) {
            return;
        }
        builder.effectivelyVisibleNodeIds.add(node.nodeId());

        if (node instanceof LeafNode leaf) {
            builder.visibleNodeBounds.put(node.nodeId(), bounds);
            var placement = new LayoutProjection.LeafPlacement(leaf.nodeId(), leaf.moduleId(), bounds);
            builder.visibleLeavesByNodeId.put(leaf.nodeId(), placement);
            builder.visibleLeavesInPaintOrder.add(placement);
            return;
        }

        var split = (SplitNode) node;
        builder.visibleNodeBounds.put(node.nodeId(), bounds);
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
        if (split.axis() == DockAxis.VERTICAL) {
            int packed = packFixedVerticalExtent(
                    split,
                    available,
                    firstExtent,
                    firstMinimum.height(),
                    secondMinimum.height());
            if (packed >= 0) {
                firstExtent = packed;
                secondExtent = available - firstExtent;
            }
        }

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

    /** Preferred occupied size of a subtree, used when the workspace fits roots to their content. */
    public DockSize preferredSize(DockWorkspace workspace, LayoutNode node) {
        if (workspace.drawsOuterShell()) {
            return desiredMeasure(workspace, node, new LinkedHashMap<>());
        }
        CompactShape shape = compactShape(workspace, node);
        return new DockSize(shape.width(), shape.height());
    }

    /**
     * Packs recursive split subtrees by their visible rectangle contour. This lets a child occupy
     * an existing L-shaped hole instead of reserving its subtree's entire bounding box as a column.
     */
    private CompactShape compactShape(DockWorkspace workspace, LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            if (!workspace.policyFor(leaf.moduleId()).visible()) {
                return CompactShape.empty();
            }
            ModuleMetrics metrics = catalog.metrics(leaf.moduleId());
            DockSize desired = desiredSize(leaf, workspace);
            int width = Math.max(metrics.minimumSize().width(), desired.width());
            int height = Math.max(metrics.minimumSize().height(), desired.height());
            DockRect bounds = new DockRect(0, 0, width, height);
            return CompactShape.leaf(leaf, bounds);
        }

        SplitNode split = (SplitNode) node;
        CompactShape first = compactShape(workspace, split.first());
        CompactShape second = compactShape(workspace, split.second());
        if (first.isEmpty()) {
            return second.withNodeBounds(split.nodeId());
        }
        if (second.isEmpty()) {
            return first.withNodeBounds(split.nodeId());
        }

        int shiftX = 0;
        int shiftY = 0;
        if (split.axis() == DockAxis.HORIZONTAL) {
            shiftX = horizontalContourShift(first.leaves, second.leaves);
            shiftX += style.dividerThickness();
        } else {
            shiftY = verticalContourShift(first.leaves, second.leaves);
            shiftY += style.dividerThickness();
        }
        CompactShape shiftedSecond = second.translate(shiftX, shiftY);
        return CompactShape.combine(split, first, shiftedSecond, style.dividerThickness());
    }

    private static int horizontalContourShift(List<CompactLeaf> first, List<CompactLeaf> second) {
        int shift = 0;
        boolean overlaps = false;
        for (CompactLeaf left : first) {
            for (CompactLeaf right : second) {
                if (!overlaps(left.bounds.y(), left.bounds.bottom(), right.bounds.y(), right.bounds.bottom())) {
                    continue;
                }
                overlaps = true;
                shift = Math.max(shift, left.bounds.right() - right.bounds.x());
            }
        }
        return overlaps ? shift : first.stream().mapToInt(leaf -> leaf.bounds.right()).max().orElse(0);
    }

    private static int verticalContourShift(List<CompactLeaf> first, List<CompactLeaf> second) {
        int shift = 0;
        boolean overlaps = false;
        for (CompactLeaf top : first) {
            for (CompactLeaf bottom : second) {
                if (!overlaps(top.bounds.x(), top.bounds.right(), bottom.bounds.x(), bottom.bounds.right())) {
                    continue;
                }
                overlaps = true;
                shift = Math.max(shift, top.bounds.bottom() - bottom.bounds.y());
            }
        }
        return overlaps ? shift : first.stream().mapToInt(leaf -> leaf.bounds.bottom()).max().orElse(0);
    }

    private static boolean overlaps(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        return firstStart < secondEnd && firstEnd > secondStart;
    }

    private static void emitCompact(CompactShape shape, int x, int y, ProjectionBuilder builder) {
        for (var entry : shape.nodeBounds.entrySet()) {
            DockRect bounds = entry.getValue();
            builder.visibleNodeBounds.put(entry.getKey(), translate(bounds, x, y));
            builder.effectivelyVisibleNodeIds.add(entry.getKey());
        }
        for (CompactLeaf leaf : shape.leaves) {
            DockRect bounds = translate(leaf.bounds, x, y);
            var placement = new LayoutProjection.LeafPlacement(leaf.nodeId, leaf.moduleId, bounds);
            builder.visibleLeavesByNodeId.put(leaf.nodeId, placement);
            builder.visibleLeavesInPaintOrder.add(placement);
        }
        for (CompactDivider divider : shape.dividers) {
            builder.dividers.add(new LayoutProjection.DividerPlacement(
                    divider.splitNodeId, divider.axis, translate(divider.bounds, x, y)));
        }
    }

    private static DockRect translate(DockRect bounds, int x, int y) {
        return new DockRect(bounds.x() + x, bounds.y() + y, bounds.width(), bounds.height());
    }

    private DockSize desiredSize(LeafNode leaf, DockWorkspace workspace) {
        DockSize restore = workspace.restoreSizes().get(leaf.nodeId());
        if (restore != null && !restore.isEmpty()) {
            return restore;
        }
        return catalog.metrics(leaf.moduleId()).defaultSize();
    }

    /**
     * Give leftover height to expanding leaves (ME list / pattern access). Fixed sections keep
     * their packed AE2 size so splice seams do not grow a blank band.
     */
    private int packFixedVerticalExtent(
            SplitNode split,
            int available,
            int firstExtent,
            int firstMinimum,
            int secondMinimum) {
        boolean firstExpands = expandsVertically(split.first());
        boolean secondExpands = expandsVertically(split.second());
        if (firstExpands == secondExpands) {
            return -1;
        }
        if (firstExpands) {
            int secondPreferred = Math.max(secondMinimum, preferredHeight(split.second(), true));
            int secondExtent = available - firstExtent;
            if (secondExtent > secondPreferred && available - secondPreferred >= firstMinimum) {
                return available - secondPreferred;
            }
            return -1;
        }
        int firstPreferred = Math.max(firstMinimum, preferredHeight(split.first(), false));
        if (firstExtent > firstPreferred && available - firstPreferred >= secondMinimum) {
            return firstPreferred;
        }
        return -1;
    }

    private boolean expandsVertically(LayoutNode node) {
        return expandsOn(node, DockAxis.VERTICAL);
    }

    private boolean expandsOn(LayoutNode node, DockAxis axis) {
        if (axis != DockAxis.VERTICAL) {
            return false;
        }
        if (node instanceof LeafNode leaf) {
            return catalog.metrics(leaf.moduleId()).expandVertically();
        }
        SplitNode split = (SplitNode) node;
        return expandsOn(split.first(), axis) || expandsOn(split.second(), axis);
    }

    private int preferredHeight(LayoutNode node, boolean bottomSection) {
        if (node instanceof LeafNode leaf) {
            ModuleMetrics metrics = catalog.metrics(leaf.moduleId());
            int standalone = metrics.defaultSize().height();
            if (bottomSection) {
                return standalone;
            }
            return Math.max(metrics.minimumSize().height(), standalone - SECTION_BOTTOM_INSET);
        }
        SplitNode split = (SplitNode) node;
        if (split.axis() == DockAxis.HORIZONTAL) {
            return Math.max(preferredHeight(split.first(), bottomSection), preferredHeight(split.second(), bottomSection));
        }
        return add(
                add(preferredHeight(split.first(), false), preferredHeight(split.second(), bottomSection)),
                style.dividerThickness());
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

    private static int extentOrZero(DockSize size, DockAxis axis) {
        return size == null ? 0 : axis.extent(size);
    }

    private static int add(int first, int second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException e) {
            throw new WorkspaceValidationException("layout size overflow");
        }
    }

    private record CompactLeaf(String nodeId, String moduleId, DockRect bounds) {
    }

    private record CompactDivider(String splitNodeId, DockAxis axis, DockRect bounds) {
    }

    private static final class CompactShape {
        private final List<CompactLeaf> leaves;
        private final List<CompactDivider> dividers;
        private final Map<String, DockRect> nodeBounds;
        private final int width;
        private final int height;

        private CompactShape(
                List<CompactLeaf> leaves,
                List<CompactDivider> dividers,
                Map<String, DockRect> nodeBounds) {
            this.leaves = List.copyOf(leaves);
            this.dividers = List.copyOf(dividers);
            this.nodeBounds = Map.copyOf(new LinkedHashMap<>(nodeBounds));
            int maxRight = 0;
            int maxBottom = 0;
            for (CompactLeaf leaf : leaves) {
                maxRight = Math.max(maxRight, leaf.bounds.right());
                maxBottom = Math.max(maxBottom, leaf.bounds.bottom());
            }
            this.width = maxRight;
            this.height = maxBottom;
        }

        static CompactShape empty() {
            return new CompactShape(List.of(), List.of(), Map.of());
        }

        static CompactShape leaf(LeafNode leaf, DockRect bounds) {
            return new CompactShape(
                    List.of(new CompactLeaf(leaf.nodeId(), leaf.moduleId(), bounds)),
                    List.of(),
                    Map.of(leaf.nodeId(), bounds));
        }

        boolean isEmpty() {
            return leaves.isEmpty();
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        CompactShape translate(int x, int y) {
            var shiftedLeaves = new ArrayList<CompactLeaf>(leaves.size());
            for (CompactLeaf leaf : leaves) {
                shiftedLeaves.add(new CompactLeaf(
                        leaf.nodeId, leaf.moduleId, LayoutEngine.translate(leaf.bounds, x, y)));
            }
            var shiftedDividers = new ArrayList<CompactDivider>(dividers.size());
            for (CompactDivider divider : dividers) {
                shiftedDividers.add(new CompactDivider(
                        divider.splitNodeId, divider.axis, LayoutEngine.translate(divider.bounds, x, y)));
            }
            var shiftedNodes = new LinkedHashMap<String, DockRect>();
            nodeBounds.forEach((id, bounds) -> shiftedNodes.put(id, LayoutEngine.translate(bounds, x, y)));
            return new CompactShape(shiftedLeaves, shiftedDividers, shiftedNodes);
        }

        CompactShape withNodeBounds(String nodeId) {
            var nodes = new LinkedHashMap<>(nodeBounds);
            nodes.put(nodeId, new DockRect(0, 0, width, height));
            return new CompactShape(leaves, dividers, nodes);
        }

        static CompactShape combine(
                SplitNode split,
                CompactShape first,
                CompactShape second,
                int dividerThickness) {
            var leaves = new ArrayList<CompactLeaf>(first.leaves.size() + second.leaves.size());
            leaves.addAll(first.leaves);
            leaves.addAll(second.leaves);
            var dividers = new ArrayList<CompactDivider>(first.dividers.size() + second.dividers.size() + 1);
            dividers.addAll(first.dividers);
            dividers.addAll(second.dividers);
            addContactDividers(dividers, split, first.leaves, second.leaves, dividerThickness);

            var nodes = new LinkedHashMap<String, DockRect>();
            nodes.putAll(first.nodeBounds);
            nodes.putAll(second.nodeBounds);
            CompactShape combined = new CompactShape(leaves, dividers, nodes);
            nodes.put(split.nodeId(), new DockRect(0, 0, combined.width, combined.height));
            return new CompactShape(leaves, dividers, nodes);
        }

        private static void addContactDividers(
                List<CompactDivider> output,
                SplitNode split,
                List<CompactLeaf> first,
                List<CompactLeaf> second,
                int thickness) {
            for (CompactLeaf firstLeaf : first) {
                for (CompactLeaf secondLeaf : second) {
                    DockRect a = firstLeaf.bounds;
                    DockRect b = secondLeaf.bounds;
                    if (split.axis() == DockAxis.HORIZONTAL) {
                        int top = Math.max(a.y(), b.y());
                        int bottom = Math.min(a.bottom(), b.bottom());
                        if (bottom > top && b.x() - a.right() == thickness) {
                            output.add(new CompactDivider(
                                    split.nodeId(), split.axis(),
                                    new DockRect(a.right(), top, thickness, bottom - top)));
                        }
                    } else {
                        int left = Math.max(a.x(), b.x());
                        int right = Math.min(a.right(), b.right());
                        if (right > left && b.y() - a.bottom() == thickness) {
                            output.add(new CompactDivider(
                                    split.nodeId(), split.axis(),
                                    new DockRect(left, a.bottom(), right - left, thickness)));
                        }
                    }
                }
            }
        }
    }

    private static final class ProjectionBuilder {
        private final ArrayList<LayoutProjection.RootPlacement> roots = new ArrayList<>();
        private final LinkedHashMap<String, DockSize> minimumSizes = new LinkedHashMap<>();
        private final LinkedHashMap<String, DockSize> desiredSizes = new LinkedHashMap<>();
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
