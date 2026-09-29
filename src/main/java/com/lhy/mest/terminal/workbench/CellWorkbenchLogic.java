package com.lhy.mest.terminal.workbench;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.CopyMode;
import appeng.api.config.FuzzyMode;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.core.definitions.AEItems;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.menu.slot.IPartitionSlotHost;
import appeng.util.inv.AppEngInternalInventory;

import com.lhy.mest.api.CellWorkbenchAction;
import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.terminal.MESTMenu;
import com.lhy.mest.terminal.MESTMenuHost;

/**
 * AE2's cell workbench without a block entity. The server edits either the cell in the terminal's
 * own slot or a {@link CellWorkbenchTarget} elsewhere (a cell inside an ME drive). The client only
 * holds mirrors filled by normal slot sync, plus the {@code @GuiSync} state on {@link MESTMenu}.
 */
public final class CellWorkbenchLogic implements IPartitionSlotHost {
    public static final int CONFIG_SLOTS = 63;
    public static final int UPGRADE_SLOTS = 8;
    private static final String CONFIG_TAG = "config";

    private final MESTMenu menu;
    @Nullable
    private final MESTMenuHost host;
    @Nullable
    private final InternalInventory local;
    private final InternalInventory cellInventory;
    private final InternalInventory upgradeInventory;
    private final GenericStackInv config = new GenericStackInv(this::configChanged,
            GenericStackInv.Mode.CONFIG_TYPES, CONFIG_SLOTS);
    private final InternalInventory configMenuInventory = config.createMenuWrapper();

    @Nullable
    private CellWorkbenchTarget target;
    private ItemStack lastSeen = ItemStack.EMPTY;
    private boolean reloadPending = true;
    private boolean locked;
    private int configSize = -1;
    @Nullable
    private IUpgradeInventory cachedUpgrades;

    public CellWorkbenchLogic(MESTMenu menu, MESTMenuHost host, boolean client) {
        this.menu = menu;
        if (client) {
            this.host = null;
            this.local = null;
            this.cellInventory = new AppEngInternalInventory(null, 1, 1);
            this.upgradeInventory = new AppEngInternalInventory(null, UPGRADE_SLOTS, 1);
        } else {
            this.host = host;
            this.local = host.getCellWorkbenchInventory();
            this.cellInventory = new CellView();
            this.upgradeInventory = new UpgradeView();
            CompoundTag saved = host.getItemStack().get(ModComponents.CELL_WORKBENCH_CONFIG.get());
            if (saved != null) {
                locked = true;
                try {
                    config.readFromChildTag(saved, CONFIG_TAG, menu.getPlayer().registryAccess());
                } finally {
                    locked = false;
                }
            }
        }
    }

    public InternalInventory cellInventory() {
        return cellInventory;
    }

    public InternalInventory upgradeInventory() {
        return upgradeInventory;
    }

    public InternalInventory configInventory() {
        return configMenuInventory;
    }

    @Nullable
    public CellWorkbenchTarget target() {
        return target;
    }

    /** {@code null} returns the workbench to the cell in its own slot. */
    public void setTarget(@Nullable CellWorkbenchTarget target) {
        this.target = target;
        reloadPending = true;
        cachedUpgrades = null;
        serverTick();
    }

    public ItemStack currentStack() {
        if (local == null) {
            return cellInventory.getStackInSlot(0);
        }
        return target != null ? target.stack() : local.getStackInSlot(0);
    }

    /** Client: whether a fuzzy card sits in the synced upgrade slots. */
    public boolean hasFuzzyCard() {
        for (int i = 0; i < upgradeInventory.size(); i++) {
            if (upgradeInventory.getStackInSlot(i).is(AEItems.FUZZY_CARD.asItem())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPartitionSlotEnabled(int idx) {
        if (!menu.workbenchOpen) {
            return false;
        }
        if (menu.workbenchConfigSlots >= 0 && menu.workbenchCopyMode == CopyMode.CLEAR_ON_REMOVE) {
            return idx < menu.workbenchConfigSlots;
        }
        return menu.workbenchCopyMode == CopyMode.KEEP_ON_REMOVE;
    }

    public boolean isUpgradeSlotEnabled(int idx) {
        return menu.workbenchOpen && idx < menu.workbenchUpgradeSlots;
    }

    /** Server, every menu tick before sync: follow external changes and publish the synced state. */
    public void serverTick() {
        if (local == null) {
            return;
        }
        if (target != null && !target.valid()) {
            target = null;
            reloadPending = true;
        }
        ItemStack current = currentStack();
        if (reloadPending || !ItemStack.matches(current, lastSeen)) {
            // Only a cell dropped into the workbench's own slot may receive the kept partition.
            boolean inserted = !reloadPending && target == null;
            reloadPending = false;
            cachedUpgrades = null;
            lastSeen = current.copy();
            onCellChanged(current, inserted);
        }
        ICellWorkbenchItem cell = cell(current);
        menu.workbenchRemote = target != null;
        menu.workbenchConfigSlots = cell == null ? -1 : configSize;
        menu.workbenchUpgradeSlots = Math.min(UPGRADE_SLOTS, upgrades().size());
        menu.workbenchFuzzyMode = cell == null ? FuzzyMode.IGNORE_ALL : cell.getFuzzyMode(current);
        menu.workbenchCopyMode = copyMode();
    }

    private void onCellChanged(ItemStack current, boolean inserted) {
        ICellWorkbenchItem cell = cell(current);
        locked = true;
        try {
            if (cell != null) {
                ItemStack work = current.copy();
                var cellConfig = cell.getConfigInventory(work);
                configSize = cellConfig.size();
                if (inserted && cellConfig.isEmpty() && !config.isEmpty()) {
                    copy(config, cellConfig);
                    write(work);
                }
                copy(cellConfig, config);
            } else {
                configSize = -1;
                if (copyMode() == CopyMode.CLEAR_ON_REMOVE) {
                    config.clear();
                }
            }
        } finally {
            locked = false;
        }
        persistConfig();
    }

    private void configChanged() {
        if (locked || local == null) {
            return;
        }
        pushConfig();
    }

    /** Writes the mirror into the cell once, then reads back what the cell accepted. */
    private void pushConfig() {
        ItemStack fresh = freshStack();
        ICellWorkbenchItem cell = cell(fresh);
        if (cell != null) {
            ItemStack work = fresh.copy();
            var cellConfig = cell.getConfigInventory(work);
            locked = true;
            try {
                copy(config, cellConfig);
                copy(cellConfig, config);
            } finally {
                locked = false;
            }
            write(work);
        }
        persistConfig();
    }

    public void partition() {
        if (local == null) {
            return;
        }
        var inventory = StorageCells.getCellInventory(freshStack().copy(), null);
        var keys = new ArrayList<AEKey>();
        if (inventory != null) {
            for (var entry : inventory.getAvailableStacks()) {
                keys.add(entry.getKey());
            }
        }
        locked = true;
        try {
            for (int i = 0; i < config.size(); i++) {
                config.setStack(i, i < keys.size() ? new GenericStack(keys.get(i), 0) : null);
            }
        } finally {
            locked = false;
        }
        pushConfig();
    }

    public void clear() {
        if (local == null) {
            return;
        }
        locked = true;
        try {
            config.clear();
        } finally {
            locked = false;
        }
        pushConfig();
    }

    public void nextCopyMode() {
        if (host == null) {
            return;
        }
        host.getItemStack().set(ModComponents.CELL_WORKBENCH_KEEP.get(), copyMode() == CopyMode.CLEAR_ON_REMOVE);
    }

    public void setFuzzyMode(FuzzyMode mode) {
        edit(work -> cell(work).setFuzzyMode(work, mode));
    }

    public void runAction(ServerPlayer player, String id, boolean alternate) {
        CellWorkbenchAction action = CellWorkbenchActions.get(id);
        if (action != null) {
            edit(work -> action.apply(player, work, alternate));
        }
    }

    private CopyMode copyMode() {
        return host != null && host.getItemStack().getOrDefault(ModComponents.CELL_WORKBENCH_KEEP.get(), false)
                ? CopyMode.KEEP_ON_REMOVE
                : CopyMode.CLEAR_ON_REMOVE;
    }

    private void edit(Consumer<ItemStack> change) {
        ItemStack fresh = freshStack();
        if (cell(fresh) == null) {
            return;
        }
        ItemStack work = fresh.copy();
        change.accept(work);
        write(work);
        reloadPending = true;
    }

    private ItemStack freshStack() {
        return target != null ? target.freshStack() : local.getStackInSlot(0);
    }

    private void write(ItemStack work) {
        if (target != null) {
            target.write(work);
        } else {
            local.setItemDirect(0, work);
        }
        lastSeen = work.copy();
        cachedUpgrades = null;
    }

    private IUpgradeInventory upgrades() {
        if (cachedUpgrades == null) {
            ItemStack stack = currentStack();
            ICellWorkbenchItem cell = cell(stack);
            IUpgradeInventory upgrades = cell == null ? null : cell.getUpgrades(stack.copy());
            cachedUpgrades = upgrades == null ? UpgradeInventories.empty() : upgrades;
        }
        return cachedUpgrades;
    }

    private void persistConfig() {
        if (host == null) {
            return;
        }
        var tag = new CompoundTag();
        config.writeToChildTag(tag, CONFIG_TAG, menu.getPlayer().registryAccess());
        host.getItemStack().set(ModComponents.CELL_WORKBENCH_CONFIG.get(), tag);
    }

    @Nullable
    private static ICellWorkbenchItem cell(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ICellWorkbenchItem cell && cell.isEditable(stack)
                ? cell
                : null;
    }

    public static boolean isEditableCell(ItemStack stack) {
        return cell(stack) != null;
    }

    /** {@code CellWorkbenchBlockEntity.copy}: skips keys the destination slot refuses. */
    private static void copy(GenericStackInv from, GenericStackInv to) {
        for (int i = 0; i < Math.min(from.size(), to.size()); i++) {
            var stack = from.getStack(i);
            if (stack != null && !to.isAllowedIn(i, stack.what())) {
                stack = null;
            }
            to.setStack(i, stack);
        }
        for (int i = from.size(); i < to.size(); i++) {
            to.setStack(i, null);
        }
    }

    private final class CellView implements InternalInventory {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return currentStack();
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            if (target == null) {
                local.setItemDirect(0, stack);
            }
        }

        // A drive cell is only on display here; taking it must never copy it out of the drive.
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return target == null ? InternalInventory.super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return target == null && isEditableCell(stack);
        }
    }

    private final class UpgradeView implements InternalInventory {
        @Override
        public int size() {
            return UPGRADE_SLOTS;
        }

        @Override
        public int getSlotLimit(int slot) {
            var upgrades = upgrades();
            return slot < upgrades.size() ? upgrades.getSlotLimit(slot) : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            var upgrades = upgrades();
            return slotIndex < upgrades.size() ? upgrades.getStackInSlot(slotIndex) : ItemStack.EMPTY;
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            edit(work -> {
                var upgrades = cell(work).getUpgrades(work);
                if (upgrades != null && slotIndex < upgrades.size()) {
                    upgrades.setItemDirect(slotIndex, stack);
                }
            });
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            var upgrades = upgrades();
            return slot < upgrades.size() && upgrades.isItemValid(slot, stack);
        }
    }
}
