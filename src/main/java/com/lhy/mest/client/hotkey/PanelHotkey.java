package com.lhy.mest.client.hotkey;

import org.lwjgl.glfw.GLFW;

/**
 * A key plus modifiers that opens one terminal panel.
 *
 * @param key          GLFW key code, or {@link GLFW#GLFW_KEY_UNKNOWN} when unbound
 * @param modifiers    any of {@link #CTRL}, {@link #SHIFT}, {@link #ALT}
 * @param terminalOnly only react while the terminal screen is open; otherwise it also opens the
 *                     terminal from the world
 */
public record PanelHotkey(int key, int modifiers, boolean terminalOnly) {
    public static final int SHIFT = GLFW.GLFW_MOD_SHIFT;
    public static final int CTRL = GLFW.GLFW_MOD_CONTROL;
    public static final int ALT = GLFW.GLFW_MOD_ALT;
    private static final int MODIFIER_MASK = SHIFT | CTRL | ALT;

    public static final PanelHotkey UNBOUND = new PanelHotkey(GLFW.GLFW_KEY_UNKNOWN, 0, true);

    public PanelHotkey {
        modifiers &= MODIFIER_MASK;
        if (key == GLFW.GLFW_KEY_UNKNOWN) {
            modifiers = 0;
        }
    }

    public boolean isBound() {
        return key != GLFW.GLFW_KEY_UNKNOWN;
    }

    /** Exact match: Ctrl+K does not fire a plain K binding, and K does not fire Ctrl+K. */
    public boolean matches(int pressedKey, int pressedModifiers) {
        return isBound() && key == pressedKey && modifiers == (pressedModifiers & MODIFIER_MASK);
    }

    public boolean sameCombo(PanelHotkey other) {
        return isBound() && key == other.key && modifiers == other.modifiers;
    }

    public PanelHotkey withCombo(int newKey, int newModifiers) {
        return new PanelHotkey(newKey, newModifiers, terminalOnly);
    }

    public PanelHotkey withTerminalOnly(boolean value) {
        return new PanelHotkey(key, modifiers, value);
    }

    public PanelHotkey cleared() {
        return new PanelHotkey(GLFW.GLFW_KEY_UNKNOWN, 0, terminalOnly);
    }

    /** Pressing a modifier alone never completes a combo; the capture waits for the real key. */
    public static boolean isModifierKey(int key) {
        return key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT
                || key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER;
    }
}
