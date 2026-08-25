package com.lhy.mest.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.Blitter;

import com.lhy.mest.MESplicedterminal;

/** 16×16 icons from {@code layout_preset_icons.png} and the spliced-terminal WUT icon. */
public final class MestGuiIcons {
    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "textures/guis/layout_preset_icons.png");
    public static final ResourceLocation SPLICED_TERMINAL = ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "textures/guis/spliced_terminal_icon.png");
    public static final int SIZE = 16;

    private MestGuiIcons() {
    }

    public static void blit(GuiGraphics graphics, int col, int row, int x, int y, int w, int h) {
        blit(graphics, col, row, x, y, w, h, 1.0F);
    }

    public static void blit(
            GuiGraphics graphics, int col, int row, int x, int y, int w, int h, float opacity) {
        var blitter = Blitter.texture(ATLAS, 256, 256)
                .src(col * SIZE, row * SIZE, SIZE, SIZE)
                .dest(x, y, w, h)
                .zOffset(3);
        if (opacity < 1.0F) {
            blitter.opacity(opacity);
        }
        blitter.blit(graphics);
    }

    public static void blitHalf(
            GuiGraphics graphics, int col, int row, boolean rightHalf, int x, int y, int w, int h) {
        int srcW = SIZE / 2;
        int u = col * SIZE + (rightHalf ? srcW : 0);
        Blitter.texture(ATLAS, 256, 256)
                .src(u, row * SIZE, srcW, SIZE)
                .dest(x, y, w, h)
                .zOffset(3)
                .blit(graphics);
    }

    public static void blitSplicedTerminal(GuiGraphics graphics, int x, int y, int w, int h) {
        Blitter.texture(SPLICED_TERMINAL, SIZE, SIZE)
                .src(0, 0, SIZE, SIZE)
                .dest(x, y, w, h)
                .blit(graphics);
    }

    public static boolean blitPanel(GuiGraphics graphics, String panelId, int x, int y, int w, int h) {
        return blitPanel(graphics, panelId, x, y, w, h, 1.0F);
    }

    public static boolean blitPanel(
            GuiGraphics graphics, String panelId, int x, int y, int w, int h, float opacity) {
        int col = panelColumn(panelId);
        if (col < 0) {
            return false;
        }
        blit(graphics, col, 0, x, y, w, h, opacity);
        return true;
    }

    private static int panelColumn(String panelId) {
        return switch (panelId) {
            case "me_list" -> 0;
            case "crafting" -> 1;
            case "crafting_terminal" -> 2;
            case "pattern_encoding" -> 3;
            case "pattern_cache" -> 4;
            case "wireless_settings" -> 5;
            case "provider_select" -> 6;
            default -> -1;
        };
    }
}
