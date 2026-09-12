package com.lhy.mest.client.dock;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.style.Blitter;
import appeng.core.localization.GuiText;
import appeng.menu.slot.AppEngSlot;

/**
 * Vanilla AE2 network-tool 3×3, hung off the anchored group's bottom-right.
 * Background is the same {@code extra_panels.png} sprite {@code ToolboxPanel} uses.
 */
public class ToolboxChrome implements ExtraChrome {
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int WIDTH = 59;
    private static final int HEIGHT = 66;
    private static final int WELL_X = 2;
    private static final int WELL_Y = 7;
    private static final Blitter BACKGROUND = Blitter.texture("guis/extra_panels.png", 128, 128)
            .src(69, 62, WIDTH, HEIGHT);

    private final List<Slot> slots;
    private final Component toolName;
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    public ToolboxChrome(List<Slot> slots, Component toolName) {
        this.slots = slots;
        this.toolName = toolName;
    }

    @Override
    public boolean hasSlots() {
        return enabledSlotCount() > 0;
    }

    @Override
    public boolean isVisible() {
        return bounds.getWidth() > 0 && bounds.getHeight() > 0;
    }

    @Override
    public Rect2i bounds() {
        return bounds;
    }

    @Override
    public int nextColumnX() {
        return bounds.getX() + WIDTH - 2;
    }

    @Override
    public boolean contains(double mx, double my) {
        return isVisible()
                && mx >= bounds.getX()
                && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY()
                && my < bounds.getY() + bounds.getHeight();
    }

    @Override
    public boolean ownsSlot(Slot slot) {
        for (Slot owned : slots) {
            if (owned == slot) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void layoutAgainst(int attachX, int attachY, boolean show) {
        if (!show || !hasSlots()) {
            hide();
            return;
        }
        bounds = new Rect2i(attachX, attachY, WIDTH, HEIGHT);
        int index = 0;
        for (Slot slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot && !appEngSlot.isSlotEnabled()) {
                slot.x = -9999;
                slot.y = -9999;
                continue;
            }
            int col = index % COLS;
            int row = index / COLS;
            slot.x = attachX + WELL_X + col * SLOT + 1;
            slot.y = attachY + WELL_Y + row * SLOT + 1;
            index++;
        }
    }

    @Override
    public void hide() {
        bounds = new Rect2i(0, 0, 0, 0);
        for (Slot slot : slots) {
            slot.x = -9999;
            slot.y = -9999;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (!isVisible()) {
            return;
        }
        BACKGROUND.dest(bounds.getX(), bounds.getY(), WIDTH, HEIGHT).blit(g);
    }

    @Override
    public void renderSlots(GuiGraphics g, ModulePanel.PanelSlotRenderer renderer) {
        if (!isVisible()) {
            return;
        }
        for (Slot slot : slots) {
            if (slot.x <= -1000 || slot.y <= -1000) {
                continue;
            }
            renderer.drawPanelSlot(g, slot);
        }
    }

    @Override
    public List<Component> chromeTooltipAt(double mx, double my) {
        if (!contains(mx, my)) {
            return List.of();
        }
        for (Slot slot : slots) {
            if (slot.x <= -1000 || slot.y <= -1000) {
                continue;
            }
            if (mx >= slot.x - 1 && mx < slot.x + 17 && my >= slot.y - 1 && my < slot.y + 17
                    && !slot.getItem().isEmpty()) {
                return List.of();
            }
        }
        return List.of(
                toolName,
                GuiText.UpgradeToolbelt.text().plainCopy().withStyle(ChatFormatting.GRAY));
    }

    private int enabledSlotCount() {
        int count = 0;
        for (Slot slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot) {
                if (appEngSlot.isSlotEnabled()) {
                    count++;
                }
            } else {
                count++;
            }
        }
        return count;
    }
}
