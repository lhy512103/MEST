package com.lhy.mest.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.util.inv.FilteredInternalInventory;
import appeng.util.inv.PlayerInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;

import com.lhy.mest.terminal.MESTMenu;

/**
 * Server-owned state for the pattern access module of one open MEST menu.
 */
public final class PatternAccessSession {
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MIN_RESNAPSHOT_INTERVAL_TICKS = 20;
    private static final int MAX_ACTIONS_PER_TICK = 16;
    private static final int MAX_PACKETS_PER_TICK = 16;
    private static final int MAX_PENDING_PACKETS = 2048;
    private static final int MAX_TRACKED_PROVIDERS = PatternProviderClientState.MAX_PROVIDERS;
    private static final int MAX_PROVIDER_SLOTS = PatternProviderClientState.MAX_INVENTORY_SIZE;
    private static final int MAX_TOTAL_TRACKED_SLOTS = PatternProviderClientState.MAX_TOTAL_INVENTORY_SLOTS;

    private static final AtomicLong NEXT_EPOCH = new AtomicLong(1);
    private static final IAEItemFilter ENCODED_PATTERN_FILTER = new IAEItemFilter() {
        @Override
        public boolean allowExtract(InternalInventory inv, int slot, int amount) {
            return true;
        }

        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
        }
    };

    private final MESTMenu menu;
    private final IdentityHashMap<PatternContainer, Tracker> trackers = new IdentityHashMap<>();
    private final Map<Long, Tracker> trackersById = new java.util.HashMap<>();
    private final ArrayDeque<PatternProviderListPacket> outbound = new ArrayDeque<>();
    private final PerGameTickBudget serverTickBudget = new PerGameTickBudget(1);
    private final PerGameTickBudget actionBudget = new PerGameTickBudget(MAX_ACTIONS_PER_TICK);
    private final PerGameTickBudget packetBudget = new PerGameTickBudget(MAX_PACKETS_PER_TICK);

    private IGrid trackedGrid;
    private long epoch;
    private long nextProviderId = 1;
    private long ticks;
    private long lastSnapshotTick = -MIN_RESNAPSHOT_INTERVAL_TICKS;
    private boolean subscribed;
    private boolean snapshotRequired;
    private boolean queueInvalid;

    public PatternAccessSession(MESTMenu menu) {
        this.menu = Objects.requireNonNull(menu);
    }

    public void setSubscribed(ServerPlayer player, boolean subscribe) {
        if (menu.getPlayer() != player) {
            return;
        }
        if (!subscribe) {
            subscribed = false;
            clearServerState();
            return;
        }
        if (!subscribed) {
            subscribed = true;
            snapshotRequired = true;
        } else {
            requestResnapshot();
        }
    }

    public void close() {
        subscribed = false;
        clearServerState();
    }

    public void serverTick() {
        if (!(menu.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        long gameTick = player.serverLevel().getGameTime();
        if (!serverTickBudget.tryAcquire(gameTick)) {
            return;
        }
        ticks++;
        if (!subscribed) {
            return;
        }

        if (!menu.canUsePatternAccess(player)) {
            resetForUnavailable();
            flush(player, gameTick);
            return;
        }

        var currentGrid = getCurrentGrid();
        if (currentGrid == null) {
            resetForUnavailable();
            flush(player, gameTick);
            return;
        }

        if (trackedGrid != currentGrid) {
            // A fresh menu subscribes immediately. Re-subscribing after an unsubscribe or disconnect is
            // throttled so alternating request packets cannot force a full snapshot every server tick.
            if (resnapshotCoolingDown()) {
                snapshotRequired = true;
            } else {
                rebuildSnapshot(currentGrid);
            }
        } else if (snapshotRequired && ticks - lastSnapshotTick >= MIN_RESNAPSHOT_INTERVAL_TICKS) {
            rebuildSnapshot(currentGrid);
        } else if (!snapshotRequired && ticks % SCAN_INTERVAL_TICKS == 0) {
            refresh(currentGrid);
        }

        flush(player, gameTick);
    }

    public void handleAction(ServerPlayer player, PatternProviderActionPacket packet) {
        if (!subscribed
                || player.containerMenu != menu
                || menu.containerId != packet.containerId()
                || !menu.canUsePatternAccess(player)) {
            return;
        }
        long gameTick = player.serverLevel().getGameTime();
        if (!actionBudget.tryAcquire(gameTick)) {
            return;
        }

        var currentGrid = getCurrentGrid();
        if (currentGrid == null) {
            return;
        }
        if (trackedGrid != currentGrid) {
            if (resnapshotCoolingDown()) {
                requestResnapshot();
                flush(player, gameTick);
                return;
            }
            rebuildSnapshot(currentGrid);
        }
        if (packet.epoch() != epoch) {
            requestResnapshot();
            flush(player, gameTick);
            return;
        }

        var tracker = trackersById.get(packet.providerId());
        if (tracker == null || !refreshTrackerMetadata(tracker, currentGrid)) {
            refresh(currentGrid);
            requestResnapshot();
            flush(player, gameTick);
            return;
        }
        refreshTrackerContents(tracker);
        if (packet.revision() != tracker.revision) {
            requestResnapshot();
            flush(player, gameTick);
            return;
        }
        if (packet.providerSlot() < 0 || packet.providerSlot() >= tracker.inventory.size()) {
            return;
        }

        var patternSlot = new FilteredInternalInventory(
                tracker.inventory.getSlotInv(packet.providerSlot()), ENCODED_PATTERN_FILTER);
        switch (packet.action()) {
            case PICKUP_OR_SET_DOWN -> exchangeWithCarried(patternSlot);
            case QUICK_MOVE_TO_PLAYER -> quickMoveToPlayer(player, patternSlot);
        }

        refreshTrackerContents(tracker);
        flush(player, gameTick);
    }

    private void exchangeWithCarried(FilteredInternalInventory patternSlot) {
        var carried = menu.getCarried();
        if (!carried.isEmpty() && !PatternDetailsHelper.isEncodedPattern(carried)) {
            return;
        }
        menu.setCarried(PatternSlotTransactions.exchange(patternSlot, carried));
    }

    private static void quickMoveToPlayer(ServerPlayer player, FilteredInternalInventory patternSlot) {
        var playerInventory = new PlayerInternalInventory(player.getInventory());
        PatternSlotTransactions.quickMove(patternSlot, playerInventory);
    }

    private void resetForUnavailable() {
        if (trackedGrid == null && trackers.isEmpty() && !snapshotRequired) {
            return;
        }
        clearTrackingOnly();
        outbound.clear();
        queueInvalid = false;
        epoch = nextEpoch();
        queue(PatternProviderListPacket.reset(menu.containerId, epoch));
        snapshotRequired = false;
        lastSnapshotTick = ticks;
    }

    private void rebuildSnapshot(IGrid grid) {
        clearTrackingOnly();
        outbound.clear();
        queueInvalid = false;
        trackedGrid = grid;
        epoch = nextEpoch();
        nextProviderId = 1;
        queue(PatternProviderListPacket.reset(menu.containerId, epoch));

        for (var provider : collectProviders(grid)) {
            var tracker = new Tracker(nextProviderId++, provider.container(), provider.inventory(),
                    provider.group(), provider.sortOrder());
            trackers.put(provider.container(), tracker);
            trackersById.put(tracker.id, tracker);
            queueFull(tracker);
        }
        snapshotRequired = false;
        lastSnapshotTick = ticks;
    }

    private void refresh(IGrid grid) {
        var providers = collectProviders(grid);
        var seen = new IdentityHashMap<PatternContainer, Boolean>();
        for (var provider : providers) {
            seen.put(provider.container(), Boolean.TRUE);
            var tracker = trackers.get(provider.container());
            if (tracker == null) {
                tracker = new Tracker(nextProviderId++, provider.container(), provider.inventory(),
                        provider.group(), provider.sortOrder());
                trackers.put(provider.container(), tracker);
                trackersById.put(tracker.id, tracker);
                queueFull(tracker);
                continue;
            }

            if (tracker.inventory != provider.inventory()
                    || tracker.inventory.size() != provider.inventory().size()
                    || tracker.sortOrder != provider.sortOrder()
                    || !tracker.group.equals(provider.group())) {
                tracker.inventory = provider.inventory();
                tracker.group = provider.group();
                tracker.sortOrder = provider.sortOrder();
                tracker.snapshot = copyInventory(provider.inventory());
                tracker.bumpRevision();
                queueFull(tracker);
            } else {
                refreshTrackerContents(tracker);
            }
        }

        Iterator<Map.Entry<PatternContainer, Tracker>> iterator = trackers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!seen.containsKey(entry.getKey())) {
                var removed = entry.getValue();
                iterator.remove();
                trackersById.remove(removed.id);
                queue(PatternProviderListPacket.remove(menu.containerId, epoch, removed.id));
            }
        }
    }

    private boolean refreshTrackerMetadata(Tracker tracker, IGrid grid) {
        var container = tracker.container;
        if (container.getGrid() != grid || !container.isVisibleInTerminal()) {
            removeTracker(tracker);
            return false;
        }

        var inventory = container.getTerminalPatternInventory();
        var group = container.getTerminalGroup();
        if (inventory == null || group == null || inventory.size() <= 0 || inventory.size() > MAX_PROVIDER_SLOTS) {
            removeTracker(tracker);
            return false;
        }
        long sortOrder = container.getTerminalSortOrder();
        if (tracker.inventory != inventory
                || tracker.inventory.size() != inventory.size()
                || tracker.sortOrder != sortOrder
                || !tracker.group.equals(group)) {
            tracker.inventory = inventory;
            tracker.group = group;
            tracker.sortOrder = sortOrder;
            tracker.snapshot = copyInventory(inventory);
            tracker.bumpRevision();
            queueFull(tracker);
        }
        return true;
    }

    private void removeTracker(Tracker tracker) {
        trackers.remove(tracker.container);
        trackersById.remove(tracker.id);
        queue(PatternProviderListPacket.remove(menu.containerId, epoch, tracker.id));
    }

    private void refreshTrackerContents(Tracker tracker) {
        if (tracker.snapshot.length != tracker.inventory.size()) {
            tracker.snapshot = copyInventory(tracker.inventory);
            tracker.bumpRevision();
            queueFull(tracker);
            return;
        }

        var changed = new Int2ObjectArrayMap<ItemStack>();
        for (int slot = 0; slot < tracker.snapshot.length; slot++) {
            var current = tracker.inventory.getStackInSlot(slot);
            if (!ItemStack.matches(current, tracker.snapshot[slot])) {
                tracker.snapshot[slot] = current.copy();
                changed.put(slot, current.copy());
            }
        }
        if (!changed.isEmpty()) {
            tracker.bumpRevision();
            queueDelta(tracker, changed);
        }
    }

    private List<ProviderView> collectProviders(IGrid grid) {
        var providers = new ArrayList<ProviderView>();
        var seen = new IdentityHashMap<PatternContainer, Boolean>();

        outer:
        for (var machineClass : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(machineClass)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var containerClass = (Class<? extends PatternContainer>) machineClass;
            for (var container : grid.getActiveMachines(containerClass)) {
                if (providers.size() >= MAX_TRACKED_PROVIDERS) {
                    break outer;
                }
                if (container == null || seen.put(container, Boolean.TRUE) != null
                        || container.getGrid() != grid || !container.isVisibleInTerminal()) {
                    continue;
                }
                var inventory = container.getTerminalPatternInventory();
                var group = container.getTerminalGroup();
                if (inventory == null || group == null || inventory.size() <= 0
                        || inventory.size() > MAX_PROVIDER_SLOTS) {
                    continue;
                }
                providers.add(new ProviderView(container, inventory, group, container.getTerminalSortOrder()));
            }
        }

        providers.sort(Comparator
                .comparing((ProviderView provider) -> provider.group().name().getString(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(ProviderView::sortOrder));

        int totalSlots = 0;
        var bounded = new ArrayList<ProviderView>(providers.size());
        for (var provider : providers) {
            if (totalSlots + provider.inventory().size() > MAX_TOTAL_TRACKED_SLOTS) {
                break;
            }
            bounded.add(provider);
            totalSlots += provider.inventory().size();
        }
        return bounded;
    }

    private void queueFull(Tracker tracker) {
        var slots = new Int2ObjectArrayMap<ItemStack>();
        for (int slot = 0; slot < tracker.snapshot.length; slot++) {
            if (!tracker.snapshot[slot].isEmpty()) {
                slots.put(slot, tracker.snapshot[slot].copy());
            }
        }
        var chunks = chunk(slots);
        for (int i = 0; i < chunks.size(); i++) {
            queue(PatternProviderListPacket.full(menu.containerId, epoch, tracker.id, tracker.revision,
                    i, chunks.size(), tracker.inventory.size(), tracker.sortOrder, tracker.group, chunks.get(i)));
        }
    }

    private void queueDelta(Tracker tracker, Int2ObjectMap<ItemStack> changed) {
        var chunks = chunk(changed);
        for (int i = 0; i < chunks.size(); i++) {
            queue(PatternProviderListPacket.delta(menu.containerId, epoch, tracker.id, tracker.revision,
                    i, chunks.size(), chunks.get(i)));
        }
    }

    private static List<Int2ObjectMap<ItemStack>> chunk(Int2ObjectMap<ItemStack> source) {
        var result = new ArrayList<Int2ObjectMap<ItemStack>>();
        var current = new Int2ObjectArrayMap<ItemStack>();
        for (var entry : source.int2ObjectEntrySet()) {
            if (current.size() >= PatternProviderListPacket.MAX_SLOTS_PER_PACKET) {
                result.add(current);
                current = new Int2ObjectArrayMap<>();
            }
            current.put(entry.getIntKey(), entry.getValue().copy());
        }
        if (!current.isEmpty() || result.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private void queue(PatternProviderListPacket packet) {
        if (queueInvalid) {
            return;
        }
        if (outbound.size() >= MAX_PENDING_PACKETS) {
            outbound.clear();
            epoch = nextEpoch();
            outbound.add(PatternProviderListPacket.reset(menu.containerId, epoch));
            queueInvalid = true;
            snapshotRequired = true;
            return;
        }
        outbound.add(packet);
    }

    private void flush(ServerPlayer player, long gameTick) {
        while (!outbound.isEmpty() && packetBudget.tryAcquire(gameTick)) {
            PacketDistributor.sendToPlayer(player, outbound.removeFirst());
        }
    }

    private void requestResnapshot() {
        snapshotRequired = true;
    }

    private boolean resnapshotCoolingDown() {
        return trackedGrid == null
                && epoch != 0
                && ticks - lastSnapshotTick < MIN_RESNAPSHOT_INTERVAL_TICKS;
    }

    private IGrid getCurrentGrid() {
        var node = menu.getGridNode();
        return node != null && node.isActive() ? node.getGrid() : null;
    }

    private void clearServerState() {
        clearTrackingOnly();
        outbound.clear();
        queueInvalid = false;
        snapshotRequired = false;
    }

    private void clearTrackingOnly() {
        trackedGrid = null;
        trackers.clear();
        trackersById.clear();
        nextProviderId = 1;
    }

    private static ItemStack[] copyInventory(InternalInventory inventory) {
        var result = new ItemStack[inventory.size()];
        for (int slot = 0; slot < result.length; slot++) {
            result[slot] = inventory.getStackInSlot(slot).copy();
        }
        return result;
    }

    private static long nextEpoch() {
        long result = NEXT_EPOCH.getAndIncrement();
        return result > 0 ? result : NEXT_EPOCH.updateAndGet(value -> value > 0 ? value : 1);
    }

    private record ProviderView(PatternContainer container, InternalInventory inventory,
            PatternContainerGroup group, long sortOrder) {
    }

    private static final class Tracker {
        private final long id;
        private final PatternContainer container;
        private InternalInventory inventory;
        private PatternContainerGroup group;
        private long sortOrder;
        private long revision = 1;
        private ItemStack[] snapshot;

        private Tracker(long id, PatternContainer container, InternalInventory inventory,
                PatternContainerGroup group, long sortOrder) {
            this.id = id;
            this.container = container;
            this.inventory = inventory;
            this.group = group;
            this.sortOrder = sortOrder;
            this.snapshot = copyInventory(inventory);
        }

        private void bumpRevision() {
            revision = revision == Long.MAX_VALUE ? 1 : revision + 1;
        }
    }
}
