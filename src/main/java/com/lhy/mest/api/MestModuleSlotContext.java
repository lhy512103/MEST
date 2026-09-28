package com.lhy.mest.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.lhy.mest.terminal.MESTMenu;

/** What a slot provider sees while the terminal menu is being built, on both sides. */
public interface MestModuleSlotContext {
    Player player();

    /** The terminal stack the menu was opened from; data components on it persist with the terminal. */
    ItemStack terminal();

    MESTMenu menu();

    boolean isClientSide();

    /** Adds a slot owned by this module. Its client panel reads them back from {@code slots()}. */
    Slot addSlot(Slot slot);
}
