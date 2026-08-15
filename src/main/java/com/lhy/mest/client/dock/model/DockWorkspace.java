package com.lhy.mest.client.dock.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable workspace containing structure and per-module runtime interaction policy. */
public record DockWorkspace(List<FloatingRoot> roots, Map<String, ModuleLayoutPolicy> policies) {
    public DockWorkspace(List<FloatingRoot> roots) {
        this(roots, defaultPolicies(roots));
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
        if (roots == null || policies == null) {
            throw new NullPointerException("roots and policies");
        }
        roots = List.copyOf(roots);
        policies = Map.copyOf(new LinkedHashMap<>(policies));
    }

    public ModuleLayoutPolicy policyFor(String moduleId) {
        return policies.getOrDefault(moduleId, ModuleLayoutPolicy.defaults());
    }
}
