package com.lhy.mest.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.upgrades.Upgrades;
import appeng.menu.locator.ItemMenuHostLocator;

import de.mari_023.ae2wtlib.api.terminal.ItemWT;

import com.lhy.mest.registry.ModMenus;

/**
 * The ME Spliced Terminal item. It plugs into the AE2WTLib terminal framework by extending
 * {@link ItemWT}, which gives us wireless grid access, power handling, quantum-bridge support,
 * hotkey locating and universal-terminal merging for free.
 */
public class ItemMEST extends ItemWT {
    private static final int UPGRADE_INVENTORY_SIZE = 3;

    @Override
    public MenuType<?> getMenuType(ItemMenuHostLocator locator, Player player) {
        return ModMenus.SPLICED_TERMINAL_MENU.get();
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, UPGRADE_INVENTORY_SIZE, this::onUpgradesChanged);
    }

    private void onUpgradesChanged(ItemStack stack, IUpgradeInventory upgrades) {
        setAEMaxPowerMultiplier(stack, 1 + Upgrades.getEnergyCardMultiplier(upgrades));
    }
}
