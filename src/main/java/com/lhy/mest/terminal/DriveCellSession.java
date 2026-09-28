package com.lhy.mest.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.StorageCells;
import appeng.blockentity.AEBaseInvBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;

import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;

/** Menu-scoped server snapshot. No remote slots are added to the fixed terminal container. */
public final class DriveCellSession {
    private static final int MAX_DRIVES = 256;
    private static final int MAX_SLOTS_PER_DRIVE = 128;
    private static final long MIN_REQUEST_TICKS = 5;

    private record Drive(IGridNode node, BlockEntity block, IItemHandler inventory, int slots) {
    }

    private record SlotRef(Drive drive, int slot, ItemStack expected) {
    }

    private record Scan(List<Drive> drives, boolean truncated) {
    }

    private final MESTMenu menu;
    private IGrid grid;
    private int revision;
    private int page = -1;
    private int totalSlots;
    private int totalDrives;
    private boolean truncated;
    private long lastRequestTick = -1;
    private List<SlotRef> references = List.of();
    private List<DriveCellListPacket.Row> previousRows = List.of();

    public DriveCellSession(MESTMenu menu) {
        this.menu = menu;
    }

    public void request(ServerPlayer player, int requestedPage) {
        if (requestedPage < 0) {
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
            if (page != requestedPage || totalSlots != 0 || !previousRows.isEmpty()) {
                grid = null;
                publish(player, requestedPage, 0, 0, false, List.of(), List.of());
            }
            return;
        }
        Scan scan = discover(current);
        List<Drive> drives = scan.drives();
        int count = 0;
        for (Drive drive : drives) {
            count += drive.slots();
        }
        boolean limited = scan.truncated();
        int targetPage = count == 0 ? 0 : Math.min(requestedPage, (count - 1) / DriveCellListPacket.PAGE_SIZE);
        int start = targetPage * DriveCellListPacket.PAGE_SIZE;
        int end = Math.min(count, start + DriveCellListPacket.PAGE_SIZE);
        List<SlotRef> nextRefs = new ArrayList<>(end - start);
        List<DriveCellListPacket.Row> rows = new ArrayList<>(end - start);
        int index = 0;
        for (int driveIndex = 0; driveIndex < drives.size() && index < end; driveIndex++) {
            Drive drive = drives.get(driveIndex);
            Component label = drive.block().getBlockState().getBlock().getName().copy().append(" (")
                    .append(drive.block().getBlockPos().toShortString()).append(")");
            if (drive.block().getLevel().dimension() != player.level().dimension()) {
                label = label.copy().append(" @" + drive.block().getLevel().dimension().location());
            }
            for (int slot = 0; slot < drive.slots() && index < end; slot++, index++) {
                if (index < start) {
                    continue;
                }
                ItemStack stack = drive.inventory().getStackInSlot(slot).copy();
                nextRefs.add(new SlotRef(drive, slot, stack));
                // Only the item icon travels over the network: a filled storage cell's data
                // components may contain a huge inventory and must never enter a GUI page packet.
                ItemStack icon = stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem());
                rows.add(new DriveCellListPacket.Row(driveIndex, label, slot, icon));
            }
        }
        if (grid != current || page != targetPage || totalSlots != count || totalDrives != drives.size()
                || truncated != limited || !sameHosts(references, nextRefs) || !sameRows(previousRows, rows)) {
            grid = current;
            publish(player, targetPage, count, drives.size(), limited, nextRefs, rows);
        } else {
            // Cell contents can change without changing their item icon; keep the action guard
            // current without sending redundant pages or serializing the cell's stored items.
            references = List.copyOf(nextRefs);
        }
    }

    public void act(ServerPlayer player, int expectedRevision, int row, DriveCellActionPacket.Action action) {
        if (expectedRevision != revision || row < 0 || row >= references.size() || grid == null
                || connectedGrid(player) != grid) {
            return;
        }
        SlotRef ref = references.get(row);
        Drive drive = ref.drive();
        if (!drive.node().isActive() || drive.node().getGrid() != grid || !live(drive.block())
                || ref.slot() >= drive.inventory().getSlots()
                || !ItemStack.matches(ref.expected(), drive.inventory().getStackInSlot(ref.slot()))) {
            lastRequestTick = -1;
            request(player, page);
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
            request(player, page);
        }
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
                // Public capability fallback for other drive implementations. Only accept a handler
                // with exactly the declared cell slots, never an unrelated upgrades/tool inventory.
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
            if (result.size() == MAX_DRIVES) {
                limited = true;
                break;
            }
            if (count > MAX_SLOTS_PER_DRIVE) {
                limited = true;
            }
            result.add(new Drive(node, block, inventory, slots));
        }
        result.sort(Comparator.comparing((Drive drive) -> drive.block().getLevel().dimension().location().toString())
                .thenComparingLong(drive -> drive.block().getBlockPos().asLong()));
        return new Scan(result, limited);
    }

    private static boolean live(BlockEntity block) {
        if (!(block.getLevel() instanceof ServerLevel level) || block.isRemoved()) {
            return false;
        }
        BlockPos pos = block.getBlockPos();
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) == block;
    }

    private void publish(ServerPlayer player, int newPage, int count, int drives, boolean limited,
            List<SlotRef> refs, List<DriveCellListPacket.Row> rows) {
        revision = (revision + 1) & Integer.MAX_VALUE;
        page = newPage;
        totalSlots = count;
        totalDrives = drives;
        truncated = limited;
        references = List.copyOf(refs);
        previousRows = List.copyOf(rows);
        PacketDistributor.sendToPlayer(player, new DriveCellListPacket(menu.containerId, revision, page,
                totalSlots, totalDrives, truncated, previousRows));
    }

    private static boolean sameHosts(List<SlotRef> a, List<SlotRef> b) {
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
                    || !before.name().equals(after.name()) || !ItemStack.matches(before.stack(), after.stack())) {
                return false;
            }
        }
        return true;
    }

    public void clear() {
        grid = null;
        page = -1;
        references = List.of();
        previousRows = List.of();
        totalSlots = 0;
        totalDrives = 0;
        lastRequestTick = -1;
    }
}
