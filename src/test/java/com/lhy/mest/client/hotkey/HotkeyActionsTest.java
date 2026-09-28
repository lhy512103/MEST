package com.lhy.mest.client.hotkey;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HotkeyActionsTest {

    @Test
    void presetIdsRoundTripAndRejectOtherIds() {
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(slot, HotkeyActions.presetIndex(HotkeyActions.presetId(slot)));
        }
        assertEquals(-1, HotkeyActions.presetIndex(HotkeyActions.CYCLE_PRESET));
        assertEquals(-1, HotkeyActions.presetIndex("trash"));
        assertEquals(-1, HotkeyActions.presetIndex("action:preset_x"));
    }
}
