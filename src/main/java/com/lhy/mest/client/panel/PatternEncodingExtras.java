package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.components.AbstractWidget;
import net.neoforged.fml.ModList;

import com.lhy.mest.compat.plus.PlusEncodingChromeFactory;
import com.lhy.mest.terminal.MESTMenu;

/** Optional processing-scale chrome. Implementations may live in the Plus compat package. */
public interface PatternEncodingExtras {
    static PatternEncodingExtras create(MESTMenu menu) {
        if (!ModList.get().isLoaded("extendedae_plus")) {
            return null;
        }
        return PlusEncodingChromeFactory.create(menu);
    }

    List<AbstractWidget> widgets();

    void layout(int bgX, int bgY, int encodeX, int encodeY, boolean visible, boolean processing);

    boolean scaleButtonsVisible();
}
