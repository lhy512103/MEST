package com.lhy.mest.compat.plus;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import com.extendedae_plus.util.GuiUtil;

/** Plus pattern-access visuals. Do not reference from always-linked methods. */
public final class PlusPatternAccess {
    private PlusPatternAccess() {
    }

    public static void drawSlotRainbowHighlight(GuiGraphics graphics, int x, int y) {
        GuiUtil.drawSlotRainbowHighlight(graphics, x, y);
    }

    public static String patternOutputText(ItemStack pattern) {
        String text = GuiUtil.getPatternOutputText(pattern);
        return text == null ? "" : text;
    }
}
