package com.lhy.mest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MestPinnedKeysCapTest {
    @Test
    void visibleColumnCapNeverDropsBelowOne() {
        MestPinnedKeysCap.setVisibleColumns(0);
        assertEquals(1, MestPinnedKeysCap.limit());
        MestPinnedKeysCap.setVisibleColumns(12);
        assertEquals(12, MestPinnedKeysCap.limit());
        MestPinnedKeysCap.resetToVanilla();
        assertEquals(9, MestPinnedKeysCap.limit());
    }
}
