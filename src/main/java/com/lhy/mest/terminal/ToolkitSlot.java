package com.lhy.mest.terminal;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;

/**
 * A toolkit slot: an extension of the player inventory that only accepts items which cannot stack
 * (tools, gear, single-instance curios...). Vanilla click / drag / shift-click handling is untouched —
 * every placement path in {@code AbstractContainerMenu} funnels through {@link #mayPlace(ItemStack)},
 * so rejecting stackables here is enough to cover mouse, keyboard and hotbar transfers alike.
 */
public class ToolkitSlot extends AppEngSlot {
    public ToolkitSlot(InternalInventory inventory, int index) {
        super(inventory, index);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        // getMaxStackSize() is a property of the item, not of the current count, so a lone stackable
        // item is still rejected here — exactly the intent of "unstackable only".
        return ToolkitBarState.mayStore(stack);
    }
}
