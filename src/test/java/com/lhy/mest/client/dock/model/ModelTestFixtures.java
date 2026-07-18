package com.lhy.mest.client.dock.model;

import java.util.LinkedHashMap;

final class ModelTestFixtures {
    private ModelTestFixtures() {
    }

    static ModuleCatalog catalog(String... moduleIds) {
        var entries = new LinkedHashMap<String, ModuleMetrics>();
        for (String moduleId : moduleIds) {
            entries.put(moduleId, new ModuleMetrics(new DockSize(20, 20), new DockSize(80, 60)));
        }
        return new ModuleCatalog(entries);
    }

    static FloatingRoot root(String rootId, String nodeId, String moduleId, int x) {
        return new FloatingRoot(rootId, new DockRect(x, 0, 100, 80), new LeafNode(nodeId, moduleId, true));
    }
}
