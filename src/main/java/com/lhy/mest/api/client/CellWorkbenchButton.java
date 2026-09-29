package com.lhy.mest.api.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.item.ItemStack;

/** One add-on button on the MEST cell workbench side bar. */
public interface CellWorkbenchButton {
    /** The widget; MEST positions, renders and clicks it. An {@code ITooltip} widget gets its tooltip shown. */
    AbstractWidget widget();

    /** Called every frame with a copy of the edited cell, before layout: update visibility and state here. */
    default void update(ItemStack cell) {
    }
}
