package com.lhy.mest.terminal;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PatternEncodingAmountsTest {
    @Test void rejectsOverflowAndInvalidScale() {
        assertFalse(PatternEncodingAmounts.canScaleAmount(Long.MAX_VALUE, 2, false));
        assertFalse(PatternEncodingAmounts.canScaleAmount(1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> PatternEncodingAmounts.scaledAmount(5, 2, true));
    }
    @Test void gcdRestoresRatio() { assertEquals(6, PatternEncodingAmounts.gcd(24, 18)); }
}
