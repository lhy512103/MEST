package com.lhy.mest.terminal.workbench;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.CellPartitionSlot;
import appeng.menu.slot.RestrictedInputSlot;

import com.lhy.mest.terminal.MESTMenu;

/** Menu slots of the cell workbench module. All are inert while the module is closed. */
public final class CellWorkbenchSlots {
    private CellWorkbenchSlots() {
    }

    /** Holds the edited cell. A cell that lives in a drive is shown here but cannot be taken or replaced. */
    public static final class Cell extends AppEngSlot {
        private final MESTMenu menu;

        public Cell(MESTMenu menu, InternalInventory inventory) {
            super(inventory, 0);
            this.menu = menu;
        }

        @Override
        public boolean isSlotEnabled() {
            return menu.workbenchOpen;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !menu.workbenchRemote && CellWorkbenchLogic.isEditableCell(stack) && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return !menu.workbenchRemote && super.mayPickup(player);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    public static final class Config extends CellPartitionSlot {
        public Config(InternalInventory inventory, CellWorkbenchLogic logic, int index) {
            super(inventory, logic, index);
        }

        // CellPartitionSlot wipes a disabled slot. Here "disabled" also means "module closed", and
        // wiping would push an empty partition into the cell.
        @Override
        public ItemStack getItem() {
            return isSlotEnabled() ? super.getItem() : ItemStack.EMPTY;
        }
    }

    public static final class Upgrade extends RestrictedInputSlot {
        private final CellWorkbenchLogic logic;
        private final int upgradeIndex;

        public Upgrade(CellWorkbenchLogic logic, InternalInventory inventory, int index) {
            super(PlacableItemType.UPGRADES, inventory, index);
            this.logic = logic;
            this.upgradeIndex = index;
        }

        @Override
        public boolean isSlotEnabled() {
            return logic.isUpgradeSlotEnabled(upgradeIndex);
        }
    }
}
