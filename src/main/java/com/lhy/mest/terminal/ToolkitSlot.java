package com.lhy.mest.terminal;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;

/**
 * A toolkit slot: an extension of the player inventory that only accepts items which cannot stack
 * (tools, gear, single-instance curios...). Remembered slots additionally only accept that item type.
 */
public class ToolkitSlot extends AppEngSlot {
    private final int toolkitIndex;
    private final MESTMenu menu;

    public ToolkitSlot(InternalInventory inventory, int index, MESTMenu menu) {
        super(inventory, index);
        this.toolkitIndex = index;
        this.menu = menu;
    }

    public int toolkitIndex() {
        return toolkitIndex;
    }

    @Override
    public boolean isSlotEnabled() {
        return menu.toolkitOpen && super.isSlotEnabled();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (!menu.toolkitOpen || !ToolkitBarState.mayStore(stack)) {
            return false;
        }
        ItemStack memory = menu.getToolkitMemoryStack(toolkitIndex);
        return memory.isEmpty() || memory.is(stack.getItem());
    }
}
