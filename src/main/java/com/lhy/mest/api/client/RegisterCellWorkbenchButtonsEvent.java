package com.lhy.mest.api.client;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import com.lhy.mest.module.OrderedModuleRegistry;

/**
 * Client mod-bus event, fired during client setup: add a button to the MEST cell workbench side bar.
 * Buttons appear below AE2's own, in id order. Pair it with a server action registered through
 * {@code com.lhy.mest.api.RegisterCellWorkbenchActionsEvent}.
 */
public final class RegisterCellWorkbenchButtonsEvent extends Event implements IModBusEvent {
    private final OrderedModuleRegistry<CellWorkbenchButtonFactory> registry;

    public RegisterCellWorkbenchButtonsEvent(OrderedModuleRegistry<CellWorkbenchButtonFactory> registry) {
        this.registry = registry;
    }

    public void register(String buttonId, CellWorkbenchButtonFactory factory) {
        registry.register(buttonId, factory);
    }
}
