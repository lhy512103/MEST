package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConservingTransferTest {
    private static final FakeStack EMPTY = new FakeStack("", 0);
    private static final ConservingTransfer<FakeStack> TRANSFER = new ConservingTransfer<>(new FakeStackOps());

    @Test
    void picksUpAnOccupiedSlotIntoAnEmptyHand() {
        var slot = new FakeSlot(new FakeStack("pattern-a", 1), 1);

        FakeStack carried = TRANSFER.exchange(slot, EMPTY);

        assertEquals(new FakeStack("pattern-a", 1), carried);
        assertEquals(EMPTY, slot.stack);
    }

    @Test
    void leavesRemainderInHandWhenPlacingIntoEmptySlot() {
        var slot = new FakeSlot(EMPTY, 1);

        FakeStack carried = TRANSFER.exchange(slot, new FakeStack("pattern-a", 3));

        assertEquals(new FakeStack("pattern-a", 2), carried);
        assertEquals(new FakeStack("pattern-a", 1), slot.stack);
    }

    @Test
    void swapsTwoAcceptedStacks() {
        var slot = new FakeSlot(new FakeStack("pattern-a", 1), 1);

        FakeStack carried = TRANSFER.exchange(slot, new FakeStack("pattern-b", 1));

        assertEquals(new FakeStack("pattern-a", 1), carried);
        assertEquals(new FakeStack("pattern-b", 1), slot.stack);
    }

    @Test
    void restoresBothSidesWhenReplacementDoesNotFit() {
        var slot = new FakeSlot(new FakeStack("pattern-a", 1), 0);

        FakeStack carried = TRANSFER.exchange(slot, new FakeStack("pattern-b", 1));

        assertEquals(new FakeStack("pattern-b", 1), carried);
        assertEquals(new FakeStack("pattern-a", 1), slot.stack);
    }

    @Test
    void fullDestinationDoesNotExtractSource() {
        var slot = new FakeSlot(new FakeStack("pattern-a", 4), 64);
        var destination = new FakeDestination(0);

        int moved = TRANSFER.quickMove(slot, destination);

        assertEquals(0, moved);
        assertEquals(new FakeStack("pattern-a", 4), slot.stack);
        assertEquals(EMPTY, destination.stack);
    }

    @Test
    void partialDestinationMovesOnlyItsAvailableCapacity() {
        var slot = new FakeSlot(new FakeStack("pattern-a", 4), 64);
        var destination = new FakeDestination(2);

        int moved = TRANSFER.quickMove(slot, destination);

        assertEquals(2, moved);
        assertEquals(new FakeStack("pattern-a", 2), slot.stack);
        assertEquals(new FakeStack("pattern-a", 2), destination.stack);
        assertEquals(4, slot.stack.count() + destination.stack.count());
    }

    private record FakeStack(String kind, int count) {
    }

    private static final class FakeStackOps implements ConservingTransfer.StackOps<FakeStack> {
        @Override
        public FakeStack empty() {
            return EMPTY;
        }

        @Override
        public boolean isEmpty(FakeStack stack) {
            return stack.count() <= 0;
        }

        @Override
        public FakeStack copy(FakeStack stack) {
            return stack;
        }

        @Override
        public int count(FakeStack stack) {
            return stack.count();
        }

        @Override
        public FakeStack withCount(FakeStack stack, int count) {
            return count <= 0 ? EMPTY : new FakeStack(stack.kind(), count);
        }

        @Override
        public boolean sameKind(FakeStack left, FakeStack right) {
            return left.kind().equals(right.kind());
        }

        @Override
        public boolean matches(FakeStack left, FakeStack right) {
            return left.equals(right);
        }
    }

    private static final class FakeSlot implements ConservingTransfer.Slot<FakeStack> {
        private FakeStack stack;
        private final int capacity;

        private FakeSlot(FakeStack stack, int capacity) {
            this.stack = stack;
            this.capacity = capacity;
        }

        @Override
        public FakeStack get() {
            return stack;
        }

        @Override
        public FakeStack extract(int amount, boolean simulate) {
            int extracted = Math.min(amount, stack.count());
            FakeStack result = extracted <= 0 ? EMPTY : new FakeStack(stack.kind(), extracted);
            if (!simulate) {
                stack = stack.count() == extracted
                        ? EMPTY
                        : new FakeStack(stack.kind(), stack.count() - extracted);
            }
            return result;
        }

        @Override
        public FakeStack insert(FakeStack incoming, boolean simulate) {
            if (incoming.count() <= 0
                    || (!stack.kind().isEmpty() && !stack.kind().equals(incoming.kind()))) {
                return incoming;
            }
            int inserted = Math.min(incoming.count(), Math.max(0, capacity - stack.count()));
            if (!simulate && inserted > 0) {
                stack = new FakeStack(incoming.kind(), stack.count() + inserted);
            }
            return incoming.count() == inserted
                    ? EMPTY
                    : new FakeStack(incoming.kind(), incoming.count() - inserted);
        }

        @Override
        public void set(FakeStack replacement) {
            stack = replacement;
        }
    }

    private static final class FakeDestination implements ConservingTransfer.Destination<FakeStack> {
        private final int capacity;
        private FakeStack stack = EMPTY;

        private FakeDestination(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public FakeStack simulateAdd(FakeStack incoming) {
            return remainder(incoming);
        }

        @Override
        public FakeStack add(FakeStack incoming) {
            FakeStack remainder = remainder(incoming);
            int inserted = incoming.count() - remainder.count();
            if (inserted > 0) {
                stack = new FakeStack(incoming.kind(), stack.count() + inserted);
            }
            return remainder;
        }

        private FakeStack remainder(FakeStack incoming) {
            int inserted = Math.min(incoming.count(), Math.max(0, capacity - stack.count()));
            return incoming.count() == inserted
                    ? EMPTY
                    : new FakeStack(incoming.kind(), incoming.count() - inserted);
        }
    }
}
