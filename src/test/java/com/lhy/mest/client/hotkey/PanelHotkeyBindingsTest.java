package com.lhy.mest.client.hotkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class PanelHotkeyBindingsTest {

    @Test
    void combosMatchExactlyAndIgnoreLockModifiers() {
        var ctrlK = new PanelHotkey(GLFW.GLFW_KEY_K, PanelHotkey.CTRL, true);

        assertTrue(ctrlK.matches(GLFW.GLFW_KEY_K, PanelHotkey.CTRL | GLFW.GLFW_MOD_NUM_LOCK));
        assertFalse(ctrlK.matches(GLFW.GLFW_KEY_K, 0));
        assertFalse(ctrlK.matches(GLFW.GLFW_KEY_K, PanelHotkey.CTRL | PanelHotkey.SHIFT));
        assertFalse(PanelHotkey.UNBOUND.matches(GLFW.GLFW_KEY_UNKNOWN, 0));
    }

    @Test
    void terminalOnlyBindingsAreSkippedInTheWorld() {
        var bindings = new PanelHotkeyBindings();
        bindings.set("trash", new PanelHotkey(GLFW.GLFW_KEY_T, PanelHotkey.ALT, true));
        bindings.set("toolkit", new PanelHotkey(GLFW.GLFW_KEY_G, PanelHotkey.ALT, false));

        assertEquals(Optional.of("trash"), bindings.match(GLFW.GLFW_KEY_T, PanelHotkey.ALT, false));
        assertEquals(Optional.empty(), bindings.match(GLFW.GLFW_KEY_T, PanelHotkey.ALT, true));
        assertEquals(Optional.of("toolkit"), bindings.match(GLFW.GLFW_KEY_G, PanelHotkey.ALT, true));
    }

    @Test
    void bindingAComboTakesItFromTheOtherPanelButKeepsItsFlag() {
        var bindings = new PanelHotkeyBindings();
        bindings.set("trash", new PanelHotkey(GLFW.GLFW_KEY_T, PanelHotkey.CTRL, false));
        bindings.set("toolkit", new PanelHotkey(GLFW.GLFW_KEY_T, PanelHotkey.CTRL, true));

        assertFalse(bindings.get("trash").isBound());
        assertFalse(bindings.get("trash").terminalOnly());
        assertEquals(Optional.of("toolkit"), bindings.match(GLFW.GLFW_KEY_T, PanelHotkey.CTRL, false));
    }

    @Test
    void roundTripsThroughJsonAndSkipsBrokenEntries() {
        var bindings = new PanelHotkeyBindings();
        bindings.set("me_list", new PanelHotkey(GLFW.GLFW_KEY_M, PanelHotkey.CTRL | PanelHotkey.SHIFT, false));

        var decoded = PanelHotkeyBindings.fromJson(bindings.toJson().toString());
        assertEquals(bindings.get("me_list"), decoded.get("me_list"));

        var partial = PanelHotkeyBindings.fromJson("""
                {"version": 1, "panels": {"broken": "x", "trash": {"key": 84}}}
                """);
        assertEquals(new PanelHotkey(GLFW.GLFW_KEY_T, 0, true), partial.get("trash"));
        assertFalse(partial.get("broken").isBound());
    }

    @Test
    void modifierKeysAloneNeverCompleteACombo() {
        assertTrue(PanelHotkey.isModifierKey(GLFW.GLFW_KEY_LEFT_CONTROL));
        assertTrue(PanelHotkey.isModifierKey(GLFW.GLFW_KEY_RIGHT_ALT));
        assertFalse(PanelHotkey.isModifierKey(GLFW.GLFW_KEY_A));
    }
}
