package com.lhy.mest.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.api.storage.cells.CellState;
import appeng.blockentity.AEBaseInvBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.helpers.IPriorityHost;
import appeng.me.cells.BasicCellInventory;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.locator.ItemMenuHostLocator;

import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.terminal.workbench.CellWorkbenchLogic;
import com.lhy.mest.terminal.workbench.CellWorkbenchTarget;

/** Menu-scoped server snapshot. No remote slots are added to the fixed terminal container. */
public final class DriveCellSession {
    private static final int MAX_SLOTS_PER_DRIVE = 128;
    private static final long MIN_REQUEST_TICKS = 5;
    // Full cell stacks let the client render AE2's own tooltip; oversized ones degrade to the bare icon.
    private static final int MAX_STACK_BYTES = 32 * 1024;
    private static final int MAX_WINDOW_STACK_BYTES = 512 * 1024;

    private record Drive(IGridNode node, IChestOrDrive host, BlockEntity block, IItemHandler inventory, int slots) {
    }

    private record SlotRef(Drive drive, int slot, ItemStack expected) {
    }

    private record Scan(List<Drive> drives, boolean truncated) {
    }

    private final MESTMenu menu;
    private IGrid grid;
    private int revision;
    private int windowStart = -1;
    private int totalSlots;
    private boolean truncated;
    private long lastRequestTick = -1;
    private List<Drive> hosts = List.of();
    private List<SlotRef> references = List.of();
    private List<DriveCellListPacket.Group> previousGroups = List.of();
    private List<DriveCellListPacket.Row> previousRows = List.of();
    private int searchId;
    private String query = "";
    private Set<AEKey> searchKeys = Set.of();
    private int previousSearchId;
    private List<Integer> previousMatches = List.of();
    private int previousEditing = -1;

    public DriveCellSession(MESTMenu menu) {
        this.menu = menu;
    }

    public void request(ServerPlayer player, int requestedStart) {
        if (requestedStart < 0) {
            clear();
            return;
        }
        long tick = player.level().getGameTime();
        if (lastRequestTick >= 0 && tick - lastRequestTick < MIN_REQUEST_TICKS) {
            return;
        }
        lastRequestTick = tick;
        IGrid current = connectedGrid(player);
        if (current == null) {
            if (grid != null || windowStart != 0 || totalSlots != 0 || truncated
                    || !hosts.isEmpty() || !previousGroups.isEmpty() || !previousRows.isEmpty()) {
                grid = null;
                publish(player, 0, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), -1);
            }
            return;
        }
        Scan scan = discover(current);
        List<Drive> drives = scan.drives();
        int count = 0;
        List<DriveCellListPacket.Group> groups = new ArrayList<>(drives.size());
        for (Drive drive : drives) {
            count += drive.slots();
            Component label = drive.block() instanceof Nameable nameable && nameable.getCustomName() != null
                    ? nameable.getCustomName()
                    : drive.block().getBlockState().getBlock().getName();
            String where = drive.block().getBlockPos().toShortString();
            if (drive.block().getLevel().dimension() != player.level().dimension()) {
                where += " @" + drive.block().getLevel().dimension().location();
            }
            boolean editable = drive.block() instanceof IPriorityHost;
            int priority = editable ? ((IPriorityHost) drive.block()).getPriority() : 0;
            long usedBytes = 0;
            long totalBytes = 0;
            for (int slot = 0; slot < drive.slots(); slot++) {
                if (drive.host().getOriginalCellInventory(slot) instanceof BasicCellInventory cell) {
                    usedBytes += Math.max(0, cell.getUsedBytes());
                    totalBytes += Math.max(0, cell.getTotalBytes());
                }
            }
            groups.add(new DriveCellListPacket.Group(label, Component.literal(where),
                    drive.block().getBlockState().getBlock().asItem(), drive.slots(), priority,
                    editable, drive.host().isPowered(), usedBytes, totalBytes));
        }
        int start = Math.min(requestedStart, count);
        int end = (int) Math.min((long) count, (long) start + DriveCellListPacket.MAX_ROWS);
        List<SlotRef> nextRefs = new ArrayList<>(end - start);
        List<DriveCellListPacket.Row> rows = new ArrayList<>(end - start);
        int budget = MAX_WINDOW_STACK_BYTES;
        int index = 0;
        for (int driveIndex = 0; driveIndex < drives.size() && index < end; driveIndex++) {
            Drive drive = drives.get(driveIndex);
            for (int slot = 0; slot < drive.slots() && index < end; slot++, index++) {
                if (index < start) {
                    continue;
                }
                ItemStack stack = drive.inventory().getStackInSlot(slot).copy();
                nextRefs.add(new SlotRef(drive, slot, stack));
                ItemStack shown = ItemStack.EMPTY;
                if (!stack.isEmpty()) {
                    int size = encodedSize(player, stack);
                    if (size <= MAX_STACK_BYTES && size <= budget) {
                        budget -= size;
                        shown = stack;
                    } else {
                        shown = new ItemStack(stack.getItem());
                    }
                }
                rows.add(row(drive, driveIndex, slot, shown));
            }
        }
        List<Integer> matches = matches(drives);
        int editing = editingIndex(drives);
        if (grid != current || windowStart != start || totalSlots != count || truncated != scan.truncated()
                || !sameHosts(hosts, drives) || !previousGroups.equals(groups)
                || !sameRows(previousRows, rows) || !sameSlots(references, nextRefs)
                || previousSearchId != searchId || !previousMatches.equals(matches)
                || previousEditing != editing) {
            grid = current;
            publish(player, start, count, scan.truncated(), drives, groups, nextRefs, rows, matches, editing);
        } else {
            // Update the full stack action guard without sending redundant icons or cell contents.
            references = List.copyOf(nextRefs);
        }
    }

    private static int encodedSize(ServerPlayer player, ItemStack stack) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess(), ConnectionType.NEOFORGE);
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
            return buf.readableBytes();
        } catch (RuntimeException exception) {
            return Integer.MAX_VALUE;
        } finally {
            buf.release();
        }
    }

    public void search(ServerPlayer player, int id, String text, List<AEKey> keys) {
        searchId = id;
        query = text.strip().toLowerCase(Locale.ROOT);
        searchKeys = query.isEmpty() ? Set.of() : new HashSet<>(keys);
        if (windowStart >= 0) {
            lastRequestTick = -1;
            request(player, windowStart);
        }
    }

    /** Absolute slot indices, ascending, of cells holding any key matching the current search. */
    private List<Integer> matches(List<Drive> drives) {
        if (query.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Integer>();
        int index = 0;
        for (Drive drive : drives) {
            for (int slot = 0; slot < drive.slots(); slot++, index++) {
                StorageCell cell = drive.host().getOriginalCellInventory(slot);
                if (cell == null) {
                    continue;
                }
                for (AEKey key : cell.getAvailableStacks().keySet()) {
                    if (matches(key)) {
                        result.add(index);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private boolean matches(AEKey key) {
        return searchKeys.contains(key) || key.getId().toString().contains(query)
                || key.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private static DriveCellListPacket.Row row(Drive drive, int driveIndex, int slot, ItemStack icon) {
        if (icon.isEmpty()) {
            return new DriveCellListPacket.Row(driveIndex, slot, icon);
        }
        CellState state = drive.host().getCellStatus(slot);
        int status = state == null ? CellState.ABSENT.ordinal() : state.ordinal();
        if (drive.host().getOriginalCellInventory(slot) instanceof BasicCellInventory cell) {
            return new DriveCellListPacket.Row(driveIndex, slot, icon, status,
                    Math.max(0, cell.getUsedBytes()), Math.max(0, cell.getTotalBytes()),
                    Math.max(0, cell.getStoredItemTypes()), Math.max(0, cell.getTotalItemTypes()));
        }
        return new DriveCellListPacket.Row(driveIndex, slot, icon, status, 0, 0, 0, 0);
    }

    public void act(ServerPlayer player, int expectedRevision, int row, DriveCellActionPacket.Action action) {
        if (expectedRevision != revision || row < 0 || row >= references.size() || grid == null
                || connectedGrid(player) != grid) {
            return;
        }
        SlotRef ref = references.get(row);
        Drive drive = ref.drive();
        if (action == DriveCellActionPacket.Action.EDIT) {
            // A stored cell's data changes with every insert, so only the item itself must still match.
            if (active(drive) && ref.slot() < drive.inventory().getSlots() && ItemStack.isSameItem(
                    ref.expected(), drive.inventory().getStackInSlot(ref.slot()))) {
                toggleEdit(player, drive, ref.slot());
            }
            lastRequestTick = -1;
            request(player, windowStart);
            return;
        }
        if (!active(drive) || ref.slot() >= drive.inventory().getSlots()
                || !ItemStack.matches(ref.expected(), drive.inventory().getStackInSlot(ref.slot()))) {
            lastRequestTick = -1;
            request(player, windowStart);
            return;
        }
        IItemHandler inventory = drive.inventory();
        boolean changed;
        if (action == DriveCellActionPacket.Action.CURSOR) {
            changed = cursorTransfer(player, inventory, ref.slot());
        } else if (action == DriveCellActionPacket.Action.QUICK_MOVE) {
            changed = quickTransfer(player, inventory, ref.slot());
        } else {
            return;
        }
        if (changed) {
            menu.broadcastChanges();
            lastRequestTick = -1;
            request(player, windowStart);
        }
    }

    private void toggleEdit(ServerPlayer player, Drive drive, int slot) {
        var workbench = menu.cellWorkbench();
        if (workbench.target() instanceof DriveTarget editing && editing.is(drive.block(), slot)) {
            workbench.setTarget(null);
            return;
        }
        ItemStack stack = drive.inventory().getStackInSlot(slot);
        if (CellWorkbenchLogic.isEditableCell(stack)) {
            workbench.setTarget(new DriveTarget(menu, grid, drive, slot, stack.getItem()));
        }
    }

    private int editingIndex(List<Drive> drives) {
        if (!(menu.cellWorkbench().target() instanceof DriveTarget editing)) {
            return -1;
        }
        int index = 0;
        for (Drive drive : drives) {
            if (drive.block() == editing.drive.block()) {
                return editing.slot < drive.slots() ? index + editing.slot : -1;
            }
            index += drive.slots();
        }
        return -1;
    }

    /**
     * A cell edited in place inside a drive. Every write first flushes the live cell inventory, so
     * items stored since the last save are part of the copy that is written back.
     */
    private static final class DriveTarget implements CellWorkbenchTarget {
        private final MESTMenu menu;
        private final IGrid grid;
        private final Drive drive;
        private final int slot;
        private final net.minecraft.world.item.Item item;

        private DriveTarget(MESTMenu menu, IGrid grid, Drive drive, int slot, net.minecraft.world.item.Item item) {
            this.menu = menu;
            this.grid = grid;
            this.drive = drive;
            this.slot = slot;
            this.item = item;
        }

        boolean is(BlockEntity block, int slot) {
            return drive.block() == block && this.slot == slot;
        }

        @Override
        public ItemStack stack() {
            return slot < drive.inventory().getSlots() ? drive.inventory().getStackInSlot(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack freshStack() {
            StorageCell cell = drive.host().getOriginalCellInventory(slot);
            if (cell != null) {
                cell.persist();
            }
            return stack();
        }

        @Override
        public void write(ItemStack stack) {
            if (drive.block() instanceof AEBaseInvBlockEntity inventoryBlock) {
                inventoryBlock.getInternalInventory().setItemDirect(slot, stack);
            } else if (drive.inventory() instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, stack);
            }
        }

        @Override
        public boolean valid() {
            var node = menu.getGridNode();
            return node != null && node.getGrid() == grid && drive.node().isActive()
                    && drive.node().getGrid() == grid && live(drive.block())
                    && slot < drive.inventory().getSlots() && stack().is(item)
                    && (drive.block() instanceof AEBaseInvBlockEntity
                            || drive.inventory() instanceof IItemHandlerModifiable);
        }
    }

    public void openPriority(ServerPlayer player, int expectedRevision, int driveIndex) {
        if (expectedRevision != revision || grid == null || connectedGrid(player) != grid || driveIndex < 0
                || driveIndex >= hosts.size() || driveIndex >= previousGroups.size()
                || !previousGroups.get(driveIndex).editable()) {
            return;
        }
        Drive drive = hosts.get(driveIndex);
        if (!active(drive) || !(drive.block() instanceof IPriorityHost)
                || !(menu.getMestHost().getLocator() instanceof ItemMenuHostLocator terminal)) {
            return;
        }
        MenuOpener.open(PriorityMenu.TYPE, player, new DrivePriorityLocator(terminal,
                GlobalPos.of(drive.block().getLevel().dimension(), drive.block().getBlockPos())));
    }

    /** Places cells from a shift-click into empty drive slots; returns how many were inserted. */
    public int insertCells(ServerPlayer player, ItemStack input) {
        if (windowStart < 0 || grid == null || input.isEmpty() || !StorageCells.isCellHandled(input)
                || connectedGrid(player) != grid) {
            return 0;
        }
        int inserted = 0;
        for (Drive drive : hosts) {
            if (inserted >= input.getCount() || !active(drive)) {
                continue;
            }
            IItemHandler inventory = drive.inventory();
            for (int slot = 0; slot < drive.slots() && inserted < input.getCount(); slot++) {
                ItemStack one = input.copyWithCount(1);
                if (!inventory.getStackInSlot(slot).isEmpty() || !inventory.isItemValid(slot, one)
                        || !inventory.insertItem(slot, one, true).isEmpty()) {
                    continue;
                }
                if (inventory.insertItem(slot, one, false).isEmpty()) {
                    inserted++;
                }
            }
        }
        if (inserted > 0) {
            lastRequestTick = -1;
            request(player, windowStart);
        }
        return inserted;
    }

    private boolean active(Drive drive) {
        return drive.node().isActive() && drive.node().getGrid() == grid && live(drive.block());
    }

    private boolean cursorTransfer(ServerPlayer player, IItemHandler inventory, int slot) {
        ItemStack current = inventory.getStackInSlot(slot);
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() && !current.isEmpty()) {
            ItemStack extracted = inventory.extractItem(slot, 1, false);
            if (extracted.isEmpty()) {
                return false;
            }
            menu.setCarried(extracted);
            return true;
        }
        if (!carried.isEmpty() && current.isEmpty() && StorageCells.isCellHandled(carried)
                && inventory.isItemValid(slot, carried)
                && inventory.insertItem(slot, carried.copyWithCount(1), true).isEmpty()) {
            ItemStack remainder = inventory.insertItem(slot, carried.copyWithCount(1), false);
            if (!remainder.isEmpty()) {
                return false;
            }
            menu.setCarried(carried.getCount() == 1 ? ItemStack.EMPTY : carried.copyWithCount(carried.getCount() - 1));
            return true;
        }
        return false;
    }

    private boolean quickTransfer(ServerPlayer player, IItemHandler inventory, int slot) {
        ItemStack current = inventory.getStackInSlot(slot);
        var playerInventory = player.getInventory();
        if (!current.isEmpty()) {
            int free = playerInventory.getFreeSlot();
            if (free < 0 || free >= 36) {
                return false;
            }
            ItemStack extracted = inventory.extractItem(slot, 1, false);
            if (extracted.isEmpty()) {
                return false;
            }
            playerInventory.setItem(free, extracted);
            return true;
        }
        for (int source = 0; source < 36; source++) {
            ItemStack stack = playerInventory.getItem(source);
            if (stack.isEmpty() || !StorageCells.isCellHandled(stack) || !inventory.isItemValid(slot, stack)) {
                continue;
            }
            ItemStack one = stack.copyWithCount(1);
            if (!inventory.insertItem(slot, one, true).isEmpty()) {
                continue;
            }
            if (!inventory.insertItem(slot, one, false).isEmpty()) {
                return false;
            }
            playerInventory.setItem(source, stack.getCount() == 1
                    ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1));
            return true;
        }
        return false;
    }

    private IGrid connectedGrid(ServerPlayer player) {
        if (!menu.canUsePatternAccess(player)) {
            return null;
        }
        IGridNode node = menu.getGridNode();
        return node == null ? null : node.getGrid();
    }

    private static Scan discover(IGrid grid) {
        var result = new ArrayList<Drive>();
        var seen = new IdentityHashMap<Object, Boolean>();
        boolean limited = false;
        for (IGridNode node : grid.getNodes()) {
            if (!node.isActive() || node.getGrid() != grid || !(node.getOwner() instanceof IChestOrDrive host)
                    || !(node.getOwner() instanceof BlockEntity block) || seen.put(host, true) != null
                    || !live(block)) {
                continue;
            }
            int count = host.getCellCount();
            if (count < 1) {
                continue;
            }
            IItemHandler inventory;
            if (block instanceof AEBaseInvBlockEntity ae) {
                inventory = ae.getInternalInventory().toItemHandler();
            } else {
                // Public capability fallback: reject unrelated upgrade/tool inventories.
                inventory = block.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, block.getBlockPos(), null);
                if (inventory == null || inventory.getSlots() != count) {
                    continue;
                }
            }
            if (!(block instanceof DriveBlockEntity) && inventory.getSlots() != count) {
                continue;
            }
            int slots = Math.min(Math.min(count, inventory.getSlots()), MAX_SLOTS_PER_DRIVE);
            if (slots < 1) {
                continue;
            }
            if (count > MAX_SLOTS_PER_DRIVE) {
                limited = true;
            }
            result.add(new Drive(node, host, block, inventory, slots));
        }
        // Highest priority first, as the grid fills storage in that order.
        result.sort(Comparator.comparingInt((Drive drive) -> drive.block() instanceof IPriorityHost host
                        ? -host.getPriority() : 0)
                .thenComparing(drive -> drive.block().getLevel().dimension().location().toString())
                .thenComparingLong(drive -> drive.block().getBlockPos().asLong()));
        if (result.size() > DriveCellListPacket.MAX_GROUPS) {
            limited = true;
            result.subList(DriveCellListPacket.MAX_GROUPS, result.size()).clear();
        }
        return new Scan(result, limited);
    }

    private static boolean live(BlockEntity block) {
        if (!(block.getLevel() instanceof ServerLevel level) || block.isRemoved()) {
            return false;
        }
        BlockPos pos = block.getBlockPos();
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) == block;
    }

    private void publish(ServerPlayer player, int start, int count, boolean limited, List<Drive> drives,
            List<DriveCellListPacket.Group> groups, List<SlotRef> refs, List<DriveCellListPacket.Row> rows,
            List<Integer> matches, int editing) {
        revision = (revision + 1) & Integer.MAX_VALUE;
        windowStart = start;
        totalSlots = count;
        truncated = limited;
        hosts = List.copyOf(drives);
        previousGroups = List.copyOf(groups);
        references = List.copyOf(refs);
        previousRows = List.copyOf(rows);
        previousSearchId = searchId;
        previousMatches = List.copyOf(matches);
        previousEditing = editing;
        PacketDistributor.sendToPlayer(player, new DriveCellListPacket(menu.containerId, revision, windowStart,
                totalSlots, truncated, previousGroups, previousRows, previousSearchId, previousMatches,
                previousEditing));
    }

    private static boolean sameHosts(List<Drive> a, List<Drive> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).block() != b.get(i).block() || a.get(i).node() != b.get(i).node()
                    || a.get(i).inventory() != b.get(i).inventory()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSlots(List<SlotRef> a, List<SlotRef> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).drive().block() != b.get(i).drive().block()
                    || a.get(i).slot() != b.get(i).slot()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRows(List<DriveCellListPacket.Row> a, List<DriveCellListPacket.Row> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            var before = a.get(i);
            var after = b.get(i);
            if (before.drive() != after.drive() || before.slot() != after.slot()
                    || before.status() != after.status() || before.usedBytes() != after.usedBytes()
                    || before.totalBytes() != after.totalBytes() || before.usedTypes() != after.usedTypes()
                    || before.totalTypes() != after.totalTypes()
                    || !ItemStack.matches(before.stack(), after.stack())) {
                return false;
            }
        }
        return true;
    }

    public void clear() {
        grid = null;
        windowStart = -1;
        references = List.of();
        hosts = List.of();
        previousGroups = List.of();
        previousRows = List.of();
        totalSlots = 0;
        truncated = false;
        lastRequestTick = -1;
        searchId = 0;
        query = "";
        searchKeys = Set.of();
        previousSearchId = 0;
        previousMatches = List.of();
        previousEditing = -1;
    }
}
