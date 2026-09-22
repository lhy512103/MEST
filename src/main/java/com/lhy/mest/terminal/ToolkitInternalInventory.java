package com.lhy.mest.terminal;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.BaseInternalInventory;

import com.lhy.mest.config.MestConfig;
import com.lhy.mest.network.ToolkitBarSyncPacket;
import com.lhy.mest.registry.ModComponents;

/**
 * Live toolkit stacks for one player. HUD, the extra-bar hand override and the terminal panel all
 * read these same references so in-place tool NBT changes stay visible.
 */
public final class ToolkitInternalInventory extends BaseInternalInventory {
    private final Player player;
    private final Supplier<ItemStack> terminalSupplier;
    private ItemStack boundTerminal = ItemStack.EMPTY;
    private ItemContainerContents boundContents = ItemContainerContents.EMPTY;
    private NonNullList<ItemStack> items = NonNullList.withSize(MestConfig.toolkitSlots(), ItemStack.EMPTY);
    /**
     * Set when the toolkit itself wrote a cell (the hand paths) and the component snapshot has not
     * caught up yet. While set, {@link #syncFromTerminal()} must not restore the cell from that
     * stale snapshot: vanilla empties the hand stack in place during an armor/tool swap, and
     * resurrecting the pre-swap stack would leave a second copy of the equipped item in the bar.
     */
    private boolean localEdit;

    ToolkitInternalInventory(Player player) {
        this(player, () -> ToolkitBarState.findTerminal(player));
    }

    public ToolkitInternalInventory(Player player, Supplier<ItemStack> terminalSupplier) {
        this.player = player;
        this.terminalSupplier = terminalSupplier;
    }

    void syncFromTerminal() {
        ItemStack terminal = terminalSupplier.get();
        ItemContainerContents contents = terminal.isEmpty()
                ? ItemContainerContents.EMPTY
                : terminal.getOrDefault(ModComponents.TOOLKIT_INV.get(), ItemContainerContents.EMPTY);
        int size = MestConfig.toolkitSlots();
        boolean resized = items.size() != size;
        if (resized) {
            NonNullList<ItemStack> resizedItems = NonNullList.withSize(size, ItemStack.EMPTY);
            int copied = Math.min(size, contents.getSlots());
            for (int i = 0; i < copied; i++) {
                resizedItems.set(i, adopt(ItemStack.EMPTY, contents.getStackInSlot(i)));
            }
            if (!player.level().isClientSide() && contents.getSlots() > size) {
                for (int i = size; i < contents.getSlots(); i++) {
                    returnOverflow(contents.getStackInSlot(i));
                }
            }
            items = resizedItems;
            boundTerminal = terminal;
            boundContents = contents;
            if (!terminal.isEmpty() && !player.level().isClientSide()) {
                save();
            }
        }
        if (!resized && (terminal != boundTerminal || contents != boundContents)) {
            boolean matchesItems = sameItems(contents);
            if (ToolkitSyncPolicy.shouldAdoptFromComponent(localEdit, matchesItems)) {
                adoptContents(contents);
            } else if (ToolkitSyncPolicy.isLocalEditSettled(localEdit, matchesItems)) {
                localEdit = false;
            }
            boundTerminal = terminal;
            boundContents = contents;
        }
    }

    private void returnOverflow(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remainder = stack.copy();
        if (!player.getInventory().add(remainder)) {
            player.drop(remainder, false);
        }
    }

    void save() {
        if (player.level().isClientSide()) {
            return;
        }
        ItemStack terminal = terminalSupplier.get();
        if (terminal.isEmpty()) {
            return;
        }
        ItemContainerContents next = ItemContainerContents.fromItems(items);
        terminal.set(ModComponents.TOOLKIT_INV.get(), next);
        boundTerminal = terminal;
        boundContents = next;
        localEdit = false;
        player.getInventory().setChanged();
        if (player instanceof ServerPlayer serverPlayer) {
            ToolkitBarSyncPacket.send(serverPlayer);
        }
    }

    ItemStack liveStack(int index) {
        syncFromTerminal();
        if (index < 0 || index >= items.size()) {
            return ItemStack.EMPTY;
        }
        return items.get(index);
    }

    boolean persistIfDirty() {
        if (player.level().isClientSide()) {
            return false;
        }
        ItemStack terminal = terminalSupplier.get();
        if (terminal.isEmpty()) {
            return false;
        }
        ItemContainerContents contents = terminal.getOrDefault(
                ModComponents.TOOLKIT_INV.get(), ItemContainerContents.EMPTY);
        if (contents.getSlots() != items.size() || !sameItems(contents)) {
            save();
            return true;
        }
        return false;
    }

    void adoptHotbar(List<ItemStack> stacks) {
        syncFromTerminal();
        int limit = Math.min(ToolkitBarState.VISIBLE_TOOLKIT_SLOTS, items.size());
        for (int i = 0; i < limit; i++) {
            ItemStack incoming = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            items.set(i, adopt(items.get(i), incoming));
        }
        // A server push is authoritative for the visible bars, so it also settles pending edits.
        localEdit = false;
    }

    private void adoptContents(ItemContainerContents contents) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack incoming = i < contents.getSlots() ? contents.getStackInSlot(i) : ItemStack.EMPTY;
            items.set(i, adopt(items.get(i), incoming));
        }
    }

    /**
     * Keep the live ItemStack identity when the item type is unchanged so {@code startUsingItem}
     * / energy tools keep seeing the same instance. WCWT replaced the snapshot on every sync and
     * cancelled bows, drills and similar held-use items.
     */
    private static ItemStack adopt(ItemStack current, ItemStack incoming) {
        if (incoming.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (current.isEmpty() || current.getItem() != incoming.getItem()) {
            return incoming.copy();
        }
        if (ItemStack.matches(current, incoming)) {
            return current;
        }
        current.setCount(incoming.getCount());
        current.applyComponents(incoming.getComponentsPatch());
        return current;
    }

    private boolean sameItems(ItemContainerContents contents) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack expected = i < contents.getSlots() ? contents.getStackInSlot(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(items.get(i), expected)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int size() {
        syncFromTerminal();
        return items.size();
    }

    @Override
    public ItemStack getStackInSlot(int slotIndex) {
        return liveStack(slotIndex);
    }

    @Override
    public void setItemDirect(int slotIndex, ItemStack stack) {
        syncFromTerminal();
        if (slotIndex < 0 || slotIndex >= items.size()) {
            return;
        }
        if (!stack.isEmpty() && !isItemValid(slotIndex, stack)) {
            return;
        }
        // A returned armor piece must not be the same object still sitting in the equipment slot.
        // In-place edits (count, components) would then change both, which is how an equip swap
        // left quantum leggings in the hand and on the body.
        items.set(slotIndex, detachIfShared(slotIndex, stack));
        // This write is newer than the component snapshot we are bound to. Mark it before saving
        // so nothing can restore the previous stack while the component catches up.
        localEdit = true;
        save();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return stack.isEmpty() || ToolkitBarState.mayStore(stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    /**
     * Copy only when {@code stack} is already stored in another slot. Held tools must keep their
     * identity so charging and drawing are not cancelled; an armor return must not alias the piece
     * still equipped.
     */
    private ItemStack detachIfShared(int slotIndex, ItemStack stack) {
        if (stack.isEmpty() || !sharesReference(slotIndex, stack)) {
            return stack;
        }
        return stack.copy();
    }

    private boolean sharesReference(int slotIndex, ItemStack stack) {
        Inventory inventory = player.getInventory();
        if (containsReference(inventory.armor, stack)
                || containsReference(inventory.offhand, stack)
                || containsReference(inventory.items, stack)) {
            return true;
        }
        for (int i = 0; i < items.size(); i++) {
            if (i != slotIndex && items.get(i) == stack) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsReference(List<ItemStack> slots, ItemStack stack) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == stack) {
                return true;
            }
        }
        return false;
    }
}
