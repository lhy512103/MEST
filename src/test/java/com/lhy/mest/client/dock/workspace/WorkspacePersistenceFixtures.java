package com.lhy.mest.client.dock.workspace;

import java.util.LinkedHashMap;
import java.util.List;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockInsets;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleMetrics;
import com.lhy.mest.client.dock.model.SplitNode;

final class WorkspacePersistenceFixtures {
    private WorkspacePersistenceFixtures() {
    }

    static ModuleCatalog catalog() {
        var entries = new LinkedHashMap<String, ModuleMetrics>();
        entries.put("a", new ModuleMetrics(new DockSize(20, 20), new DockSize(80, 60)));
        entries.put("b", new ModuleMetrics(new DockSize(30, 20), new DockSize(90, 60)));
        entries.put("c", new ModuleMetrics(new DockSize(20, 30), new DockSize(80, 70)));
        return new ModuleCatalog(entries);
    }

    static ModuleCatalog catalog(String... moduleIds) {
        var entries = new LinkedHashMap<String, ModuleMetrics>();
        for (String moduleId : moduleIds) {
            entries.put(moduleId, new ModuleMetrics(
                    new DockSize(20, 20),
                    new DockSize(80, 60)));
        }
        return new ModuleCatalog(entries);
    }

    static LegacyMigrationContext migrationContext() {
        return new LegacyMigrationContext(DockInsets.NONE, 4, 8, 8, 16, 800, 600);
    }

    static DockWorkspace workspace() {
        return new DockWorkspace(List.of(
                new FloatingRoot(
                        "root-a",
                        new DockRect(10, 20, 100, 80),
                        new LeafNode("leaf-a", "a", false)),
                new FloatingRoot(
                        "root-bc",
                        new DockRect(160, 40, 220, 140),
                        new SplitNode(
                                "split-bc",
                                DockAxis.VERTICAL,
                                0.35,
                                new LeafNode("leaf-b", "b", true),
                                new LeafNode("leaf-c", "c", true)))));
    }
}
