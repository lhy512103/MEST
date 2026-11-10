package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class RemoteMenuDistanceScopeTest {
    private final Object player = new Object();
    private final Object level = new Object();
    private final Object menu = new Object();

    @Test
    void onlyTheExactPlayerLevelMenuAndPositionAreExempt() {
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
        assertTrue(RemoteMenuDistanceScope.evaluate(player, level, menu, 42, () -> {
            assertTrue(RemoteMenuDistanceScope.matches(player, level, menu, 42));
            assertFalse(RemoteMenuDistanceScope.matches(new Object(), level, menu, 42));
            assertFalse(RemoteMenuDistanceScope.matches(player, new Object(), menu, 42));
            assertFalse(RemoteMenuDistanceScope.matches(player, level, new Object(), 42));
            assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 43));
            return true;
        }));
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
    }

    @Test
    void preservesNonDistanceFailureAndAlwaysCleansUp() {
        assertFalse(RemoteMenuDistanceScope.evaluate(player, level, menu, 42, () -> false));
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
        assertThrows(IllegalStateException.class,
                () -> RemoteMenuDistanceScope.evaluate(player, level, menu, 42, () -> {
                    throw new IllegalStateException("custom stillValid failed");
                }));
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
    }

    @Test
    void nestedScopeRestoresOnlyItsPreviousBindingEvenOnException() {
        RemoteMenuDistanceScope.evaluate(player, level, menu, 42, () -> {
            assertThrows(IllegalStateException.class,
                    () -> RemoteMenuDistanceScope.evaluate(player, level, menu, 43, () -> {
                        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
                        assertTrue(RemoteMenuDistanceScope.matches(player, level, menu, 43));
                        throw new IllegalStateException("nested");
                    }));
            assertTrue(RemoteMenuDistanceScope.matches(player, level, menu, 42));
            assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 43));
            return true;
        });
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 42));
    }

    @Test
    void exemptionDoesNotEscapeToAnotherThread() {
        RemoteMenuDistanceScope.evaluate(player, level, menu, 42, () -> {
            AtomicBoolean leaked = new AtomicBoolean(true);
            Thread thread = new Thread(() -> leaked.set(RemoteMenuDistanceScope.matches(player, level, menu, 42)));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertFalse(leaked.get());
            return true;
        });
    }
}
