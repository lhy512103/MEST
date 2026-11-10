package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RemoteMenuValidityPolicyTest {
    @Test
    void deniesOtherDimensionsUnloadedRemovedAndReplacedTargets() {
        Object level = new Object();
        Object target = new Object();
        assertTrue(RemoteMenuValidityPolicy.sameTarget(level, level, true, target, target, false));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(new Object(), level, true, target, target, false));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(level, level, false, target, target, false));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(level, level, true, target, new Object(), false));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(level, level, true, target, target, true));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(level, level, true, null, null, false));
        assertFalse(RemoteMenuValidityPolicy.sameTarget(null, null, true, target, target, false));
    }

    @Test
    void reauthorizesEveryCheckAndRevocationCannotFallBackToLocalValidity() {
        var policy = new RemoteMenuValidityPolicy();
        var checks = new AtomicInteger();
        var originalCalls = new AtomicInteger();
        assertTrue(policy.evaluate(() -> checks.incrementAndGet() < 2, () -> false, () -> true));
        assertFalse(policy.evaluate(() -> checks.incrementAndGet() < 2,
                () -> { originalCalls.incrementAndGet(); return true; },
                () -> { throw new AssertionError("revoked menu must not get distance exemption"); }));
        assertFalse(policy.evaluate(() -> { throw new AssertionError("lease must stay revoked"); },
                () -> { originalCalls.incrementAndGet(); return true; }, () -> true));
        assertEquals(2, checks.get());
        assertEquals(2, originalCalls.get());
    }

    @Test
    void originalNonDistanceFailureWinsEvenWhenAuthorized() {
        var policy = new RemoteMenuValidityPolicy();
        assertFalse(policy.evaluate(() -> true, () -> true, () -> false));
        assertFalse(policy.evaluate(() -> true, () -> true, () -> true));
    }

    @Test
    void authorizedCheckCallsOriginalExactlyOnceInsideBoundScope() {
        Object player = new Object();
        Object level = new Object();
        Object menu = new Object();
        var calls = new AtomicInteger();
        var policy = new RemoteMenuValidityPolicy();
        assertTrue(policy.evaluate(() -> {
            assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 7));
            return true;
        }, () -> { throw new AssertionError("must use scoped original"); },
                () -> RemoteMenuDistanceScope.evaluate(player, level, menu, 7, () -> {
                    calls.incrementAndGet();
                    return RemoteMenuDistanceScope.matches(player, level, menu, 7);
                })));
        assertEquals(1, calls.get());
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 7));
    }

    @Test
    void throwingOriginalRevokesAndCleansDistanceScope() {
        Object player = new Object();
        Object level = new Object();
        Object menu = new Object();
        var policy = new RemoteMenuValidityPolicy();
        assertThrows(IllegalStateException.class, () -> policy.evaluate(() -> true, () -> true,
                () -> RemoteMenuDistanceScope.evaluate(player, level, menu, 7, () -> {
                    throw new IllegalStateException("menu failure");
                })));
        assertFalse(RemoteMenuDistanceScope.matches(player, level, menu, 7));
        assertFalse(policy.evaluate(() -> true, () -> true, () -> true));
    }

    @Test
    void throwingAuthorizationNeverEntersDistanceScopeAndRevokes() {
        var policy = new RemoteMenuValidityPolicy();
        assertThrows(IllegalStateException.class, () -> policy.evaluate(() -> {
            throw new IllegalStateException("permission failure");
        }, () -> true, () -> { throw new AssertionError("must not exempt"); }));
        assertFalse(policy.evaluate(() -> true, () -> true, () -> true));
    }
}
