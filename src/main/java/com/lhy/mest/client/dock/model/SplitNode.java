package com.lhy.mest.client.dock.model;

import java.util.Objects;

/** A recursive two-way split. Ratio is the fraction allocated to {@code first}. */
public record SplitNode(
        String nodeId,
        DockAxis axis,
        double ratio,
        LayoutNode first,
        LayoutNode second) implements LayoutNode {
    public SplitNode {
        NodeIds.requireValid(nodeId, "nodeId");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!Double.isFinite(ratio) || ratio <= 0.0 || ratio >= 1.0) {
            throw new IllegalArgumentException("split ratio must be finite and strictly between 0 and 1");
        }
    }

    public SplitNode withChildren(LayoutNode newFirst, LayoutNode newSecond) {
        return new SplitNode(nodeId, axis, ratio, newFirst, newSecond);
    }

    public SplitNode withRatio(double newRatio) {
        return ratio == newRatio ? this : new SplitNode(nodeId, axis, newRatio, first, second);
    }
}
