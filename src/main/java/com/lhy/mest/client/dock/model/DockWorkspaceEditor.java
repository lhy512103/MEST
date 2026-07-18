package com.lhy.mest.client.dock.model;

import java.util.ArrayList;

/** Immutable tree mutations used by drag, splice, detach, hide and Z-order controls. */
public final class DockWorkspaceEditor {
    private final ModuleCatalog catalog;

    public DockWorkspaceEditor(ModuleCatalog catalog) {
        if (catalog == null) {
            throw new NullPointerException("catalog");
        }
        this.catalog = catalog;
    }

    public DockWorkspace insertSplit(
            DockWorkspace workspace,
            String draggedNodeId,
            String targetNodeId,
            DockEdge edge,
            String splitNodeId,
            double ratio) {
        validate(workspace);
        NodeIds.requireValid(draggedNodeId, "draggedNodeId");
        NodeIds.requireValid(targetNodeId, "targetNodeId");
        NodeIds.requireValid(splitNodeId, "splitNodeId");
        if (edge == null) {
            throw new NullPointerException("edge");
        }
        if (draggedNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("cannot split a node with itself");
        }
        if (identifierExists(workspace, splitNodeId)) {
            throw new IllegalArgumentException("split identifier already exists: " + splitNodeId);
        }

        LayoutNode dragged = findRequired(workspace, draggedNodeId);
        LayoutNode target = findRequired(workspace, targetNodeId);
        if (LayoutTrees.contains(dragged, targetNodeId) || LayoutTrees.contains(target, draggedNodeId)) {
            throw new IllegalArgumentException("dragged and target subtrees must be disjoint");
        }

        var roots = new ArrayList<>(workspace.roots());
        int draggedRootIndex = rootIndexContaining(roots, draggedNodeId);
        FloatingRoot draggedRoot = roots.get(draggedRootIndex);
        Removal removal = remove(draggedRoot.content(), draggedNodeId);
        if (removal.remaining() == null) {
            roots.remove(draggedRootIndex);
        } else {
            roots.set(draggedRootIndex, draggedRoot.withContent(removal.remaining()));
        }

        int targetRootIndex = rootIndexContaining(roots, targetNodeId);
        FloatingRoot targetRoot = roots.get(targetRootIndex);
        LayoutNode first = edge.draggedFirst() ? removal.removed() : target;
        LayoutNode second = edge.draggedFirst() ? target : removal.removed();
        LayoutNode replacement = new SplitNode(splitNodeId, edge.axis(), ratio, first, second);
        Replace replacementResult = replace(targetRoot.content(), targetNodeId, replacement);
        if (!replacementResult.replaced()) {
            throw new IllegalStateException("target disappeared during split insertion");
        }
        roots.set(targetRootIndex, targetRoot.withContent(replacementResult.node()));
        return validated(new DockWorkspace(roots));
    }

    public DockWorkspace detach(
            DockWorkspace workspace,
            String nodeId,
            String newRootId,
            DockRect newBounds) {
        validate(workspace);
        NodeIds.requireValid(nodeId, "nodeId");
        NodeIds.requireValid(newRootId, "newRootId");
        if (identifierExists(workspace, newRootId)) {
            throw new IllegalArgumentException("root identifier already exists: " + newRootId);
        }

        var roots = new ArrayList<>(workspace.roots());
        int rootIndex = rootIndexContaining(roots, nodeId);
        FloatingRoot sourceRoot = roots.get(rootIndex);
        if (sourceRoot.content().nodeId().equals(nodeId)) {
            throw new IllegalArgumentException("node is already a floating root");
        }
        Removal removal = remove(sourceRoot.content(), nodeId);
        if (removal.removed() == null || removal.remaining() == null) {
            throw new IllegalStateException("failed to detach node " + nodeId);
        }
        roots.set(rootIndex, sourceRoot.withContent(removal.remaining()));
        roots.add(new FloatingRoot(newRootId, newBounds, removal.removed()));
        return validated(new DockWorkspace(roots));
    }

    public DockWorkspace setLeafVisible(DockWorkspace workspace, String leafNodeId, boolean visible) {
        validate(workspace);
        var roots = new ArrayList<>(workspace.roots());
        for (int i = 0; i < roots.size(); i++) {
            FloatingRoot root = roots.get(i);
            VisibilityChange change = setVisible(root.content(), leafNodeId, visible);
            if (change.found()) {
                roots.set(i, root.withContent(change.node()));
                return validated(new DockWorkspace(roots));
            }
        }
        throw new IllegalArgumentException("unknown leaf node: " + leafNodeId);
    }

    public DockWorkspace setRootBounds(DockWorkspace workspace, String rootId, DockRect bounds) {
        validate(workspace);
        var roots = new ArrayList<>(workspace.roots());
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).rootId().equals(rootId)) {
                roots.set(i, roots.get(i).withBounds(bounds));
                return validated(new DockWorkspace(roots));
            }
        }
        throw new IllegalArgumentException("unknown root: " + rootId);
    }

    public DockWorkspace setSplitRatio(DockWorkspace workspace, String splitNodeId, double ratio) {
        validate(workspace);
        NodeIds.requireValid(splitNodeId, "splitNodeId");
        var roots = new ArrayList<>(workspace.roots());
        for (int i = 0; i < roots.size(); i++) {
            FloatingRoot root = roots.get(i);
            RatioChange change = setRatio(root.content(), splitNodeId, ratio);
            if (change.found()) {
                roots.set(i, root.withContent(change.node()));
                return validated(new DockWorkspace(roots));
            }
        }
        throw new IllegalArgumentException("unknown split node: " + splitNodeId);
    }

    public DockWorkspace raiseRoot(DockWorkspace workspace, String rootId) {
        validate(workspace);
        var roots = new ArrayList<>(workspace.roots());
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).rootId().equals(rootId)) {
                FloatingRoot root = roots.remove(i);
                roots.add(root);
                return validated(new DockWorkspace(roots));
            }
        }
        throw new IllegalArgumentException("unknown root: " + rootId);
    }

    private void validate(DockWorkspace workspace) {
        WorkspaceValidator.validateStrict(workspace, catalog);
    }

    private DockWorkspace validated(DockWorkspace workspace) {
        validate(workspace);
        return workspace;
    }

    private static LayoutNode findRequired(DockWorkspace workspace, String nodeId) {
        for (FloatingRoot root : workspace.roots()) {
            var found = LayoutTrees.find(root.content(), nodeId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalArgumentException("unknown node: " + nodeId);
    }

    private static int rootIndexContaining(ArrayList<FloatingRoot> roots, String nodeId) {
        for (int i = 0; i < roots.size(); i++) {
            if (LayoutTrees.contains(roots.get(i).content(), nodeId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown node: " + nodeId);
    }

    private static boolean identifierExists(DockWorkspace workspace, String identifier) {
        for (FloatingRoot root : workspace.roots()) {
            if (root.rootId().equals(identifier) || LayoutTrees.contains(root.content(), identifier)) {
                return true;
            }
        }
        return false;
    }

    private static Removal remove(LayoutNode node, String nodeId) {
        if (node.nodeId().equals(nodeId)) {
            return new Removal(null, node);
        }
        if (!(node instanceof SplitNode split)) {
            return new Removal(node, null);
        }

        Removal firstRemoval = remove(split.first(), nodeId);
        if (firstRemoval.removed() != null) {
            LayoutNode remaining = firstRemoval.remaining() == null
                    ? split.second()
                    : split.withChildren(firstRemoval.remaining(), split.second());
            return new Removal(remaining, firstRemoval.removed());
        }

        Removal secondRemoval = remove(split.second(), nodeId);
        if (secondRemoval.removed() != null) {
            LayoutNode remaining = secondRemoval.remaining() == null
                    ? split.first()
                    : split.withChildren(split.first(), secondRemoval.remaining());
            return new Removal(remaining, secondRemoval.removed());
        }
        return new Removal(node, null);
    }

    private static Replace replace(LayoutNode node, String nodeId, LayoutNode replacement) {
        if (node.nodeId().equals(nodeId)) {
            return new Replace(replacement, true);
        }
        if (!(node instanceof SplitNode split)) {
            return new Replace(node, false);
        }
        Replace first = replace(split.first(), nodeId, replacement);
        if (first.replaced()) {
            return new Replace(split.withChildren(first.node(), split.second()), true);
        }
        Replace second = replace(split.second(), nodeId, replacement);
        if (second.replaced()) {
            return new Replace(split.withChildren(split.first(), second.node()), true);
        }
        return new Replace(node, false);
    }

    private static VisibilityChange setVisible(LayoutNode node, String leafNodeId, boolean visible) {
        if (node.nodeId().equals(leafNodeId)) {
            if (!(node instanceof LeafNode leaf)) {
                throw new IllegalArgumentException("node is not a leaf: " + leafNodeId);
            }
            return new VisibilityChange(leaf.withVisible(visible), true);
        }
        if (!(node instanceof SplitNode split)) {
            return new VisibilityChange(node, false);
        }
        VisibilityChange first = setVisible(split.first(), leafNodeId, visible);
        if (first.found()) {
            return new VisibilityChange(split.withChildren(first.node(), split.second()), true);
        }
        VisibilityChange second = setVisible(split.second(), leafNodeId, visible);
        if (second.found()) {
            return new VisibilityChange(split.withChildren(split.first(), second.node()), true);
        }
        return new VisibilityChange(node, false);
    }

    private static RatioChange setRatio(LayoutNode node, String splitNodeId, double ratio) {
        if (node.nodeId().equals(splitNodeId)) {
            if (!(node instanceof SplitNode split)) {
                throw new IllegalArgumentException("node is not a split: " + splitNodeId);
            }
            return new RatioChange(split.withRatio(ratio), true);
        }
        if (!(node instanceof SplitNode split)) {
            return new RatioChange(node, false);
        }
        RatioChange first = setRatio(split.first(), splitNodeId, ratio);
        if (first.found()) {
            return new RatioChange(split.withChildren(first.node(), split.second()), true);
        }
        RatioChange second = setRatio(split.second(), splitNodeId, ratio);
        if (second.found()) {
            return new RatioChange(split.withChildren(split.first(), second.node()), true);
        }
        return new RatioChange(node, false);
    }

    private record Removal(LayoutNode remaining, LayoutNode removed) {
    }

    private record Replace(LayoutNode node, boolean replaced) {
    }

    private record VisibilityChange(LayoutNode node, boolean found) {
    }

    private record RatioChange(LayoutNode node, boolean found) {
    }
}
