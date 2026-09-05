package com.lhy.mest.client.dock.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable workspace containing structure and per-module runtime interaction policy. */
public record DockWorkspace(
        List<FloatingRoot> roots,
        Map<String, ModuleLayoutPolicy> policies,
        Map<String, DockSize> restoreSizes,
        SpliceMode spliceMode,
        Map<String, ContentOffset> contentOffsets) {
    public DockWorkspace(List<FloatingRoot> roots) {
        this(roots, defaultPolicies(roots), Map.of(), SpliceMode.DEFAULT, Map.of());
    }

    public DockWorkspace(List<FloatingRoot> roots, Map<String, ModuleLayoutPolicy> policies) {
        this(roots, policies, Map.of(), SpliceMode.DEFAULT, Map.of());
    }

    public DockWorkspace(
            List<FloatingRoot> roots,
            Map<String, ModuleLayoutPolicy> policies,
            Map<String, DockSize> restoreSizes) {
        this(roots, policies, restoreSizes, SpliceMode.DEFAULT, Map.of());
    }

    public DockWorkspace(
            List<FloatingRoot> roots,
            Map<String, ModuleLayoutPolicy> policies,
            Map<String, DockSize> restoreSizes,
            SpliceMode spliceMode) {
        this(roots, policies, restoreSizes, spliceMode, Map.of());
    }

    private static Map<String, ModuleLayoutPolicy> defaultPolicies(List<FloatingRoot> roots) {
        var policies = new LinkedHashMap<String, ModuleLayoutPolicy>();
        if (roots != null) {
            for (FloatingRoot root : roots) {
                collectPolicies(root.content(), policies);
            }
        }
        return policies;
    }

    private static void collectPolicies(LayoutNode node, Map<String, ModuleLayoutPolicy> policies) {
        if (node instanceof LeafNode leaf) {
            policies.putIfAbsent(
                    leaf.moduleId(),
                    new ModuleLayoutPolicy(leaf.visible(), true, true));
        } else if (node instanceof SplitNode split) {
            collectPolicies(split.first(), policies);
            collectPolicies(split.second(), policies);
        }
    }

    public DockWorkspace {
        if (roots == null || policies == null || restoreSizes == null || contentOffsets == null) {
            throw new NullPointerException("roots, policies, restoreSizes and contentOffsets");
        }
        roots = List.copyOf(roots);
        policies = Map.copyOf(new LinkedHashMap<>(policies));
        restoreSizes = Map.copyOf(new LinkedHashMap<>(restoreSizes));
        contentOffsets = Map.copyOf(new LinkedHashMap<>(contentOffsets));
        if (spliceMode == null) {
            spliceMode = SpliceMode.DEFAULT;
        }
    }

    public ModuleLayoutPolicy policyFor(String moduleId) {
        return policies.getOrDefault(moduleId, ModuleLayoutPolicy.defaults());
    }

    public DockWorkspace withRoots(List<FloatingRoot> newRoots) {
        return new DockWorkspace(newRoots, policies, restoreSizes, spliceMode, contentOffsets);
    }

    public DockWorkspace withPolicies(Map<String, ModuleLayoutPolicy> newPolicies) {
        return new DockWorkspace(roots, newPolicies, restoreSizes, spliceMode, contentOffsets);
    }

    public DockWorkspace withRestoreSizes(Map<String, DockSize> newRestoreSizes) {
        return new DockWorkspace(roots, policies, newRestoreSizes, spliceMode, contentOffsets);
    }

    public DockWorkspace withSpliceMode(SpliceMode newSpliceMode) {
        return new DockWorkspace(roots, policies, restoreSizes, newSpliceMode, contentOffsets);
    }

    public DockWorkspace withContentOffsets(Map<String, ContentOffset> newContentOffsets) {
        return new DockWorkspace(roots, policies, restoreSizes, spliceMode, newContentOffsets);
    }

    public ContentOffset contentOffset(String moduleId) {
        return contentOffsets.getOrDefault(moduleId, ContentOffset.ZERO);
    }

    /** True when the root bounds follow the leaves instead of stretching them to a fixed frame. */
    public boolean prefersCompactBounds() {
        return spliceMode.prefersCompactBounds();
    }

    /** True for the only mode that lets a nested split tuck into an L-shaped hole. */
    public boolean packsContour() {
        return spliceMode.packsContour();
    }

    /** True for the only mode where each leaf paints its own 9-slice frame. */
    public boolean drawsPerLeafFrames() {
        return spliceMode.drawsPerLeafFrames();
    }

    /** True when one background is drawn over the whole shell plus 1px section rules. */
    public boolean drawsOuterShell() {
        return spliceMode.drawsOuterShell();
    }

    public DockWorkspace rebuilt(List<FloatingRoot> newRoots, Map<String, DockSize> newRestoreSizes) {
        return new DockWorkspace(newRoots, policies, newRestoreSizes, spliceMode, contentOffsets);
    }

    /**
     * Last standalone window size for {@code nodeId}, placed at {@code fallback}'s origin.
     * Used when unsplicing so a panel does not keep the combined occupancy of its former siblings.
     */
    public DockRect restoredBounds(String nodeId, DockRect fallback) {
        if (fallback == null) {
            return null;
        }
        DockSize restore = restoreSizes.get(nodeId);
        if (restore == null || restore.isEmpty()) {
            return fallback;
        }
        return fallback.withSize(restore);
    }

    public static Map<String, DockSize> retainRestoreSizes(Map<String, DockSize> restoreSizes, List<FloatingRoot> roots) {
        if (restoreSizes == null || restoreSizes.isEmpty() || roots == null) {
            return Map.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        for (FloatingRoot root : roots) {
            collectNodeIds(root.content(), nodeIds);
        }
        var retained = new LinkedHashMap<String, DockSize>();
        for (var entry : restoreSizes.entrySet()) {
            if (nodeIds.contains(entry.getKey())) {
                retained.put(entry.getKey(), entry.getValue());
            }
        }
        return retained;
    }

    private static void collectNodeIds(LayoutNode node, Set<String> nodeIds) {
        nodeIds.add(node.nodeId());
        if (node instanceof SplitNode split) {
            collectNodeIds(split.first(), nodeIds);
            collectNodeIds(split.second(), nodeIds);
        }
    }
}
