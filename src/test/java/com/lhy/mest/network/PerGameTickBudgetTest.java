package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerGameTickBudgetTest {
    @Test
    void repeatedCallbacksCannotResetQuotaWithinOneGameTick() {
        var budget = new PerGameTickBudget(2);

        assertTrue(budget.tryAcquire(100));
        assertTrue(budget.tryAcquire(100));
        assertFalse(budget.tryAcquire(100));
        assertFalse(budget.tryAcquire(100));
    }

    @Test
    void changedAuthoritativeTickResetsQuota() {
        var budget = new PerGameTickBudget(1);

        assertTrue(budget.tryAcquire(100));
        assertFalse(budget.tryAcquire(100));
        assertTrue(budget.tryAcquire(101));
        assertFalse(budget.tryAcquire(101));
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new PerGameTickBudget(0));
    }
}
