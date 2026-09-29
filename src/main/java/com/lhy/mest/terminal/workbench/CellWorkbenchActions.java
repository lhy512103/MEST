package com.lhy.mest.terminal.workbench;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import net.neoforged.fml.ModLoader;

import com.lhy.mest.api.CellWorkbenchAction;
import com.lhy.mest.api.RegisterCellWorkbenchActionsEvent;
import com.lhy.mest.module.OrderedModuleRegistry;

/** Server actions behind add-on cell workbench buttons, keyed by the id the client sends. */
public final class CellWorkbenchActions {
    private static final OrderedModuleRegistry<CellWorkbenchAction> REGISTRY = new OrderedModuleRegistry<>(Set.of());
    private static Map<String, CellWorkbenchAction> actions = Map.of();

    private CellWorkbenchActions() {
    }

    /** Common setup, on the main thread. */
    public static void collect() {
        if (REGISTRY.isFrozen()) {
            return;
        }
        ModLoader.postEvent(new RegisterCellWorkbenchActionsEvent(REGISTRY));
        REGISTRY.freeze();
        var built = new TreeMap<String, CellWorkbenchAction>();
        for (var entry : REGISTRY.entries()) {
            built.put(entry.getKey(), entry.getValue());
        }
        actions = Map.copyOf(built);
    }

    @Nullable
    public static CellWorkbenchAction get(String id) {
        return actions.get(id);
    }
}
