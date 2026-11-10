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
    private final DriveSearchScheduler scheduler = new DriveSearchScheduler(MIN_REQUEST_TICKS);
    private static final int CELLS_PER_TICK = 16;
    private static final int KEYS_PER_TICK = 256;
    private static final long CACHE_TICKS = 100;
    private static final long SEARCH_REFRESH_TICKS = 40;
    private static final long SOFT_NANOS_PER_TICK = 2_000_000;
    private static final boolean METRICS = Boolean.getBoolean("mest.driveSearch.metrics");
    private static final System.Logger LOGGER = System.getLogger(DriveCellSession.class.getName());
    private Scan topology;
    private IGrid topologyGrid;
    private long topologyTick = Long.MIN_VALUE;
    private long statsTick = Long.MIN_VALUE;
    private long searchTick = Long.MIN_VALUE;
    private boolean searchDirty = true;
    private SnapshotJob job;
    private long metricTicks, metricCells, metricKeys, metricSnapshots, metricDiscoveries, metricNanos;
    private long metricEnumerations, metricEnumerationNanos, metricMaxTickNanos;
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

    /** Packet entry point: no grid traversal, cell reads or packet construction here. */
    public void request(ServerPlayer player, int requestedStart) {
        if (requestedStart < 0) {
            clear();
        } else if (scheduler.request(requestedStart)) {
            job = null;
        }
    }

    public void serverTick(ServerPlayer player) {
        long tick = player.level().getGameTime();
        if (!scheduler.tick(tick) || scheduler.requestedStart() < 0) return;
        long began = System.nanoTime();
        try {
            IGrid current = connectedGrid(player);
            if (current != topologyGrid) {
                topology = null;
                topologyGrid = current;
                job = null;
                searchDirty = true;
                scheduler.invalidate();
            }
            if (current == null) {
                if (grid != null || windowStart != 0 || previousSearchId != searchId) {
                    grid = null;
                    publish(player, 0, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), -1);
                }
                return;
            }
            if (query.isEmpty() && grid == current && windowStart >= 0
                    && (previousSearchId != searchId || !previousMatches.isEmpty())) {
                // Clear search feedback on the next tick, without waiting for a statistics sweep.
                publish(player, windowStart, totalSlots, truncated, hosts, previousGroups,
                        references, previousRows, List.of(), previousEditing);
            }
            if (job == null && scheduler.start(tick)) {
                if (topology == null || expired(tick, topologyTick, CACHE_TICKS)) {
                    Scan next = discover(current);
                    metricDiscoveries++;
                    if (topology == null || !sameHosts(topology.drives(), next.drives())) {
                        statsTick = Long.MIN_VALUE;
                        searchDirty = true;
                    }
                    topology = next;
                    topologyTick = tick;
                }
                job = new SnapshotJob(current, topology, scheduler.requestedStart(), tick);
            }
            if (job != null && job.advance(player, began + SOFT_NANOS_PER_TICK)) {
                SnapshotJob completed = job;
                job = null;
                if (completed.generation == scheduler.generation() && connectedGrid(player) == completed.current) {
                    completed.finish(player, tick);
                }
            }
        } finally {
            if (METRICS) {
                long elapsed = System.nanoTime() - began;
                metricNanos += elapsed;
                metricMaxTickNanos = Math.max(metricMaxTickNanos, elapsed);
                if (++metricTicks % 200 == 0) {
                    LOGGER.log(System.Logger.Level.INFO,
                            "DriveSearch menu={0} ticks={1} cells={2} keys={3} enumerations={4} enumerationNs={5} snapshots={6} topologyScans={7} totalNs={8} maxTickNs={9}",
                            menu.containerId, metricTicks, metricCells, metricKeys, metricEnumerations,
                            metricEnumerationNanos, metricSnapshots, metricDiscoveries, metricNanos, metricMaxTickNanos);
                }
            }
        }
    }

    private static boolean expired(long now, long before, long interval) {
        return before == Long.MIN_VALUE || now < before || now - before >= interval;
    }

    /** A snapshot is assembled privately; only the current generation may publish it. */
    private final class SnapshotJob {
        final IGrid current;
        final Scan scan;
        final long generation = scheduler.generation();
        final int start, end, count;
        final boolean statistics, searching;
        final long[] used, capacity;
        final List<SlotRef> refs = new ArrayList<>();
        final List<DriveCellListPacket.Row> rows = new ArrayList<>();
        final List<Integer> matches = new ArrayList<>();
        java.util.Iterator<it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>> keys;
        int driveIndex, slot, index, stackBudget = MAX_WINDOW_STACK_BYTES;

        SnapshotJob(IGrid current, Scan scan, int requestedStart, long tick) {
            this.current = current;
            this.scan = scan;
            count = scan.drives().stream().mapToInt(Drive::slots).sum();
            start = Math.min(requestedStart, count);
            end = (int) Math.min((long) count, (long) start + DriveCellListPacket.MAX_ROWS);
            statistics = expired(tick, statsTick, CACHE_TICKS) || !sameHosts(hosts, scan.drives());
            searching = !query.isEmpty() && (searchDirty || expired(tick, searchTick, SEARCH_REFRESH_TICKS));
            used = new long[scan.drives().size()];
            capacity = new long[used.length];
            if (!statistics) {
                for (int i = 0; i < used.length; i++) {
                    used[i] = previousGroups.get(i).usedBytes();
                    capacity[i] = previousGroups.get(i).totalBytes();
                }
            }
            if (!searching && !query.isEmpty()) matches.addAll(previousMatches);
        }

        boolean advance(ServerPlayer player, long deadline) {
            var budget = new DriveSearchBudget(CELLS_PER_TICK, KEYS_PER_TICK);
            while (driveIndex < scan.drives().size()) {
                if (System.nanoTime() >= deadline) return false;
                Drive drive = scan.drives().get(driveIndex);
                if (!statistics && !searching) {
                    if (index >= end) return true;
                    if (index < start) {
                        // Skip cached topology arithmetically, not by probing every off-screen slot.
                        int skipped = Math.min(start - index, drive.slots() - slot);
                        index += skipped;
                        slot += skipped;
                        if (slot == drive.slots()) {
                            slot = 0;
                            driveIndex++;
                        }
                        continue;
                    }
                }
                // Recheck live identity before touching cached topology. No chunk loads.
                if (!drive.node().isActive() || drive.node().getGrid() != current || !live(drive.block())
                        || drive.inventory().getSlots() < drive.slots()) {
                    topology = null;
                    scheduler.invalidate();
                    return true; // The generation guard discards this incomplete result.
                }
                if (keys != null) {
                    while (keys.hasNext()) {
                        if (System.nanoTime() >= deadline || !budget.key()) return false;
                        metricKeys++;
                        if (matches(keys.next().getKey())) {
                            matches.add(index);
                            break;
                        }
                    }
                    keys = null;
                    nextSlot(drive);
                    continue;
                }
                boolean visible = index >= start && index < end;
                if (!budget.cell()) return false;
                metricCells++;
                StorageCell cell = drive.host().getOriginalCellInventory(slot);
                if (statistics && cell instanceof BasicCellInventory basic) {
                    used[driveIndex] += Math.max(0, basic.getUsedBytes());
                    capacity[driveIndex] += Math.max(0, basic.getTotalBytes());
                }
                if (visible) {
                    ItemStack stack = drive.inventory().getStackInSlot(slot).copy();
                    refs.add(new SlotRef(drive, slot, stack));
                    ItemStack shown = stack;
                    if (!stack.isEmpty()) {
                        int bytes = encodedSize(player, stack);
                        if (bytes <= MAX_STACK_BYTES && bytes <= stackBudget) stackBudget -= bytes;
                        else shown = new ItemStack(stack.getItem());
                    }
                    rows.add(row(drive, driveIndex, slot, shown));
                }
                if (searching && cell != null) {
                    // AE2 returns an owned counter. Iterate it directly: keySet() eagerly copies ALL keys.
                    // Enumeration itself is third-party synchronous code and cannot be preempted.
                    long began = METRICS ? System.nanoTime() : 0;
                    keys = cell.getAvailableStacks().iterator();
                    metricEnumerations++;
                    if (METRICS) metricEnumerationNanos += System.nanoTime() - began;
                } else {
                    nextSlot(drive);
                }
            }
            return true;
        }

        private void nextSlot(Drive drive) {
            index++;
            if (++slot == drive.slots()) {
                slot = 0;
                driveIndex++;
            }
        }

        void finish(ServerPlayer player, long tick) {
            List<Drive> drives = scan.drives();
            List<DriveCellListPacket.Group> groups = new ArrayList<>(drives.size());
            for (int i = 0; i < drives.size(); i++) {
                Drive drive = drives.get(i);
                String where = drive.block().getBlockPos().toShortString();
                if (drive.block().getLevel().dimension() != player.level().dimension()) {
                    where += " @" + drive.block().getLevel().dimension().location();
                }
                boolean editable = drive.block() instanceof IPriorityHost;
                int priority = editable ? ((IPriorityHost) drive.block()).getPriority() : 0;
                Component label = drive.block() instanceof Nameable nameable && nameable.getCustomName() != null
                        ? nameable.getCustomName()
                        : drive.block().getBlockState().getBlock().getName();
                groups.add(new DriveCellListPacket.Group(label, Component.literal(where),
                        drive.block().getBlockState().getBlock().asItem(), drive.slots(), priority,
                        editable, drive.host().isPowered(), used[i], capacity[i]));
            }
            if (statistics) statsTick = tick;
            if (searching || query.isEmpty()) {
                searchTick = tick;
                searchDirty = false;
            }
            metricSnapshots++;
            int editing = editingIndex(drives);
            if (grid != current || windowStart != start || totalSlots != count || truncated != scan.truncated()
                    || !sameHosts(hosts, drives) || !previousGroups.equals(groups)
                    || !sameRows(previousRows, rows) || !sameSlots(references, refs)
                    || previousSearchId != searchId || !previousMatches.equals(matches) || previousEditing != editing) {
                grid = current;
                publish(player, start, count, scan.truncated(), drives, groups, refs, rows, matches, editing);
            } else {
                references = List.copyOf(refs);
            }
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
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        Set<AEKey> normalizedKeys = normalized.isEmpty() ? Set.of() : new HashSet<>(keys);
        boolean changed = !query.equals(normalized) || !searchKeys.equals(normalizedKeys);
        boolean newId = searchId != id;
        searchId = id;
        if (changed) {
            query = normalized;
            searchKeys = normalizedKeys;
            scheduler.invalidate();
            job = null;
            searchDirty = true;
        } else if (newId) {
            // An identical query may reuse its work, but only the latest request id is published.
            scheduler.request(scheduler.requestedStart());
        }
    }

    private void invalidateContent() {
        scheduler.invalidate();
        job = null;
        searchDirty = true;
        statsTick = Long.MIN_VALUE;
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
            invalidateContent();
            request(player, scheduler.requestedStart());
            return;
        }
        if (!active(drive) || ref.slot() >= drive.inventory().getSlots()
                || !ItemStack.matches(ref.expected(), drive.inventory().getStackInSlot(ref.slot()))) {
            invalidateContent();
            request(player, scheduler.requestedStart());
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
            invalidateContent();
            request(player, scheduler.requestedStart());
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
            invalidateContent();
            request(player, scheduler.requestedStart());
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
                    || a.get(i).host() != b.get(i).host() || a.get(i).slots() != b.get(i).slots()
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
        scheduler.clear();
        job = null;
        topology = null;
        topologyGrid = null;
        topologyTick = statsTick = searchTick = Long.MIN_VALUE;
        searchDirty = true;
        searchId = 0;
        query = "";
        searchKeys = Set.of();
        previousSearchId = 0;
        previousMatches = List.of();
        previousEditing = -1;
    }
}
