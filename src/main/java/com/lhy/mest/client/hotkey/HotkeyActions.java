package com.lhy.mest.client.hotkey;

/** Hotkey ids that are layout actions rather than panel module ids. */
public final class HotkeyActions {
    public static final String CYCLE_PRESET = "action:cycle_preset";
    private static final String SELECT_PRESET = "action:preset_";

    private HotkeyActions() {
    }

    /** @param index zero-based preset slot */
    public static String presetId(int index) {
        return SELECT_PRESET + (index + 1);
    }

    /** Zero-based preset slot for a {@link #presetId} id, or -1. */
    public static int presetIndex(String id) {
        if (id == null || !id.startsWith(SELECT_PRESET)) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(SELECT_PRESET.length())) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
