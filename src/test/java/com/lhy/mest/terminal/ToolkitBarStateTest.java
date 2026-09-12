package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.lhy.mest.terminal.ToolkitBarState.Bar;

class ToolkitBarStateTest {

    @Test
    void visibleIndexMapsPagesOntoThe27CellRing() {
        assertEquals(0, ToolkitBarState.toIndex(Bar.LEFT, 0));
        assertEquals(8, ToolkitBarState.toIndex(Bar.LEFT, 8));
        assertEquals(9, ToolkitBarState.toIndex(Bar.CENTER, 0));
        assertEquals(17, ToolkitBarState.toIndex(Bar.CENTER, 8));
        assertEquals(18, ToolkitBarState.toIndex(Bar.RIGHT, 0));
        assertEquals(26, ToolkitBarState.toIndex(Bar.RIGHT, 8));
    }

    @Test
    void barAndSlotOfRoundTrip() {
        for (int index = 0; index < ToolkitBarState.VISIBLE_CELLS; index++) {
            Bar bar = ToolkitBarState.barOf(index);
            int slot = ToolkitBarState.slotOf(index);
            assertEquals(index, ToolkitBarState.toIndex(bar, slot));
        }
    }

    @Test
    void toolkitIndexSkipsTheVanillaPage() {
        assertEquals(0, ToolkitBarState.toolkitIndex(Bar.LEFT, 0));
        assertEquals(8, ToolkitBarState.toolkitIndex(Bar.LEFT, 8));
        assertEquals(-1, ToolkitBarState.toolkitIndex(Bar.CENTER, 3));
        assertEquals(9, ToolkitBarState.toolkitIndex(Bar.RIGHT, 0));
        assertEquals(17, ToolkitBarState.toolkitIndex(Bar.RIGHT, 8));
    }

    @Test
    void cycleIndexWalksTheWholeRing() {
        assertEquals(26, ToolkitBarState.cycleIndex(0, 1));
        assertEquals(1, ToolkitBarState.cycleIndex(0, -1));
        assertEquals(9, ToolkitBarState.cycleIndex(8, -1));
        assertEquals(8, ToolkitBarState.cycleIndex(9, 1));
    }
}
