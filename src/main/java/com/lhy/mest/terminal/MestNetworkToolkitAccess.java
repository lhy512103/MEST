package com.lhy.mest.terminal;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.InternalInventory;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.slot.AppEngSlot;

import com.lhy.mest.compat.MestWtlibSupport;
import com.lhy.mest.config.MestConfig;
import com.lhy.mest.inventory.PersistentItemInventory;
import com.lhy.mest.item.ItemMEST;
import com.lhy.mest.registry.ModComponents;

/** Live network-toolkit inventory stored on the spliced terminal the player is carrying. */
public final class MestNetworkToolkitAccess {
    private MestNetworkToolkitAccess() {
    }

    /**
     * Marks the terminal's own toolbox inventory. Screens use this to tell the terminal's slots
     * apart from a carried vanilla network tool's, which keeps AE2's own panel.
     */
    public static final class TerminalToolboxInventory extends PersistentItemInventory {
        private TerminalToolboxInventory(Player player, ItemStack stack) {
            super(MestConfig.networkToolkitSlots(),
                    stack.getOrDefault(ModComponents.NETWORK_TOOLKIT_INV.get(), ItemContainerContents.EMPTY)
                            .stream().toList(),
                    () -> player.level().isClientSide(),
                    contents -> stack.set(ModComponents.NETWORK_TOOLKIT_INV.get(), ItemContainerContents.fromItems(contents)));
        }
    }

    /** True when this toolbox slot belongs to the spliced terminal rather than a vanilla tool. */
    public static boolean isTerminalToolbox(Slot slot) {
        return slot instanceof AppEngSlot appEngSlot
                && appEngSlot.getInventory() instanceof TerminalToolboxInventory;
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
        TerminalToolboxInventory inventory = new TerminalToolboxInventory(player, stack);
        inventory.setEnableClientEvents(true);
        return inventory;
    }

    /**
     * Network-tool host wrapping {@link #inventoryOf} so memory-card restore and
     * {@code findNetworkToolInv} can use the terminal toolkit when no vanilla tool is carried.
     */
    @Nullable
    public static NetworkToolMenuHost<?> menuHostOf(Player player) {
        return MestTerminalNetworkToolHost.create(player);
    }

}
