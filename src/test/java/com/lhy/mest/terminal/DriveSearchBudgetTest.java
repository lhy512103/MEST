package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DriveSearchBudgetTest {
    @Test
    void enforcesIndependentCellAndKeyLimits() {
        var budget = new DriveSearchBudget(16, 256);
        for (int i = 0; i < 16; i++) assertTrue(budget.cell());
        assertFalse(budget.cell());
        for (int i = 0; i < 256; i++) assertTrue(budget.key());
        assertFalse(budget.key());
        assertFalse(budget.cell());
        assertFalse(budget.key());
    }

    @Test
    void hugeCellRequiresMultipleTicksInsteadOfUnboundedKeyLoop() {
        int remaining = 100_000;
        int ticks = 0;
        while (remaining > 0) {
            var budget = new DriveSearchBudget(16, 256);
            int visited = 0;
            while (remaining > 0 && budget.key()) {
                remaining--;
                visited++;
            }
            assertTrue(visited <= 256);
            ticks++;
        }
        assertEquals(391, ticks);
    }

    @Test
    void rejectsNonPositiveQuotas() {
        assertThrows(IllegalArgumentException.class, () -> new DriveSearchBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DriveSearchBudget(1, 0));
    }
}
