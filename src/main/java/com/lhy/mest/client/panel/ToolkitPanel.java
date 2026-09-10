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
import com.lhy.mest.config.MestConfig;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Toolkit: an extension of the player inventory that only accepts unstackable items (tools, gear).
 *
 * <p>Reuses the trash module's visual language — a scrollable slot grid with a bordered frame — but
 * stays a permanent dock module instead of a submenu, so it has no back button.
 */
public class ToolkitPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int MIN_COLS = 9;
    private static final int MIN_ROWS = 2;
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_INNER = 3;
    private static final int INSIDE_TRACK_GAP = 2;
    private static final int TRACK_SHIFT_X = 3;
    private static final int INSIDE_GUTTER = INSIDE_TRACK_GAP + TRACK_WIDTH + TRACK_SHIFT_X;
    private static final int TRACK_BORDER = 0xFFF2F2F2;
    private static final int TRACK_FILL = 0xFF9A9FB4;

    private final List<Slot> toolkitSlots;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private int cols = MIN_COLS;
    private int rows = MIN_ROWS;
    private int scrollRows;
    private boolean scrollbarDragging;

    public ToolkitPanel(MESTMenu menu) {
        this.toolkitSlots = menu.getToolkitSlots();
        for (Slot slot : toolkitSlots) {
            registerSlot(slot);
        }
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public String id() {
        return "toolkit";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.toolkit");
    }

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + MIN_COLS * SLOT + INSIDE_GUTTER;
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
    public boolean fillsContentArea() {
        return true;
    }

    @Override
    public int preferredContentRightInset() {
        return visible ? INSIDE_GUTTER : 0;
    }

    @Override
    public void layoutSlots() {
        this.cols = Math.max(MIN_COLS, contentWidth() / SLOT);
        this.rows = Math.max(MIN_ROWS, contentHeight() / SLOT);
        layoutScrollbar();
        if (!visible) {
            for (Slot slot : toolkitSlots) {
                hideSlot(slot);
            }
            return;
        }
        int gridLeft = contentLeft();
        int gridTop = contentTop();
        int first = scrollRows * cols;
        for (int index = 0; index < toolkitSlots.size(); index++) {
            Slot slot = toolkitSlots.get(index);
            int visibleIndex = index - first;
            int row = cols == 0 ? 0 : visibleIndex / cols;
            int col = cols == 0 ? 0 : visibleIndex % cols;
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
        int visibleCells = Math.min(Math.max(0, toolkitSlots.size() - scrollRows * cols), rows * cols);
        for (int visibleIndex = 0; visibleIndex < visibleCells; visibleIndex++) {
            int row = cols == 0 ? 0 : visibleIndex / cols;
            int col = cols == 0 ? 0 : visibleIndex % cols;
            drawSlot(g, gridLeft + col * SLOT, gridTop + row * SLOT);
        }
        drawSlotGroupBorder(g, gridLeft, gridTop, cols, rows);
        g.flush();
        drawTrack(g, trackDrawLeft(), contentTop(), Math.max(1, rows * SLOT));
        scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || (!inGrid(mx, my) && !inScroller(mx, my))) {
            return false;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - (int) Math.signum(scrollY));
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || !inScrollbar(mx, my)) {
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
        int max = Math.max(0, totalRows() - rows);
        scrollbar.setRange(0, max, 1);
        scrollbar.setHeight(Math.max(1, rows * SLOT - 2));
        scrollbar.setPosition(new Point(trackLeft() + 2, contentTop() + 1));
        scrollbar.setCurrentScroll(Math.min(scrollRows, max));
        scrollRows = scrollbar.getCurrentScroll();
    }

    private int totalRows() {
        int size = Math.max(toolkitSlots.size(), MestConfig.toolkitSlots());
        return Math.max(MIN_ROWS, (size + cols - 1) / Math.max(1, cols));
    }

    private int trackLeft() {
        return contentLeft() + cols * SLOT + INSIDE_TRACK_GAP;
    }

    private int trackDrawLeft() {
        return trackLeft() + TRACK_SHIFT_X;
    }

    private boolean inGrid(double mx, double my) {
        return mx >= contentLeft() && mx < contentLeft() + cols * SLOT
                && my >= contentTop() && my < contentTop() + rows * SLOT;
    }

    private boolean inScroller(double mx, double my) {
        if (inScrollbar(mx, my)) {
            return true;
        }
        int left = trackDrawLeft();
        int top = contentTop();
        int height = Math.max(1, rows * SLOT);
        return mx >= left && mx < left + TRACK_WIDTH && my >= top && my < top + height;
    }

    private boolean inScrollbar(double mx, double my) {
        Rect2i bounds = scrollbar.getBounds();
        return mx >= bounds.getX() && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY() && my < bounds.getY() + bounds.getHeight();
    }

    private static void drawTrack(GuiGraphics g, int x, int y, int height) {
        if (height <= 0) {
            return;
        }
        int x1 = x + TRACK_WIDTH - 1;
        int y1 = y + height - 1;
        g.hLine(x, x1, y, TRACK_BORDER);
        g.hLine(x, x1, y1, TRACK_BORDER);
        g.vLine(x, y, y1, TRACK_BORDER);
        g.vLine(x1, y, y1, TRACK_BORDER);
        if (height > 2) {
            g.fill(x + 1, y + 1, x + 1 + TRACK_INNER, y1, TRACK_FILL);
        }
    }

    private static void drawSlotGroupBorder(GuiGraphics g, int px, int py, int cols, int rows) {
        int x1 = px + cols * SLOT;
        int y1 = py + rows * SLOT;
        g.hLine(px, x1 - 1, py, 0xFFF2F2F2);
        g.hLine(px, x1 - 1, y1 - 1, 0xFFF2F2F2);
        g.vLine(px, py, y1 - 1, 0xFFF2F2F2);
        g.vLine(x1 - 1, py, y1 - 1, 0xFFF2F2F2);
    }
}
