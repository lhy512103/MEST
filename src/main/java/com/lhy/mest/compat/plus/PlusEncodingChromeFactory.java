package com.lhy.mest.compat.plus;

import com.lhy.mest.client.panel.PatternEncodingExtras;
import com.lhy.mest.terminal.MESTMenu;

public final class PlusEncodingChromeFactory {
    private PlusEncodingChromeFactory() {
    }

    public static PatternEncodingExtras create(MESTMenu menu) {
        return new PlusEncodingChrome(menu);
    }
}
