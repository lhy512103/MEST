package com.lhy.mest.client;

import java.util.List;
import java.util.Set;

import net.neoforged.fml.ModLoader;

import com.lhy.mest.api.client.CellWorkbenchButtonFactory;
import com.lhy.mest.api.client.RegisterCellWorkbenchButtonsEvent;
import com.lhy.mest.module.OrderedModuleRegistry;

/** Add-on cell workbench side bar buttons, in id order. */
public final class CellWorkbenchButtons {
    private static final OrderedModuleRegistry<CellWorkbenchButtonFactory> REGISTRY =
            new OrderedModuleRegistry<>(Set.of());
    private static List<CellWorkbenchButtonFactory> factories = List.of();

    private CellWorkbenchButtons() {
    }

    /** Client setup, on the main thread. */
    static void collect() {
        if (REGISTRY.isFrozen()) {
            return;
        }
        ModLoader.postEvent(new RegisterCellWorkbenchButtonsEvent(REGISTRY));
        REGISTRY.freeze();
        factories = REGISTRY.entries().stream().map(java.util.Map.Entry::getValue).toList();
    }

    public static List<CellWorkbenchButtonFactory> factories() {
        return factories;
    }
}
