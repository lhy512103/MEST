package com.lhy.mest.client.panel;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;

/** Uses only the required AE2 dependency when ExtendedAE is absent. */
final class DefaultPatternAccessChrome implements PatternAccessChrome {
    @Override
    public Blitter buttonBackground(boolean hovered, boolean focused) {
        return (hovered ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : focused ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND).getBlitter();
    }

    @Override
    public Blitter searchModeIcon(boolean inputs, boolean outputs) {
        return (inputs && outputs ? Icon.ACCESS_READ_WRITE
                : inputs ? Icon.ARROW_DOWN : Icon.ARROW_UP).getBlitter();
    }
}
