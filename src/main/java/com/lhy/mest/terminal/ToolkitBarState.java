package com.lhy.mest.terminal;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.InternalInventory;

import de.mari_023.ae2wtlib.api.AE2wtlibAPI;

import com.lhy.mest.compat.MestWtlibSupport;
import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.registry.ModItems;

/**
 * Visible 27-cell cycle: left toolkit 0-8, vanilla hotbar, right toolkit 9-17.
 *
 * <p>{@code Inventory.items[0..8]} never moves. Selecting an extra bar only changes which toolkit
 * cell {@code Inventory.getSelected} reports, so vanilla hotbar interaction stays intact.
 */
public final class ToolkitBarState {
    public enum Bar {
        LEFT,
        CENTER,
        RIGHT
    }

    public static final int BAR_SLOTS = 9;
    public static final int VISIBLE_CELLS = 27;
    public static final int VISIBLE_TOOLKIT_SLOTS = BAR_SLOTS * 2;

    static final Map<Player, Bar> PAGES = new WeakHashMap<>();
    private static final Map<Player, ToolkitInternalInventory> INVENTORIES = new WeakHashMap<>();

    private ToolkitBarState() {}

    public static Bar getBar(Player player) {
        if (!isBarEnabled(player)) {
            return Bar.CENTER;
        }
        return storedBar(player);
    }

    static Bar storedBar(Player player) {
        Bar fromItem = barFromTerminal(findTerminal(player));
        Bar cached = PAGES.get(player);
        if (cached == null) {
            return fromItem;
        }
        if (cached != fromItem) {
            if (fromItem == Bar.CENTER) {
                PAGES.remove(player);
            } else {
                PAGES.put(player, fromItem);
            }
            return fromItem;
        }
        return cached;
    }

    public static int getSlot(Player player) {
        return Math.floorMod(player.getInventory().selected, BAR_SLOTS);
    }

    public static boolean isBarEnabled(Player player) {
        return isBarEnabled(findTerminal(player));
    }

    public static boolean isBarEnabled(ItemStack terminal) {
        return !terminal.isEmpty() && terminal.getOrDefault(ModComponents.TOOLKIT_BAR.get(), false);
    }

    public static boolean isToolkitSelected(Player player) {
        return isBarEnabled(player) && getBar(player) != Bar.CENTER;
    }

    public static int visibleIndex(Player player) {
        return toIndex(getBar(player), getSlot(player));
    }

    public static int toIndex(Bar bar, int slot) {
        return bar.ordinal() * BAR_SLOTS + Math.floorMod(slot, BAR_SLOTS);
    }

    public static Bar barOf(int index) {
        int normalized = Math.floorMod(index, VISIBLE_CELLS);
        return Bar.values()[normalized / BAR_SLOTS];
    }

    public static int slotOf(int index) {
        return Math.floorMod(index, BAR_SLOTS);
    }

    public static int cycleIndex(int current, int delta) {
        return Math.floorMod(current - delta, VISIBLE_CELLS);
    }

    public static int toolkitIndex(Player player) {
        return toolkitIndex(getBar(player), getSlot(player));
    }

    public static int toolkitIndex(Bar bar, int slot) {
        if (bar == Bar.CENTER) {
            return -1;
        }
        int normalized = Math.floorMod(slot, BAR_SLOTS);
        return bar == Bar.LEFT ? normalized : BAR_SLOTS + normalized;
    }

    public static void setSelection(Player player, Bar bar, int slot) {
        player.getInventory().selected = Math.floorMod(slot, BAR_SLOTS);
        ItemStack terminal = findTerminal(player);
        Bar target = bar == Bar.CENTER || !isBarEnabled(terminal) ? Bar.CENTER : bar;
        if (target == Bar.CENTER) {
            PAGES.remove(player);
        } else {
            PAGES.put(player, target);
        }
        if (!terminal.isEmpty()) {
            terminal.set(ModComponents.TOOLKIT_BAR_PAGE.get(), target.ordinal());
        }
    }

    public static void clear(Player player) {
        PAGES.remove(player);
        INVENTORIES.remove(player);
    }

    public static boolean mayStore(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getMaxStackSize() <= 1
                && !isTerminalCarrier(stack);
    }

    public static InternalInventory asInventory(Player player) {
        return INVENTORIES.computeIfAbsent(player, ToolkitInternalInventory::new);
    }

    public static ItemStack stackAt(Player player, int index) {
        return inventory(player).liveStack(index);
    }

    public static ItemStack memoryAt(Player player, int index) {
        ItemStack terminal = findTerminal(player);
        if (terminal.isEmpty() || index < 0) {
            return ItemStack.EMPTY;
        }
        ItemContainerContents memory = terminal.getOrDefault(
                ModComponents.TOOLKIT_MEMORY.get(), ItemContainerContents.EMPTY);
        return index < memory.getSlots() ? memory.getStackInSlot(index) : ItemStack.EMPTY;
    }

    /** Live toolkit cell for the current extra-bar selection, or empty. */
    public static ItemStack selectedStack(Player player) {
        int index = toolkitIndex(player);
        return isValidToolkitIndex(index) ? stackAt(player, index) : ItemStack.EMPTY;
    }

    /**
     * Selects the extra-bar or vanilla hotbar cell that already holds {@code stack}.
     *
     * @return {@code TOOLKIT} if an extra-bar cell matched, {@code HOTBAR} if a vanilla hotbar cell
     *     matched, otherwise {@code NONE}
     */
    public static PickMatch selectMatchingItem(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return PickMatch.NONE;
        }
        if (isToolkitSelected(player)
                && ItemStack.isSameItemSameComponents(selectedStack(player), stack)) {
            return PickMatch.TOOLKIT;
        }
        for (int index = 0; index < VISIBLE_TOOLKIT_SLOTS; index++) {
            if (ItemStack.isSameItemSameComponents(stackAt(player, index), stack)) {
                setSelection(player, index < BAR_SLOTS ? Bar.LEFT : Bar.RIGHT, index % BAR_SLOTS);
                return PickMatch.TOOLKIT;
            }
        }
        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(stack);
        if (slot != -1) {
            setSelection(player, Bar.CENTER, Inventory.isHotbarSlot(slot) ? slot : inventory.selected);
            return PickMatch.HOTBAR;
        }
        return PickMatch.NONE;
    }

    public enum PickMatch {
        NONE,
        TOOLKIT,
        HOTBAR
    }

    public static void setSelectedStack(Player player, ItemStack stack) {
        int index = toolkitIndex(player);
        if (!isValidToolkitIndex(index)) {
            return;
        }
        if (!stack.isEmpty() && !mayStore(stack)) {
            // Vanilla just wrote a stack the bar cannot hold, for example the armor it displaced
            // when equipping something else. Empty the cell first so the old item cannot linger as
            // a second copy next to the equipped one, then hand the stack back to the player
            // instead of dropping it on the floor of this method.
            inventory(player).setItemDirect(index, ItemStack.EMPTY);
            ItemStack remainder = stack.copy();
            if (!player.getInventory().add(remainder)) {
                player.drop(remainder, false);
            }
            return;
        }
        inventory(player).setItemDirect(index, stack);
    }

    /**
     * Puts a picked-up unstackable item into the first empty remembered slot of the same type.
     * Shrinks {@code stack} in place when accepted.
     *
     * @return {@code true} if the stack was fully moved into a memory slot
     */
    public static boolean tryInsertIntoMemory(Player player, ItemStack stack) {
        if (!mayStore(stack)) {
            return false;
        }
        ItemStack terminal = findTerminal(player);
        if (terminal.isEmpty()) {
            return false;
        }
        ItemContainerContents memory = terminal.getOrDefault(
                ModComponents.TOOLKIT_MEMORY.get(), ItemContainerContents.EMPTY);
        ToolkitInternalInventory toolkit = inventory(player);
        int limit = Math.min(toolkit.size(), memory.getSlots());
        for (int i = 0; i < limit; i++) {
            ItemStack remembered = memory.getStackInSlot(i);
            if (remembered.isEmpty() || !remembered.is(stack.getItem()) || !toolkit.getStackInSlot(i).isEmpty()) {
                continue;
            }
            toolkit.setItemDirect(i, stack.copy());
            stack.setCount(0);
            return true;
        }
        return false;
    }

    public static void persistIfDirty(Player player) {
        inventory(player).persistIfDirty();
    }

    public static void applyClientHotbar(Player player, List<ItemStack> stacks) {
        inventory(player).adoptHotbar(stacks);
    }

    public static boolean isValidToolkitIndex(int index) {
        return index >= 0 && index < VISIBLE_TOOLKIT_SLOTS;
    }

    public static ItemStack findTerminal(Player player) {
        Inventory inventory = player.getInventory();
        ItemStack first = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isTerminalCarrier(stack)) {
                continue;
            }
            if (isBarEnabled(stack)) {
                return stack;
            }
            if (first.isEmpty()) {
                first = stack;
            }
        }
        ItemStack located = MestWtlibSupport.mestStack(player);
        if (isBarEnabled(located)) {
            return located;
        }
        return first;
    }

    public static boolean isTerminalCarrier(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ModItems.SPLICED_TERMINAL.get()) || AE2wtlibAPI.isUniversalTerminal(stack));
    }

    static Bar barFromTerminal(ItemStack terminal) {
        if (terminal.isEmpty()) {
            return Bar.CENTER;
        }
        int page = terminal.getOrDefault(ModComponents.TOOLKIT_BAR_PAGE.get(), Bar.CENTER.ordinal());
        if (page < 0 || page >= Bar.values().length) {
            return Bar.CENTER;
        }
        return Bar.values()[page];
    }

    static ToolkitInternalInventory inventory(Player player) {
        return (ToolkitInternalInventory) asInventory(player);
    }
}
