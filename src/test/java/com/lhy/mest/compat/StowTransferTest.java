package com.lhy.mest.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class StowTransferTest {
    @Test
    void fullInsertionConsumesOnlyTheHeldCount() {
        var remaining = new AtomicInteger(64);
        assertTrue(StowTransfer.insert(64, amount -> {
            assertEquals(64, amount);
            return amount;
        }, remaining::set));
        assertEquals(0, remaining.get());
    }

    @Test
    void partialInsertionKeepsTheRemainder() {
        var remaining = new AtomicInteger(64);
        assertTrue(StowTransfer.insert(64, amount -> 13, remaining::set));
        assertEquals(51, remaining.get());
    }

    @Test
    void fullOrDeniedNetworkDoesNotChangeTheHand() {
        assertFalse(StowTransfer.insert(64, amount -> 0, count -> {
            throw new AssertionError("A failed insert must not change the held stack");
        }));
    }

    @Test
    void emptyHandDoesNotAccessStorage() {
        assertFalse(StowTransfer.insert(0, amount -> {
            throw new AssertionError("An empty hand must not access the network");
        }, count -> {
            throw new AssertionError("An empty hand must not be changed");
        }));
    }

    @Test
    void invalidStorageCountsCannotGrowOrUnderflowTheHand() {
        var remaining = new AtomicInteger(64);
        assertFalse(StowTransfer.insert(64, amount -> -1, remaining::set));
        assertEquals(64, remaining.get());
        assertTrue(StowTransfer.insert(64, amount -> Long.MAX_VALUE, remaining::set));
        assertEquals(0, remaining.get());
    }
}
