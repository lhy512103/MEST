package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PatternEncodingAmountsTest {
    @Test
    void rejectsOverflowAndInvalidScale() {
        assertFalse(PatternEncodingAmounts.canScaleAmount(Long.MAX_VALUE, 2, false));
        assertFalse(PatternEncodingAmounts.canScaleAmount(1, 0, false));
        assertFalse(PatternEncodingAmounts.canScaleAmount(1, -2, true));
        assertFalse(PatternEncodingAmounts.canScaleAmount(1, Integer.MIN_VALUE, false));
        assertFalse(PatternEncodingAmounts.canScaleAmount(-1, 2, false));
        assertThrows(IllegalArgumentException.class,
                () -> PatternEncodingAmounts.scaledAmount(Long.MAX_VALUE, 2, false));
        assertThrows(IllegalArgumentException.class, () -> PatternEncodingAmounts.scaledAmount(5, 2, true));
    }

    @Test
    void scalesExactlyAtLongBoundary() {
        long amount = Long.MAX_VALUE / 2;
        assertTrue(PatternEncodingAmounts.canScaleAmount(amount, 2, false));
        assertEquals(Long.MAX_VALUE - 1, PatternEncodingAmounts.scaledAmount(amount, 2, false));
        assertFalse(PatternEncodingAmounts.canScaleAmount(amount + 1, 2, false));
        assertEquals(amount, PatternEncodingAmounts.scaledAmount(Long.MAX_VALUE - 1, 2, true));
        assertEquals(Long.MAX_VALUE, PatternEncodingAmounts.scaledAmount(Long.MAX_VALUE, 1, false));
    }

    @Test
    void scalesZeroAndRequiresExactDivision() {
        assertEquals(0, PatternEncodingAmounts.scaledAmount(0, 5, false));
        assertEquals(0, PatternEncodingAmounts.scaledAmount(0, 5, true));
        assertEquals(6, PatternEncodingAmounts.scaledAmount(30, 5, true));
        assertFalse(PatternEncodingAmounts.canScaleAmount(31, 5, true));
    }

    @Test
    void gcdRestoresRatio() {
        long divisor = PatternEncodingAmounts.gcd(24, 18);
        assertEquals(6, divisor);
        assertEquals(4, 24 / divisor);
        assertEquals(3, 18 / divisor);
        assertEquals(1, PatternEncodingAmounts.gcd(17, 19));
    }

    @Test
    void gcdHandlesZeroSignsAndLongBoundary() {
        assertEquals(0, PatternEncodingAmounts.gcd(0, 0));
        assertEquals(18, PatternEncodingAmounts.gcd(0, 18));
        assertEquals(24, PatternEncodingAmounts.gcd(24, 0));
        assertEquals(6, PatternEncodingAmounts.gcd(-24, -18));
        assertEquals(Long.MAX_VALUE, PatternEncodingAmounts.gcd(Long.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> PatternEncodingAmounts.gcd(Long.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> PatternEncodingAmounts.gcd(0, Long.MIN_VALUE));
    }
}
