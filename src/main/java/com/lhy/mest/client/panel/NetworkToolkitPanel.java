package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.widgets.Scrollbar;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.terminal.MESTMenu;

/**
 * 3×3 view of the network toolkit (or vanilla network-tool toolbox). Extra rows scroll.
 */
public class NetworkToolkitPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int MIN_ROWS = 3;
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_INNER = 3;
    private static final int INSIDE_TRACK_GAP = 2;
    private static final int TRACK_SHIFT_X = 3;
    private static final int INSIDE_GUTTER = INSIDE_TRACK_GAP + TRACK_WIDTH + TRACK_SHIFT_X;
    private static final int TRACK_BORDER = 0xFFF2F2F2;
    private static final int TRACK_FILL = 0xFF9A9FB4;

    private final List<Slot> slots;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private int rows = MIN_ROWS;
    private int scrollRows;
    private boolean scrollbarDragging;

    public NetworkToolkitPanel(MESTMenu menu) {
        this.slots = menu.getNetworkToolkitSlots();
        for (Slot slot : slots) {
            registerSlot(slot);
        }
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public String id() {
        return "network_toolkit";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.network_toolkit");
    }

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + COLS * SLOT + INSIDE_GUTTER;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + MIN_ROWS * SLOT;
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
    public boolean expandsVertically() {
        return true;
    }

    @Override
    public int preferredContentRightInset() {
        return visible ? INSIDE_GUTTER : 0;
    }

    @Override
    public void layoutSlots() {
        this.rows = Math.max(MIN_ROWS, contentHeight() / SLOT);
        layoutScrollbar();
        if (!visible) {
            for (Slot slot : slots) {
                hideSlot(slot);
            }
            return;
        }
        int gridLeft = contentLeft();
        int gridTop = contentTop();
        int first = scrollRows * COLS;
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            int visibleIndex = index - first;
            int row = visibleIndex / COLS;
            int col = visibleIndex % COLS;
            if (visibleIndex < 0 || row >= rows) {
                hideSlot(slot);
            } else {
                placeSlot(slot, gridLeft + col * SLOT + 1, gridTop + row * SLOT + 1);
            }
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        layoutScrollbar();
        int gridLeft = contentLeft();
        int gridTop = contentTop();
        int visibleCells = Math.min(Math.max(0, slots.size() - scrollRows * COLS), rows * COLS);
        int occupiedRows = visibleCells <= 0 ? 0 : (visibleCells + COLS - 1) / COLS;
        for (int visibleIndex = 0; visibleIndex < visibleCells; visibleIndex++) {
            int row = visibleIndex / COLS;
            int col = visibleIndex % COLS;
            drawSlot(g, gridLeft + col * SLOT, gridTop + row * SLOT);
        }
        if (occupiedRows > 0) {
            int lastCols = visibleCells % COLS == 0 ? COLS : visibleCells % COLS;
            int width = (occupiedRows == 1 ? lastCols : COLS) * SLOT;
            g.hLine(gridLeft, gridLeft + width - 1, gridTop, 0xFFF2F2F2);
            g.hLine(gridLeft, gridLeft + width - 1, gridTop + occupiedRows * SLOT - 1, 0xFFF2F2F2);
            g.vLine(gridLeft, gridTop, gridTop + occupiedRows * SLOT - 1, 0xFFF2F2F2);
            g.vLine(gridLeft + width - 1, gridTop, gridTop + occupiedRows * SLOT - 1, 0xFFF2F2F2);
        }
        g.flush();
        int trackHeight = Math.max(1, occupiedRows * SLOT);
        if (maxScroll() > 0) {
            int trackLeft = contentLeft() + COLS * SLOT + INSIDE_TRACK_GAP + TRACK_SHIFT_X;
            int x1 = trackLeft + TRACK_WIDTH - 1;
            int y1 = contentTop() + trackHeight - 1;
            g.hLine(trackLeft, x1, contentTop(), TRACK_BORDER);
            g.hLine(trackLeft, x1, y1, TRACK_BORDER);
            g.vLine(trackLeft, contentTop(), y1, TRACK_BORDER);
            g.vLine(x1, contentTop(), y1, TRACK_BORDER);
            if (trackHeight > 2) {
                g.fill(trackLeft + 1, contentTop() + 1, trackLeft + 1 + TRACK_INNER, y1, TRACK_FILL);
            }
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || maxScroll() <= 0) {
            return false;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - (int) Math.signum(scrollY));
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || maxScroll() <= 0) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDown(new Point((int) mx, (int) my), 0);
        scrollbarDragging = consumed;
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
        return consumed;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbarDragging) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDrag(new Point((int) mx, (int) my), 0);
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
        return consumed;
    }

    @Override
    public void scrollbarReleased() {
        if (scrollbarDragging) {
            scrollbar.onMouseUp(Point.ZERO, 0);
        }
        scrollbarDragging = false;
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
    }

    @Override
    public boolean scrollbarDragging() {
        return scrollbarDragging;
    }

    private void layoutScrollbar() {
        int shown = Math.max(1, Math.min(rows, neededRows()));
        int max = Math.max(0, neededRows() - shown);
        scrollbar.setRange(0, max, 1);
        int height = Math.max(1, shown * SLOT);
        scrollbar.setHeight(Math.max(1, height - 2));
        scrollbar.setPosition(new Point(contentLeft() + COLS * SLOT + INSIDE_TRACK_GAP + TRACK_SHIFT_X + 1,
                contentTop() + 1));
        scrollbar.setCurrentScroll(Math.min(scrollRows, max));
        scrollRows = scrollbar.getCurrentScroll();
    }

    private int neededRows() {
        return Math.max(1, (slots.size() + COLS - 1) / COLS);
    }

    private int maxScroll() {
        return Math.max(0, neededRows() - Math.max(1, Math.min(rows, neededRows())));
    }
}
