package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpliceModeTest {
    @Test
    void cyclesUnifiedCompactShell() {
        assertEquals(SpliceMode.COMPACT, SpliceMode.UNIFIED.next());
        assertEquals(SpliceMode.SHELL, SpliceMode.COMPACT.next());
        assertEquals(SpliceMode.UNIFIED, SpliceMode.SHELL.next());
    }

    @Test
    void mapsLegacyAndUnknownIds() {
        assertEquals(SpliceMode.UNIFIED, SpliceMode.fromId(null));
        assertEquals(SpliceMode.UNIFIED, SpliceMode.fromId(""));
        assertEquals(SpliceMode.UNIFIED, SpliceMode.fromId("unknown"));
        assertEquals(SpliceMode.COMPACT, SpliceMode.fromId("compact"));
        assertEquals(SpliceMode.SHELL, SpliceMode.fromId("SHELL"));
    }

    @Test
    void compactIsTheOnlyModeThatPacksAContour() {
        assertFalse(SpliceMode.UNIFIED.packsContour());
        assertTrue(SpliceMode.COMPACT.packsContour());
        assertFalse(SpliceMode.SHELL.packsContour());
        assertTrue(SpliceMode.SHELL.prefersCompactBounds());
        assertTrue(SpliceMode.SHELL.drawsOuterShell());
        assertFalse(SpliceMode.SHELL.drawsPerLeafFrames());
    }
}
