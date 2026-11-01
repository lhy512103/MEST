package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import appeng.client.gui.style.Blitter;

import com.lhy.mest.compat.extendedae.ExtendedAePatternAccessChrome;
import com.lhy.mest.network.PatternProviderLoc;

/** Pattern-access visuals whose public types never require an optional mod. */
public interface PatternAccessChrome {
    static PatternAccessChrome create() {
        return create(ModList.get().isLoaded("extendedae"));
    }

    /** Kept separate from ModList so the absent-mod class-loading path can be verified. */
    static PatternAccessChrome create(boolean extendedAeLoaded) {
        if (extendedAeLoaded) {
            return new ExtendedAePatternAccessChrome();
        }
        return new DefaultPatternAccessChrome();
    }

    Blitter buttonBackground(boolean hovered, boolean focused);

    Blitter searchModeIcon(boolean inputs, boolean outputs);

    /** Null means this installation has no provider-highlighting integration. */
    default ProviderHighlight createHighlight() {
        return null;
    }

    interface ProviderHighlight {
        Button button();

        void setTarget(PatternProviderLoc location);

        List<Component> tooltip();
    }
}
