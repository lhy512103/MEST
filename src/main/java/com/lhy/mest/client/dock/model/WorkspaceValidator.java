package com.lhy.mest.client.dock.model;

import java.util.HashSet;
import java.util.Set;

/** Strict structural validation used at persistence and mutation boundaries. */
public final class WorkspaceValidator {
    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 1024;

    private WorkspaceValidator() {
    }

    public static void validateStrict(DockWorkspace workspace, ModuleCatalog catalog) {
        if (workspace == null) {
            throw new WorkspaceValidationException("workspace must not be null");
        }
        if (catalog == null) {
            throw new WorkspaceValidationException("module catalog must not be null");
        }

        var identifiers = new HashSet<String>();
        var moduleIds = new HashSet<String>();
        var nodeCount = new Counter();
        for (FloatingRoot root : workspace.roots()) {
            if (!identifiers.add(root.rootId())) {
                throw new WorkspaceValidationException("duplicate identifier: " + root.rootId());
            }
            validateNode(root.content(), catalog, identifiers, moduleIds, nodeCount, 1);
        }

        if (!moduleIds.equals(catalog.moduleIds())) {
            Set<String> missing = new HashSet<>(catalog.moduleIds());
            missing.removeAll(moduleIds);
            Set<String> unexpected = new HashSet<>(moduleIds);
            unexpected.removeAll(catalog.moduleIds());
            throw new WorkspaceValidationException(
                    "module set differs from catalog; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private static void validateNode(
            LayoutNode node,
            ModuleCatalog catalog,
            Set<String> identifiers,
            Set<String> moduleIds,
            Counter nodeCount,
            int depth) {
        if (depth > MAX_DEPTH) {
            throw new WorkspaceValidationException("layout exceeds maximum depth " + MAX_DEPTH);
        }
        if (++nodeCount.value > MAX_NODES) {
            throw new WorkspaceValidationException("layout exceeds maximum node count " + MAX_NODES);
        }
        if (!identifiers.add(node.nodeId())) {
            throw new WorkspaceValidationException("duplicate identifier: " + node.nodeId());
        }

        if (node instanceof LeafNode leaf) {
            if (!catalog.contains(leaf.moduleId())) {
                throw new WorkspaceValidationException("unknown module: " + leaf.moduleId());
            }
            if (!moduleIds.add(leaf.moduleId())) {
                throw new WorkspaceValidationException("duplicate module: " + leaf.moduleId());
            }
            return;
        }

        var split = (SplitNode) node;
        validateNode(split.first(), catalog, identifiers, moduleIds, nodeCount, depth + 1);
        validateNode(split.second(), catalog, identifiers, moduleIds, nodeCount, depth + 1);
    }

    private static final class Counter {
        private int value;
    }
}
