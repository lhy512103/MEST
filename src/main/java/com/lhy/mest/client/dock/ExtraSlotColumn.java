package com.lhy.mest.client.dock;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.style.Blitter;
import appeng.menu.slot.AppEngSlot;

/**
 * AE2 {@link appeng.client.gui.widgets.UpgradesPanel} chrome, hung off the spliced group like
 * {@link PanelSideBar}. Not a dock module.
 *
 * <p>{@code extra_panels.png}'s slot well starts at texture (1, 6), so items sit at panel
 * {@code x + 1} like vanilla — not at {@code x + PADDING + 1}.
 */
public class ExtraSlotColumn implements ExtraChrome {
    private static final int SLOT = 18;
    private static final int PADDING = 5;
    private static final int MAX_ROWS = 8;
    private static final int SLOT_OUTLINE = 0xFFF2F2F2;
    private static final Blitter BACKGROUND = Blitter.texture("guis/extra_panels.png", 128, 128);
    private static final Blitter INNER_CORNER = BACKGROUND.copy().src(12, 33, SLOT, SLOT);

    private final List<Slot> slots;
    private final Supplier<List<Component>> tooltipSupplier;
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    public ExtraSlotColumn(List<Slot> slots, Supplier<List<Component>> tooltipSupplier) {
        this.slots = slots;
        this.tooltipSupplier = tooltipSupplier != null ? tooltipSupplier : Collections::emptyList;
    }

    public ExtraSlotColumn(List<Slot> slots) {
        this(slots, Collections::emptyList);
    }

    public boolean hasSlots() {
        return enabledSlotCount() > 0;
    }

    public Rect2i bounds() {
        return bounds;
    }

    public int right() {
        return bounds.getX() + bounds.getWidth();
    }

    /**
     * Left of the next extra panel so its 5px left chrome overlaps this panel's 5px right chrome
     * and the wells sit 18px apart.
     */
    public int nextColumnX() {
        return bounds.getX() + PADDING + columns() * SLOT - 1;
    }

    public boolean isVisible() {
        return bounds.getWidth() > 0 && bounds.getHeight() > 0;
    }

    public boolean contains(double mx, double my) {
        return isVisible()
                && mx >= bounds.getX()
                && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY()
                && my < bounds.getY() + bounds.getHeight();
    }

    public boolean ownsSlot(Slot slot) {
        for (Slot owned : slots) {
            if (owned == slot) {
                return true;
            }
        }
        return false;
    }

    public void layoutAgainst(int attachX, int attachY, boolean show) {
        if (!show || !hasSlots()) {
            hide();
            return;
        }
        int slotCount = enabledSlotCount();
        int rows = Math.max(1, Math.min(MAX_ROWS, slotCount));
        int width = 2 * PADDING + columns() * SLOT;
        int height = 2 * PADDING + rows * SLOT;
        bounds = new Rect2i(attachX, attachY, width, height);
        placeSlots();
    }

    public void hide() {
        bounds = new Rect2i(0, 0, 0, 0);
        for (Slot slot : slots) {
            slot.x = -9999;
            slot.y = -9999;
        }
    }

    public void renderBackground(GuiGraphics g) {
        int slotCount = enabledSlotCount();
        if (!isVisible() || slotCount <= 0) {
            return;
        }
        int slotOriginX = bounds.getX() + PADDING;
        int slotOriginY = bounds.getY() + PADDING;
        for (int i = 0; i < slotCount; i++) {
            int row = i % MAX_ROWS;
            int col = i / MAX_ROWS;
            int x = slotOriginX + col * SLOT;
            int y = slotOriginY + row * SLOT;
            boolean lastSlot = i + 1 >= slotCount;
            boolean lastRow = row + 1 >= MAX_ROWS;
            drawSlotChrome(g, x, y, col == 0, row == 0, i >= slotCount - MAX_ROWS, lastRow || lastSlot);
            if (col > 0 && lastSlot && !lastRow) {
                INNER_CORNER.dest(x, y + SLOT).blit(g);
            }
        }
        g.hLine(slotOriginX - 4, slotOriginX + 11, slotOriginY, SLOT_OUTLINE);
        g.hLine(slotOriginX - 4, slotOriginX + 11, slotOriginY + SLOT * Math.min(MAX_ROWS, slotCount) - 1, SLOT_OUTLINE);
        g.vLine(slotOriginX - 5, slotOriginY - 1, slotOriginY + SLOT * Math.min(MAX_ROWS, slotCount), SLOT_OUTLINE);
        g.vLine(slotOriginX + 12, slotOriginY - 1, slotOriginY + SLOT * Math.min(MAX_ROWS, slotCount), SLOT_OUTLINE);
    }

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
        Slot slot = slotAt(mx, my);
        if (slot != null && !slot.getItem().isEmpty()) {
            return List.of();
        }
        return tooltipSupplier.get();
    }

    private Slot slotAt(double mx, double my) {
        for (Slot slot : slots) {
            if (slot.x <= -1000 || slot.y <= -1000) {
                continue;
            }
            if (mx >= slot.x - 1 && mx < slot.x + 17 && my >= slot.y - 1 && my < slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    private void placeSlots() {
        // Same as UpgradesPanel.updateBeforeRender: items sit on the well at panel x+1.
        int slotOriginX = bounds.getX();
        int slotOriginY = bounds.getY() + PADDING;
        int index = 0;
        for (Slot slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot && !appEngSlot.isSlotEnabled()) {
                slot.x = -9999;
                slot.y = -9999;
                continue;
            }
            int row = index % MAX_ROWS;
            int col = index / MAX_ROWS;
            slot.x = slotOriginX + col * SLOT + 1;
            slot.y = slotOriginY + row * SLOT + 1;
            index++;
        }
    }

    private int columns() {
        return Math.max(1, (enabledSlotCount() + MAX_ROWS - 1) / MAX_ROWS);
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

    private static void drawSlotChrome(GuiGraphics g, int x, int y,
            boolean borderLeft, boolean borderTop, boolean borderRight, boolean borderBottom) {
        int srcX = PADDING;
        int srcY = PADDING;
        int srcWidth = SLOT;
        int srcHeight = SLOT;
        int destX = x;
        int destY = y;
        if (borderLeft) {
            destX -= PADDING;
            srcX = 0;
            srcWidth += PADDING;
        }
        if (borderRight) {
            srcWidth += PADDING;
        }
        if (borderTop) {
            destY -= PADDING;
            srcY = 0;
            srcHeight += PADDING;
        }
        if (borderBottom) {
            srcHeight += PADDING + 2;
        }
        BACKGROUND.src(srcX, srcY, srcWidth, srcHeight).dest(destX, destY).blit(g);
    }
}
