package com.lhy.mest.client.dock.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read-only recursive tree queries shared by layout, editing and integration code. */
public final class LayoutTrees {
    private LayoutTrees() {
    }

    public static Optional<LayoutNode> find(LayoutNode node, String nodeId) {
        if (node.nodeId().equals(nodeId)) {
            return Optional.of(node);
        }
        if (node instanceof SplitNode split) {
            var first = find(split.first(), nodeId);
            return first.isPresent() ? first : find(split.second(), nodeId);
        }
        return Optional.empty();
    }

    public static boolean contains(LayoutNode node, String nodeId) {
        return find(node, nodeId).isPresent();
    }

    public static boolean hasVisibleLeaf(LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return leaf.visible();
        }
        var split = (SplitNode) node;
        return hasVisibleLeaf(split.first()) || hasVisibleLeaf(split.second());
    }

    public static List<LeafNode> leaves(LayoutNode node) {
        var result = new ArrayList<LeafNode>();
        collectLeaves(node, result);
        return List.copyOf(result);
    }

    private static void collectLeaves(LayoutNode node, List<LeafNode> result) {
        if (node instanceof LeafNode leaf) {
            result.add(leaf);
            return;
        }
        var split = (SplitNode) node;
        collectLeaves(split.first(), result);
        collectLeaves(split.second(), result);
    }
}
