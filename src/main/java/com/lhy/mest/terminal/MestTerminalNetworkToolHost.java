package com.lhy.mest.terminal;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.menu.locator.MenuLocators;

import com.lhy.mest.item.ItemMestNetworkToolkit;
import com.lhy.mest.registry.ModItems;

/**
 * Presents the spliced terminal's built-in toolkit inventory as a network-tool host so AE2
 * callers of {@code NetworkToolItem.findNetworkToolInv} (memory cards, machine toolboxes)
 * can pull and return upgrade cards without a carried {@code NetworkToolItem}.
 */
final class MestTerminalNetworkToolHost extends NetworkToolMenuHost<ItemMestNetworkToolkit> {
    private final InternalInventory inventory;

    private MestTerminalNetworkToolHost(Player player, InternalInventory inventory) {
        super(ModItems.NETWORK_TOOLKIT.get(), player, MenuLocators.forStack(new ItemStack(ModItems.NETWORK_TOOLKIT.get())),
                null);
        this.inventory = inventory;
    }

    static MestTerminalNetworkToolHost create(Player player) {
        InternalInventory inventory = MestNetworkToolkitAccess.inventoryOf(player);
        return inventory == null ? null : new MestTerminalNetworkToolHost(player, inventory);
    }

    @Override
    public InternalInventory getInventory() {
        return inventory;
    }

    @Override
    public boolean isValid() {
        return MestNetworkToolkitAccess.inventoryOf(getPlayer()) != null;
    }
}
