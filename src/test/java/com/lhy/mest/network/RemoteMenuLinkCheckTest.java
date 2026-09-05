package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RemoteMenuLinkCheckTest {
    @Test
    void firstUseRequiresALiveLink() {
        assertFalse(new RemoteMenuLinkCheck().isLinked(0, () -> false));
        assertTrue(new RemoteMenuLinkCheck().isLinked(0, () -> true));
    }

    @Test
    void cachesUntilExactlyTwentyTicksAfterLastCheck() {
        var lease = new RemoteMenuLinkCheck();
        var calls = new AtomicInteger();
        for (long tick = 100; tick < 120; tick++) {
            assertTrue(lease.isLinked(tick, () -> calls.incrementAndGet() == 1));
        }
        assertEquals(1, calls.get());
        assertFalse(lease.isLinked(120, () -> calls.incrementAndGet() == 1));
        assertEquals(2, calls.get());
    }

    @Test
    void clockRollbackForcesRecheck() {
        var lease = new RemoteMenuLinkCheck();
        assertTrue(lease.isLinked(1000, () -> true));
        assertFalse(lease.isLinked(10, () -> false));
    }

    @Test
    void lostLinkCannotReviveTheSameMenu() {
        var lease = new RemoteMenuLinkCheck();
        assertTrue(lease.isLinked(100, () -> true));
        assertFalse(lease.isLinked(120, () -> false));
        assertFalse(lease.isLinked(140, () -> {
            throw new AssertionError("Revoked leases must not re-resolve the terminal");
        }));
        assertFalse(lease.isLinked(0, () -> true));
        assertTrue(new RemoteMenuLinkCheck().isLinked(140, () -> true));
    }
}
