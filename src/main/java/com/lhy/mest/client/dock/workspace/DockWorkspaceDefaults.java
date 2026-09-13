package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.model.NodeIds;

/** Deterministic default roots for registered modules. */
public final class DockWorkspaceDefaults {
    private static final String BUNDLED_LAYOUT = "/assets/mesplicedterminal/layouts/default.json";
    public static final String NETWORK_TOOLKIT_MODULE = "network_toolkit";
    /** The network-tool panel sits 4px further left than the generic cascade placement. */
    public static final int NETWORK_TOOLKIT_SHIFT_X = 4;

    private DockWorkspaceDefaults() {
    }

    /** Placement tweak for {@link #NETWORK_TOOLKIT_MODULE}; every other module is unchanged. */
    public static DockRect defaultBounds(String moduleId, DockRect bounds) {
        return NETWORK_TOOLKIT_MODULE.equals(moduleId) ? networkToolkitBounds(bounds) : bounds;
    }

    public static DockRect networkToolkitBounds(DockRect bounds) {
        return new DockRect(
                Math.max(0, bounds.x() - NETWORK_TOOLKIT_SHIFT_X),
                bounds.y(),
                bounds.width(),
                bounds.height());
    }

    public static DockWorkspace create(ModuleCatalog catalog, LegacyMigrationContext geometry) {
        DockWorkspace bundled = loadBundled(catalog, geometry);
        if (bundled != null) {
            return bundled;
        }
        var roots = new ArrayList<FloatingRoot>();
        int index = 0;
        for (String moduleId : catalog.moduleIds()) {
            var leaf = new LeafNode(leafNodeId(moduleId), moduleId, defaultVisible(moduleId));
            roots.add(new FloatingRoot(
                    rootId(moduleId),
                    defaultBounds(moduleId, geometry.defaultRootBounds(catalog.metrics(moduleId), index++)),
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

    private static DockWorkspace loadBundled(ModuleCatalog catalog, LegacyMigrationContext geometry) {
        try (InputStream in = DockWorkspaceDefaults.class.getResourceAsStream(BUNDLED_LAYOUT)) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new DockLayoutCodec(catalog, geometry).decode(json).workspace();
        } catch (IOException | RuntimeException e) {
            MESplicedterminal.LOGGER.warn("Failed to load bundled default layout", e);
            return null;
        }
    }

    public static boolean defaultVisible(String moduleId) {
        return !"provider_select".equals(moduleId)
                && !"wireless_settings".equals(moduleId)
                && !"trash".equals(moduleId)
                && !"toolkit".equals(moduleId);
    }

    public static boolean defaultFloating(String moduleId) {
        return "wireless_settings".equals(moduleId)
                || "trash".equals(moduleId)
                || "toolkit".equals(moduleId)
                || "network_toolkit".equals(moduleId);
    }

    public static boolean defaultPinned(String moduleId) {
        return "trash".equals(moduleId) || "network_toolkit".equals(moduleId);
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
