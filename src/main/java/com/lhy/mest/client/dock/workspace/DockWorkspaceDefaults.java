package com.lhy.mest.client.dock.workspace;

import java.util.ArrayList;

import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.model.NodeIds;

/** Deterministic default roots for registered modules. */
public final class DockWorkspaceDefaults {
    private DockWorkspaceDefaults() {
    }

    public static DockWorkspace create(ModuleCatalog catalog, LegacyMigrationContext geometry) {
        var roots = new ArrayList<FloatingRoot>();
        int index = 0;
        for (String moduleId : catalog.moduleIds()) {
            var leaf = new LeafNode(leafNodeId(moduleId), moduleId, defaultVisible(moduleId));
            roots.add(new FloatingRoot(
                    rootId(moduleId),
                    geometry.defaultRootBounds(catalog.metrics(moduleId), index++),
                    leaf));
        }
        DockWorkspace workspace = new DockWorkspace(roots);
        var policies = new java.util.LinkedHashMap<>(workspace.policies());
        for (String moduleId : catalog.moduleIds()) {
            ModuleLayoutPolicy policy = workspace.policyFor(moduleId)
                    .withShowTerminalButton(defaultShowTerminalButton(moduleId));
            if (defaultFloating(moduleId) || defaultPinned(moduleId)) {
                policy = policy
                        .withFloating(defaultFloating(moduleId) || defaultPinned(moduleId))
                        .withPinned(defaultPinned(moduleId));
            }
            policies.put(moduleId, policy);
        }
        return workspace.withPolicies(policies);
    }

    public static boolean defaultVisible(String moduleId) {
        return !"provider_select".equals(moduleId)
                && !"wireless_settings".equals(moduleId)
                && !"trash".equals(moduleId);
    }

    public static boolean defaultFloating(String moduleId) {
        return "wireless_settings".equals(moduleId) || "trash".equals(moduleId);
    }

    public static boolean defaultPinned(String moduleId) {
        return "trash".equals(moduleId);
    }

    public static boolean defaultShowTerminalButton(String moduleId) {
        return !"provider_select".equals(moduleId) && !"wireless_settings".equals(moduleId);
    }

    public static String leafNodeId(String moduleId) {
        return NodeIds.deterministic("leaf", moduleId);
    }

    public static String rootId(String moduleId) {
        return NodeIds.deterministic("root", moduleId);
    }
}
