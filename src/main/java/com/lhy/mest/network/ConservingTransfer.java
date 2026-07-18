package com.lhy.mest.network;

import java.util.Objects;

/** Pure transaction policy used by pattern-slot inventory adapters. */
final class ConservingTransfer<S> {
    interface StackOps<S> {
        S empty();

        boolean isEmpty(S stack);

        S copy(S stack);

        int count(S stack);

        S withCount(S stack, int count);

        boolean sameKind(S left, S right);

        boolean matches(S left, S right);
    }

    interface Slot<S> {
        S get();

        S extract(int amount, boolean simulate);

        S insert(S stack, boolean simulate);

        void set(S stack);
    }

    interface Destination<S> {
        S simulateAdd(S stack);

        S add(S stack);
    }

    private final StackOps<S> stacks;

    ConservingTransfer(StackOps<S> stacks) {
        this.stacks = Objects.requireNonNull(stacks);
    }

    S exchange(Slot<S> slot, S carried) {
        var inSlot = slot.get();
        if (stacks.isEmpty(carried)) {
            return stacks.isEmpty(inSlot)
                    ? stacks.empty()
                    : slot.extract(stacks.count(inSlot), false);
        }

        if (stacks.isEmpty(inSlot)) {
            return slot.insert(carried, false);
        }

        var originalHand = stacks.copy(carried);
        var originalSlot = stacks.copy(inSlot);
        var extracted = slot.extract(stacks.count(originalSlot), false);
        if (!stacks.matches(extracted, originalSlot)) {
            slot.set(originalSlot);
            return originalHand;
        }

        if (!stacks.isEmpty(slot.insert(originalHand, true))) {
            slot.set(originalSlot);
            return originalHand;
        }
        if (stacks.isEmpty(slot.insert(originalHand, false))) {
            return originalSlot;
        }

        slot.set(originalSlot);
        return originalHand;
    }

    int quickMove(Slot<S> source, Destination<S> destination) {
        var inSlot = source.get();
        if (stacks.isEmpty(inSlot)) {
            return 0;
        }

        var simulatedRemainder = destination.simulateAdd(inSlot);
        int plannedInsert = stacks.count(inSlot) - stacks.count(simulatedRemainder);
        if (plannedInsert <= 0 || plannedInsert > stacks.count(inSlot)) {
            return 0;
        }

        var originalSlot = stacks.copy(inSlot);
        var extracted = source.extract(plannedInsert, false);
        if (stacks.isEmpty(extracted)
                || stacks.count(extracted) != plannedInsert
                || !stacks.sameKind(extracted, originalSlot)) {
            source.set(originalSlot);
            return 0;
        }

        var actualRemainder = destination.add(extracted);
        if ((!stacks.isEmpty(actualRemainder) && !stacks.sameKind(actualRemainder, extracted))
                || stacks.count(actualRemainder) < 0
                || stacks.count(actualRemainder) > stacks.count(extracted)) {
            source.set(originalSlot);
            return 0;
        }
        int inserted = stacks.count(extracted) - stacks.count(actualRemainder);
        source.set(stacks.withCount(originalSlot, stacks.count(originalSlot) - inserted));
        return inserted;
    }
}
