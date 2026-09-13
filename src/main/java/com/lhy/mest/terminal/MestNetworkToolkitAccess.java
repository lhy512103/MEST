package com.lhy.mest.terminal;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.InternalInventory;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import com.lhy.mest.compat.MestWtlibSupport;
import com.lhy.mest.config.MestConfig;
import com.lhy.mest.item.ItemMEST;
import com.lhy.mest.registry.ModComponents;

/** Live network-toolkit inventory stored on the spliced terminal the player is carrying. */
public final class MestNetworkToolkitAccess {
    private MestNetworkToolkitAccess() {
    }

    /**
     * The spliced terminal in the player's inventory (Curios first through the wtlib locator, then a
     * plain inventory scan). Returns {@code null} when the player carries no terminal.
     */
    @Nullable
    public static InternalInventory inventoryOf(Player player) {
        ItemMenuHostLocator locator = MestWtlibSupport.findMest(player);
        ItemStack stack = locator != null ? locator.locateItem(player) : ItemStack.EMPTY;
        if (!(stack.getItem() instanceof ItemMEST)) {
            locator = null;
            stack = ItemStack.EMPTY;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack candidate = player.getInventory().getItem(slot);
                if (candidate.getItem() instanceof ItemMEST) {
                    locator = MenuLocators.forInventorySlot(slot);
                    stack = candidate;
                    break;
                }
            }
        }
        if (locator == null || stack.isEmpty()) {
            return null;
        }
        AppEngInternalInventory inventory = new AppEngInternalInventory(
                new TerminalHost(player, locator), MestConfig.networkToolkitSlots());
        inventory.setEnableClientEvents(true);
        inventory.fromItemContainerContents(
                stack.getOrDefault(ModComponents.NETWORK_TOOLKIT_INV.get(), ItemContainerContents.EMPTY));
        return inventory;
    }

    private record TerminalHost(Player player, ItemMenuHostLocator locator) implements InternalInventoryHost {
        @Override
        public void saveChangedInventory(AppEngInternalInventory inv) {
            ItemStack stack = locator.locateItem(player);
            if (!stack.isEmpty()) {
                stack.set(ModComponents.NETWORK_TOOLKIT_INV.get(), inv.toItemContainerContents());
            }
        }

        @Override
        public boolean isClientSide() {
            return player.level().isClientSide();
        }
    }
}
