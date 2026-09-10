package com.lhy.mest.terminal;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.lhy.mest.config.MestConfig;
import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.registry.ModItems;

/**
 * Server-side toolkit quick-bar actions.
 *
 * <p>The toolkit inventory lives in the terminal item's {@code TOOLKIT_INV} component, so the bars
 * work without an open terminal: read the component, swap, write it back. Because the terminal sits
 * in the player's own inventory, the component update is replicated by the normal slot sync — no
 * bespoke S2C packet, no per-tick work.
 */
public final class ToolkitBarActions {
    private ToolkitBarActions() {}

    /**
     * Swaps the player's main-hand item with toolkit slot {@code index}.
     *
     * <p>Rejected when the hand holds a stackable item: the toolkit only stores unstackable items,
     * so allowing that swap would let a stack sneak past {@link ToolkitSlot#mayPlace(ItemStack)}.
     */
    public static void swapWithHand(ServerPlayer player, int index) {
        ItemStack terminal = findTerminal(player);
        if (terminal.isEmpty()) {
            return;
        }
        int size = MestConfig.toolkitSlots();
        if (index < 0 || index >= size) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && (held.getMaxStackSize() > 1 || held.is(ModItems.SPLICED_TERMINAL.get()))) {
            return;
        }
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        ItemContainerContents contents = terminal.get(ModComponents.TOOLKIT_INV.get());
        if (contents != null) {
            contents.copyInto(items);
        }
        ItemStack stored = items.get(index);
        items.set(index, held.copy());
        terminal.set(ModComponents.TOOLKIT_INV.get(), ItemContainerContents.fromItems(items));
        player.setItemInHand(InteractionHand.MAIN_HAND, stored);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    /** First spliced terminal in the player's inventory, or empty when none is carried. */
    public static ItemStack findTerminal(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(ModItems.SPLICED_TERMINAL.get())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
