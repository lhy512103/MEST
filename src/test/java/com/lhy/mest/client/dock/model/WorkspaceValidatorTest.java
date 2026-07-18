package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class WorkspaceValidatorTest {
    @Test
    void rejectsDuplicateNodeIdentifiers() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b");
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.HORIZONTAL,
                0.5,
                new LeafNode("leaf-duplicate", "a", true),
                new LeafNode("leaf-duplicate", "b", true));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 100, 80), split)));

        assertThrows(
                WorkspaceValidationException.class,
                () -> WorkspaceValidator.validateStrict(workspace, catalog));
    }

    @Test
    void rejectsDuplicateAndMissingModules() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b");
        LayoutNode split = new SplitNode(
                "split-main",
                DockAxis.HORIZONTAL,
                0.5,
                new LeafNode("leaf-a1", "a", true),
                new LeafNode("leaf-a2", "a", true));
        DockWorkspace workspace = new DockWorkspace(List.of(
                new FloatingRoot("root-main", new DockRect(0, 0, 100, 80), split)));

        assertThrows(
                WorkspaceValidationException.class,
                () -> WorkspaceValidator.validateStrict(workspace, catalog));
    }

    @Test
    void rejectsWorkspaceThatOmitsRegisteredModule() {
        ModuleCatalog catalog = ModelTestFixtures.catalog("a", "b");
        DockWorkspace workspace = new DockWorkspace(List.of(
                ModelTestFixtures.root("root-a", "leaf-a", "a", 0)));

        assertThrows(
                WorkspaceValidationException.class,
                () -> WorkspaceValidator.validateStrict(workspace, catalog));
    }
}
