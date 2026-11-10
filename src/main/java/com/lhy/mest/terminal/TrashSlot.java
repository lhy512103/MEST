package com.lhy.mest.terminal;

import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;

/** Server-side access gate, including AE2's generic Shift-click fallback. */
final class TrashSlot extends AppEngSlot {
    private final BooleanSupplier open;

    TrashSlot(InternalInventory inventory, int slot, BooleanSupplier open) {
        super(inventory, slot);
        this.open = open;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return open.getAsBoolean() && super.mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return open.getAsBoolean() && super.mayPickup(player);
    }

    @Override
    public boolean isActive() {
        return open.getAsBoolean() && super.isActive();
    }

    // Do not override isSlotEnabled: AppEngSlot.getItem/set must still synchronize hidden contents.
}
