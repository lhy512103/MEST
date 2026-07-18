package com.lhy.mest.client.dock.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lhy.mest.client.dock.model.DockWorkspaceEditor;

class AtomicFileDockLayoutStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyCreatesAndOverwritesInjectedPath() throws Exception {
        Path path = temporaryDirectory.resolve("nested").resolve("layout.json");
        var catalog = WorkspacePersistenceFixtures.catalog();
        var codec = new DockLayoutCodec(catalog, WorkspacePersistenceFixtures.migrationContext());
        var store = new AtomicFileDockLayoutStore(path, codec);
        var first = WorkspacePersistenceFixtures.workspace();

        assertTrue(store.load().isEmpty());
        store.save(first);
        assertTrue(Files.isRegularFile(path));
        assertEquals(first, store.load().orElseThrow().workspace());

        var raised = new DockWorkspaceEditor(catalog).raiseRoot(first, "root-a");
        store.save(raised);
        assertEquals(raised, store.load().orElseThrow().workspace());
        try (var children = Files.list(path.getParent())) {
            assertFalse(children.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }
}
