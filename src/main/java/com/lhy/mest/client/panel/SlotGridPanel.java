package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import com.lhy.mest.client.dock.ModulePanel;

/**
 * Generic fixed-size slot panel for menu-owned slots that do not need special client logic.
 */
public class SlotGridPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int GAP = 2;

    private final String id;
    private final Component title;
    private final List<Slot> slots;
    private final int columns;

    public SlotGridPanel(String id, Component title, List<Slot> slots, int columns) {
        this.id = id;
        this.title = title;
        this.slots = slots;
        this.columns = Math.max(1, columns);
        for (Slot slot : slots) {
            registerSlot(slot);
        }
    }

    public boolean hasSlots() {
        return !slots.isEmpty();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Component title() {
        return title;
    }

    @Override
    public int defaultWidth() {
        int cols = Math.min(columns, Math.max(1, slots.size()));
        return 2 * CONTENT_PADDING + cols * SLOT + Math.max(0, cols - 1) * GAP;
    }

    @Override
    public int defaultHeight() {
        int rows = rows();
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + rows * SLOT + Math.max(0, rows - 1) * GAP;
    }

    @Override
    public int minWidth() {
        return defaultWidth();
    }

    @Override
    public int minHeight() {
        return defaultHeight();
    }

    @Override
    public void layoutSlots() {
        if (!visible) {
            for (Slot slot : ownedSlots()) {
                slot.x = -9999;
                slot.y = -9999;
            }
            return;
        }

        int left = contentLeft();
        int top = contentTop();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            slot.x = left + (i % columns) * (SLOT + GAP) + 1;
            slot.y = top + (i / columns) * (SLOT + GAP) + 1;
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        for (Slot slot : slots) {
            ModulePanel.drawSlot(g, slot.x - 1, slot.y - 1);
        }
    }

    private int rows() {
        return Math.max(1, (slots.size() + columns - 1) / columns);
    }
}
