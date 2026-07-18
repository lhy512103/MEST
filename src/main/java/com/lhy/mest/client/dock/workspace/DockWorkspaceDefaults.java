package com.lhy.mest.client.dock.workspace;

import java.util.ArrayList;

import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.NodeIds;

/** Deterministic default roots for registered modules. */
public final class DockWorkspaceDefaults {
    private DockWorkspaceDefaults() {
    }

    public static DockWorkspace create(ModuleCatalog catalog, LegacyMigrationContext geometry) {
        var roots = new ArrayList<FloatingRoot>();
        int index = 0;
        for (String moduleId : catalog.moduleIds()) {
            var leaf = new LeafNode(leafNodeId(moduleId), moduleId, true);
            roots.add(new FloatingRoot(
                    rootId(moduleId),
                    geometry.defaultRootBounds(catalog.metrics(moduleId), index++),
                    leaf));
        }
        return new DockWorkspace(roots);
    }

    public static String leafNodeId(String moduleId) {
        return NodeIds.deterministic("leaf", moduleId);
    }

    public static String rootId(String moduleId) {
        return NodeIds.deterministic("root", moduleId);
    }
}
