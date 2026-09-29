package com.lhy.mest.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server side of a button an add-on puts on the MEST cell workbench side bar. */
@FunctionalInterface
public interface CellWorkbenchAction {
    /**
     * Edits {@code cell}, a private copy of the edited cell, in place. MEST writes the copy back to
     * wherever the cell lives (the workbench slot or an ME drive). {@code alternate} is true for a
     * right click.
     */
    void apply(ServerPlayer player, ItemStack cell, boolean alternate);
}
