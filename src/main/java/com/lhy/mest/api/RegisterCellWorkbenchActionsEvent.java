package com.lhy.mest.api;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import com.lhy.mest.module.OrderedModuleRegistry;

/**
 * Mod-bus event, fired on both sides during common setup: register the server action behind a cell
 * workbench side bar button. Register the button itself with
 * {@code com.lhy.mest.api.client.RegisterCellWorkbenchButtonsEvent} under the same id.
 */
public final class RegisterCellWorkbenchActionsEvent extends Event implements IModBusEvent {
    private final OrderedModuleRegistry<CellWorkbenchAction> registry;

    public RegisterCellWorkbenchActionsEvent(OrderedModuleRegistry<CellWorkbenchAction> registry) {
        this.registry = registry;
    }

    public void register(String actionId, CellWorkbenchAction action) {
        registry.register(actionId, action);
    }
}
