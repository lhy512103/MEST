package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.menu.SlotSemantics;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Floating panel that hosts the player's main inventory (9x3) and hotbar (9x1). It re-homes the menu's
 * existing player-inventory slots into its content area; resizing is fixed to the slot grid size.
 */
public class InventoryPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int COLS = 9;

    private final List<Slot> inventorySlots;
    private final List<Slot> hotbarSlots;

    public InventoryPanel(MESTMenu menu) {
        this.inventorySlots = menu.getSlots(SlotSemantics.PLAYER_INVENTORY);
        this.hotbarSlots = menu.getSlots(SlotSemantics.PLAYER_HOTBAR);
        for (Slot s : inventorySlots) {
            registerSlot(s);
        }
        for (Slot s : hotbarSlots) {
            registerSlot(s);
        }
    }

    @Override
    public String id() {
        return "inventory";
    }

    @Override
    public Component title() {
        return Component.translatable("container.inventory");
    }

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + COLS * SLOT;
    }

    @Override
    public int defaultHeight() {
        // 3 inventory rows + 1 hotbar row + a small gap.
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + 4 * SLOT + 4;
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
            // Move owned slots off-screen so a hidden panel cannot be interacted with.
            for (Slot s : ownedSlots()) {
                s.x = -9999;
                s.y = -9999;
            }
            return;
        }
        int left = contentLeft();
        int top = contentTop();
        for (int i = 0; i < inventorySlots.size(); i++) {
            Slot s = inventorySlots.get(i);
            s.x = left + (i % COLS) * SLOT;
            s.y = top + (i / COLS) * SLOT;
        }
        int hotbarTop = top + 3 * SLOT + 4;
        for (int i = 0; i < hotbarSlots.size(); i++) {
            Slot s = hotbarSlots.get(i);
            s.x = left + (i % COLS) * SLOT;
            s.y = hotbarTop;
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        // Draw slot background cells so empty slots are visible.
        drawSlotCells(g, inventorySlots);
        drawSlotCells(g, hotbarSlots);
    }

    private void drawSlotCells(GuiGraphics g, List<Slot> slots) {
        for (Slot s : slots) {
            // AE2 slot art's top-left is one pixel up-left of the 16x16 item area.
            ModulePanel.drawSlot(g, s.x - 1, s.y - 1);
        }
    }
}
