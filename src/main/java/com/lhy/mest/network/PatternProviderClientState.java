package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Bounded, transport-independent state machine for chunked pattern-provider updates.
 */
final class PatternProviderClientState<V, M> {
    static final int MAX_PROVIDERS = 1024;
    static final int MAX_INVENTORY_SIZE = 4096;
    static final int MAX_TOTAL_INVENTORY_SLOTS = 32768;
    static final int MAX_SLOTS_PER_CHUNK = 64;
    static final int MAX_CHUNKS_PER_UPDATE =
            (MAX_INVENTORY_SIZE + MAX_SLOTS_PER_CHUNK - 1) / MAX_SLOTS_PER_CHUNK;

    private static final int MAX_PENDING_UPDATES = 128;
    private static final int MAX_PENDING_SLOT_VALUES = MAX_TOTAL_INVENTORY_SLOTS;
    private static final int MAX_PENDING_PER_PROVIDER = 8;
    private static final int MAX_RETIRED_PROVIDER_IDS = 4096;
    private static final long MAX_PENDING_AGE_PACKETS = 2048;

    enum Operation {
        RESET,
        FULL,
        DELTA,
        REMOVE
    }

    enum Outcome {
        IGNORED,
        CHANGED,
        RESYNC_REQUIRED
    }

    interface ValueOps<V> {
        V copy(V value);

        boolean isEmpty(V value);

        boolean matches(V left, V right);
    }

    record Update<V, M>(
            int containerId,
            long epoch,
            Operation operation,
            long providerId,
            long revision,
            int chunkIndex,
            int chunkCount,
            int inventorySize,
            long sortOrder,
            @Nullable M metadata,
            Map<Integer, V> slots) {
        Update {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(slots, "slots");
        }
    }

    record Provider<V, M>(
            long epoch,
            long providerId,
            long revision,
            M metadata,
            int inventorySize,
            long sortOrder,
            Map<Integer, V> slots) {
    }

    record Result<V, M>(Outcome outcome, List<Provider<V, M>> providers) {
    }

    private final ValueOps<V> values;
    private final Map<Long, Provider<V, M>> providers = new HashMap<>();
    private final Map<PendingKey, PendingUpdate<V, M>> pending = new HashMap<>();
    private final Set<Long> retiredProviderIds = new HashSet<>();

    private int containerId = -1;
    private long epoch;
    private long packetSequence;
    private boolean awaitingReset;
    private boolean desynchronized;
    private boolean resyncReported;

    PatternProviderClientState(ValueOps<V> values) {
        this.values = Objects.requireNonNull(values);
    }

    void beginSession(int expectedContainerId) {
        beginSession(expectedContainerId, false);
    }

    void beginSession(int expectedContainerId, boolean continueExistingEpoch) {
        if (!continueExistingEpoch || containerId != expectedContainerId) {
            epoch = 0;
        }
        containerId = expectedContainerId;
        providers.clear();
        pending.clear();
        retiredProviderIds.clear();
        awaitingReset = true;
        desynchronized = false;
        resyncReported = false;
    }

    void endSession() {
        containerId = -1;
        epoch = 0;
        packetSequence = 0;
        providers.clear();
        pending.clear();
        retiredProviderIds.clear();
        awaitingReset = true;
        desynchronized = false;
        resyncReported = false;
    }

    Result<V, M> apply(Update<V, M> update) {
        packetSequence++;

        if (containerId != update.containerId()) {
            return ignored();
        }
        if (update.operation() == Operation.RESET) {
            return applyReset(update);
        }
        if (desynchronized) {
            return ignored();
        }
        if (epoch == 0 || awaitingReset) {
            return ignored();
        }
        if (epoch != update.epoch()) {
            return update.epoch() > epoch ? reject() : ignored();
        }
        if (expireIncompleteUpdates()) {
            return reject();
        }

        return switch (update.operation()) {
            case REMOVE -> applyRemove(update);
            case FULL, DELTA -> applyChunk(update);
            case RESET -> throw new IllegalStateException("reset handled above");
        };
    }

    private Result<V, M> applyReset(Update<V, M> update) {
        if (update.containerId() < 0 || update.epoch() <= 0 || update.providerId() != 0
                || update.revision() != 0 || update.chunkIndex() != 0 || update.chunkCount() != 1
                || update.inventorySize() != 0 || update.sortOrder() != 0
                || update.metadata() != null || !update.slots().isEmpty()) {
            return reject();
        }
        if (containerId == update.containerId() && epoch != 0 && update.epoch() <= epoch) {
            return ignored();
        }

        containerId = update.containerId();
        epoch = update.epoch();
        providers.clear();
        pending.clear();
        retiredProviderIds.clear();
        awaitingReset = false;
        desynchronized = false;
        resyncReported = false;
        return changed();
    }

    private Result<V, M> applyRemove(Update<V, M> update) {
        if (update.providerId() <= 0 || update.revision() != 0 || update.chunkIndex() != 0
                || update.chunkCount() != 1 || update.inventorySize() != 0
                || update.sortOrder() != 0 || update.metadata() != null || !update.slots().isEmpty()) {
            return reject();
        }

        boolean changed = providers.remove(update.providerId()) != null;
        changed |= pending.keySet().removeIf(key -> key.providerId() == update.providerId());
        if (retiredProviderIds.size() >= MAX_RETIRED_PROVIDER_IDS
                && !retiredProviderIds.contains(update.providerId())) {
            return reject();
        }
        retiredProviderIds.add(update.providerId());
        return changed ? changed() : ignored();
    }

    private Result<V, M> applyChunk(Update<V, M> update) {
        if (!validChunkEnvelope(update)) {
            return reject();
        }
        if (retiredProviderIds.contains(update.providerId())) {
            return ignored();
        }

        var current = providers.get(update.providerId());
        if (current != null && update.revision() <= current.revision()) {
            return ignored();
        }
        if (update.operation() == Operation.DELTA) {
            int knownInventorySize = current != null
                    ? current.inventorySize()
                    : pendingFullInventorySize(update.providerId(), update.revision());
            if (knownInventorySize <= 0
                    || update.chunkCount() > chunksForSlots(knownInventorySize)
                    || !slotsFit(update.slots(), knownInventorySize)) {
                return reject();
            }
        }
        if (hasConflictingOperation(update)) {
            return reject();
        }

        var key = new PendingKey(update.operation(), update.providerId(), update.revision());
        var accumulator = pending.get(key);
        if (accumulator == null) {
            if (pending.size() >= MAX_PENDING_UPDATES
                    || pendingForProvider(update.providerId()) >= MAX_PENDING_PER_PROVIDER) {
                return reject();
            }
            accumulator = new PendingUpdate<>(update, packetSequence, values);
            pending.put(key, accumulator);
        }
        if (!accumulator.accept(update)) {
            pending.remove(key);
            return reject();
        }
        if (totalPendingSlotValues() > MAX_PENDING_SLOT_VALUES) {
            return reject();
        }
        if (!accumulator.complete()) {
            return ignored();
        }

        if (update.operation() == Operation.FULL) {
            return applyCompletedFull(key, accumulator);
        }
        return applyCompletedDelta(key, accumulator);
    }

    private Result<V, M> applyCompletedFull(PendingKey key, PendingUpdate<V, M> update) {
        pending.remove(key);
        if (providers.size() >= MAX_PROVIDERS && !providers.containsKey(key.providerId())) {
            return reject();
        }
        var previous = providers.get(key.providerId());
        int totalInventorySlots = totalInventorySlots()
                - (previous == null ? 0 : previous.inventorySize())
                + update.inventorySize;
        if (totalInventorySlots > MAX_TOTAL_INVENTORY_SLOTS) {
            return reject();
        }
        if (!slotsFit(update.slots, update.inventorySize)) {
            return reject();
        }

        var nonEmpty = new HashMap<Integer, V>();
        for (var entry : update.slots.entrySet()) {
            if (!values.isEmpty(entry.getValue())) {
                nonEmpty.put(entry.getKey(), values.copy(entry.getValue()));
            }
        }
        providers.put(key.providerId(), new Provider<>(epoch, key.providerId(), key.revision(),
                Objects.requireNonNull(update.metadata), update.inventorySize, update.sortOrder,
                immutableSlots(nonEmpty)));
        discardObsoletePending(key.providerId(), key.revision());
        if (!drainCompletedDeltas(key.providerId())) {
            return reject();
        }
        return changed();
    }

    private Result<V, M> applyCompletedDelta(PendingKey key, PendingUpdate<V, M> update) {
        var current = providers.get(key.providerId());
        if (current == null) {
            return ignored();
        }
        if (key.revision() > current.revision() + 1) {
            if (!pending.containsKey(new PendingKey(Operation.DELTA, key.providerId(), current.revision() + 1))
                    && !hasPendingFull(key.providerId(), key.revision())) {
                pending.remove(key);
                return reject();
            }
            return ignored();
        }
        if (key.revision() <= current.revision()) {
            pending.remove(key);
            return ignored();
        }
        if (!applyDelta(key, update, current)) {
            pending.remove(key);
            return reject();
        }
        pending.remove(key);
        if (!drainCompletedDeltas(key.providerId())) {
            return reject();
        }
        return changed();
    }

    private boolean drainCompletedDeltas(long providerId) {
        while (true) {
            var current = providers.get(providerId);
            if (current == null) {
                return true;
            }
            var key = new PendingKey(Operation.DELTA, providerId, current.revision() + 1);
            var next = pending.get(key);
            if (next == null || !next.complete()) {
                return true;
            }
            if (!applyDelta(key, next, current)) {
                return false;
            }
            pending.remove(key);
        }
    }

    private boolean applyDelta(PendingKey key, PendingUpdate<V, M> update, Provider<V, M> current) {
        if (!slotsFit(update.slots, current.inventorySize())) {
            return false;
        }
        var slots = new HashMap<Integer, V>(current.slots());
        for (var entry : update.slots.entrySet()) {
            if (values.isEmpty(entry.getValue())) {
                slots.remove(entry.getKey());
            } else {
                slots.put(entry.getKey(), values.copy(entry.getValue()));
            }
        }
        providers.put(key.providerId(), new Provider<>(epoch, current.providerId(), key.revision(),
                current.metadata(), current.inventorySize(), current.sortOrder(), immutableSlots(slots)));
        discardObsoletePending(key.providerId(), key.revision());
        return true;
    }

    private boolean validChunkEnvelope(Update<V, M> update) {
        if (update.providerId() <= 0 || update.revision() <= 0
                || update.chunkCount() <= 0 || update.chunkCount() > MAX_CHUNKS_PER_UPDATE
                || update.chunkIndex() < 0 || update.chunkIndex() >= update.chunkCount()
                || update.slots().size() > MAX_SLOTS_PER_CHUNK) {
            return false;
        }

        if (update.operation() == Operation.FULL) {
            if (update.inventorySize() <= 0 || update.inventorySize() > MAX_INVENTORY_SIZE
                    || update.metadata() == null
                    || update.chunkCount() > chunksForSlots(update.inventorySize())) {
                return false;
            }
        } else if (update.inventorySize() != 0 || update.sortOrder() != 0 || update.metadata() != null) {
            return false;
        }

        for (var entry : update.slots().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getKey() < 0 || entry.getKey() >= MAX_INVENTORY_SIZE) {
                return false;
            }
            if (update.operation() == Operation.FULL && entry.getKey() >= update.inventorySize()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasConflictingOperation(Update<V, M> update) {
        for (var key : pending.keySet()) {
            if (key.providerId() == update.providerId() && key.revision() == update.revision()
                    && key.operation() != update.operation()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingFull(long providerId, long atMostRevision) {
        return pendingFullInventorySize(providerId, atMostRevision) > 0;
    }

    private int pendingFullInventorySize(long providerId, long atMostRevision) {
        long newestRevision = Long.MIN_VALUE;
        int inventorySize = -1;
        for (var key : pending.keySet()) {
            if (key.operation() == Operation.FULL && key.providerId() == providerId
                    && key.revision() <= atMostRevision && key.revision() > newestRevision) {
                newestRevision = key.revision();
                inventorySize = pending.get(key).inventorySize;
            }
        }
        return inventorySize;
    }

    private int pendingForProvider(long providerId) {
        int count = 0;
        for (var key : pending.keySet()) {
            if (key.providerId() == providerId) {
                count++;
            }
        }
        return count;
    }

    private int totalPendingSlotValues() {
        int count = 0;
        for (var update : pending.values()) {
            count += update.slots.size();
        }
        return count;
    }

    private int totalInventorySlots() {
        int count = 0;
        for (var provider : providers.values()) {
            count += provider.inventorySize();
        }
        return count;
    }

    private boolean expireIncompleteUpdates() {
        boolean expired = false;
        Iterator<PendingUpdate<V, M>> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            var update = iterator.next();
            if (packetSequence - update.firstPacketSequence > MAX_PENDING_AGE_PACKETS) {
                iterator.remove();
                expired = true;
            }
        }
        return expired;
    }

    private void discardObsoletePending(long providerId, long revision) {
        pending.keySet().removeIf(key -> key.providerId() == providerId && key.revision() <= revision);
    }

    private boolean slotsFit(Map<Integer, V> slots, int inventorySize) {
        if (slots.size() > inventorySize) {
            return false;
        }
        for (int slot : slots.keySet()) {
            if (slot < 0 || slot >= inventorySize) {
                return false;
            }
        }
        return true;
    }

    private Map<Integer, V> immutableSlots(Map<Integer, V> slots) {
        return Collections.unmodifiableMap(new HashMap<>(slots));
    }

    private static int chunksForSlots(int slots) {
        return Math.max(1, (slots + MAX_SLOTS_PER_CHUNK - 1) / MAX_SLOTS_PER_CHUNK);
    }

    private Result<V, M> reject() {
        pending.clear();
        desynchronized = true;
        if (!resyncReported) {
            resyncReported = true;
            return new Result<>(Outcome.RESYNC_REQUIRED, List.of());
        }
        return ignored();
    }

    private Result<V, M> changed() {
        return new Result<>(Outcome.CHANGED, new ArrayList<>(providers.values()));
    }

    private Result<V, M> ignored() {
        return new Result<>(Outcome.IGNORED, List.of());
    }

    private record PendingKey(Operation operation, long providerId, long revision) {
    }

    private static final class PendingUpdate<V, M> {
        private final Operation operation;
        private final int chunkCount;
        private final int inventorySize;
        private final long sortOrder;
        @Nullable
        private final M metadata;
        private final long firstPacketSequence;
        private final BitSet received;
        private final Map<Integer, Map<Integer, V>> chunks = new HashMap<>();
        private final Map<Integer, V> slots = new HashMap<>();
        private final ValueOps<V> values;

        private PendingUpdate(Update<V, M> update, long firstPacketSequence, ValueOps<V> values) {
            this.operation = update.operation();
            this.chunkCount = update.chunkCount();
            this.inventorySize = update.inventorySize();
            this.sortOrder = update.sortOrder();
            this.metadata = update.metadata();
            this.firstPacketSequence = firstPacketSequence;
            this.received = new BitSet(chunkCount);
            this.values = values;
        }

        private boolean accept(Update<V, M> update) {
            if (update.operation() != operation
                    || update.chunkCount() != chunkCount
                    || update.inventorySize() != inventorySize
                    || update.sortOrder() != sortOrder
                    || !Objects.equals(update.metadata(), metadata)) {
                return false;
            }

            var previousChunk = chunks.get(update.chunkIndex());
            if (previousChunk != null) {
                return sameSlots(previousChunk, update.slots());
            }
            for (int slot : update.slots().keySet()) {
                if (slots.containsKey(slot)) {
                    return false;
                }
            }

            var chunkCopy = new HashMap<Integer, V>();
            for (var entry : update.slots().entrySet()) {
                var copy = values.copy(entry.getValue());
                chunkCopy.put(entry.getKey(), copy);
                slots.put(entry.getKey(), values.copy(copy));
            }
            chunks.put(update.chunkIndex(), chunkCopy);
            received.set(update.chunkIndex());
            return true;
        }

        private boolean sameSlots(Map<Integer, V> left, Map<Integer, V> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (var entry : left.entrySet()) {
                var other = right.get(entry.getKey());
                if (other == null || !values.matches(entry.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }

        private boolean complete() {
            return received.cardinality() == chunkCount;
        }
    }
}
