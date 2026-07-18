package com.lhy.mest.client.panel;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import com.lhy.mest.client.dock.ModulePanel;

/**
 * A simple resizable placeholder panel used to validate the docking engine with a second, non-slot
 * module: it has no menu slots, freely resizes, and draws a grid so resizing is visually obvious.
 * It will be replaced by a real feature module (equipment / crafting / etc.) in M4.
 */
public class PlaceholderPanel extends ModulePanel {
    private final String moduleId;
    private final Component title;

    public PlaceholderPanel(String moduleId, Component title) {
        this.moduleId = moduleId;
        this.title = title;
    }

    @Override
    public String id() {
        return moduleId;
    }

    @Override
    public Component title() {
        return title;
    }

    @Override
    public int defaultWidth() {
        return 120;
    }

    @Override
    public int defaultHeight() {
        return 90;
    }

    @Override
    public void layoutSlots() {
        // No slots owned.
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        int left = contentLeft();
        int top = contentTop();
        int right = left + contentWidth();
        int bottom = top + contentHeight();
        // Grid lines every 16px so resizing is visually obvious.
        for (int gx = left; gx <= right; gx += 16) {
            g.fill(gx, top, gx + 1, bottom, 0x40FFFFFF);
        }
        for (int gy = top; gy <= bottom; gy += 16) {
            g.fill(left, gy, right, gy + 1, 0x40FFFFFF);
        }
        // Size readout.
        g.drawString(font, contentWidth() + " x " + contentHeight(), left + 2, top + 2, COLOR_MUTED, false);
    }
}
