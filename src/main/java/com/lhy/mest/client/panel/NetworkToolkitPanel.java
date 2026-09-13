package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.widgets.Scrollbar;

import com.lhy.mest.client.dock.MestPanelSkin;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.terminal.MESTMenu;

/**
 * 3×3 view of the network toolkit (or vanilla network-tool toolbox). Extra rows scroll.
 */
public class NetworkToolkitPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int MIN_ROWS = 3;
    /** Tighter than {@link ModulePanel#CONTENT_PADDING}; matches the AE2 window bevel width. */
    private static final int PAD = 4;
    /**
     * Invisible drag strip along the top edge. There is no title bar, so window dragging is bound
     * to this band instead of {@link ModulePanel#TITLE_BAR_HEIGHT}.
     */
    private static final int DRAG_BAND = 8;
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_INNER = 3;
    private static final int INSIDE_TRACK_GAP = 2;
    private static final int INSIDE_GUTTER = INSIDE_TRACK_GAP + TRACK_WIDTH + 2;
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
        return 2 * PAD + COLS * SLOT + INSIDE_GUTTER;
    }

    @Override
    public int defaultHeight() {
        return DRAG_BAND + MIN_ROWS * SLOT + PAD;
    }

    @Override
    protected boolean drawsTitleBar() {
        // No title strip: no title text and no pin button. Dragging uses DRAG_BAND instead.
        return false;
    }

    @Override
    public boolean inTitleBar(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + DRAG_BAND;
    }

    @Override
    public boolean canSplice() {
        return false;
    }

    /** Pinned window chrome without the title strip {@link #drawsTitleBar()} would suppress. */
    @Override
    public void renderFrame(
            GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks, int sharedEdges) {
        if (hosted) {
            return;
        }
        if ((sharedEdges & EDGE_BOTTOM) == 0) {
            g.fill(x + 2, y + height, x + width + 2, y + height + 2, 0x55000000);
        }
        if ((sharedEdges & EDGE_RIGHT) == 0 && outsideHitWidth() <= 0) {
            g.fill(x + width, y + 2, x + width + 2, y + height + 2, 0x55000000);
        }
        MestPanelSkin.drawFrame(g, x, y, width, height);
    }

    @Override
    public void renderSectionHeader(GuiGraphics g, Font font, int mouseX, int mouseY) {
        // Spliced sections keep their own header; this panel never has one.
    }

    @Override
    public int contentLeft() {
        return x + PAD + contentOffsetX;
    }

    @Override
    public int contentTop() {
        return y + DRAG_BAND + contentOffsetY;
    }

    @Override
    public int contentWidth() {
        return Math.max(0, width - 2 * PAD - contentRightInset);
    }

    @Override
    public int contentHeight() {
        return Math.max(0, height - DRAG_BAND - PAD);
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
    public int preferredContentWidth() {
        return COLS * SLOT + INSIDE_GUTTER;
    }

    @Override
    public int preferredContentHeight() {
        return MIN_ROWS * SLOT;
    }

    @Override
    public int preferredContentRightInset() {
        return visible ? INSIDE_GUTTER : 0;
    }

    @Override
    public void layoutSlots() {
        this.rows = Math.max(MIN_ROWS, contentHeight() / SLOT);
        layoutScrollbar();
        if (!visible || width < minWidth() || height < minHeight()) {
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
        if (maxScroll() > 0) {
            int trackLeft = trackLeft();
            int trackTop = contentTop();
            int trackHeight = trackHeight();
            drawTrack(g, trackLeft, trackTop, trackHeight);
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
        }
    }

    /** Same recipe as {@code ToolkitPanel}: 7px handle centred on the 5px track. */
    private void drawTrack(GuiGraphics g, int x, int y, int height) {
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

    private int trackLeft() {
        return contentLeft() + COLS * SLOT + INSIDE_TRACK_GAP;
    }

    private int trackHeight() {
        return Math.max(1, rows * SLOT - 1);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || (!inGrid(mx, my) && !inScrollbar(mx, my))) {
            return false;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - (int) Math.signum(scrollY));
        scrollRows = scrollbar.getCurrentScroll();
        layoutSlots();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || maxScroll() <= 0 || !inScrollbar(mx, my)) {
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
        scrollbar.setHeight(Math.max(1, trackHeight() - 2));
        // Thumb sits in the 3px inner fill of the 5px track, same inset as ToolkitPanel.
        scrollbar.setPosition(new Point(trackLeft() - 1, contentTop() + 1));
        scrollbar.setCurrentScroll(Math.min(scrollRows, max));
        scrollRows = scrollbar.getCurrentScroll();
    }

    private int neededRows() {
        return Math.max(1, (slots.size() + COLS - 1) / COLS);
    }

    private int maxScroll() {
        return Math.max(0, neededRows() - Math.max(1, Math.min(rows, neededRows())));
    }

    private boolean inGrid(double mx, double my) {
        int visibleCells = Math.min(Math.max(0, slots.size() - scrollRows * COLS), rows * COLS);
        int occupiedRows = visibleCells <= 0 ? 0 : (visibleCells + COLS - 1) / COLS;
        if (occupiedRows <= 0) {
            return false;
        }
        int lastCols = visibleCells % COLS == 0 ? COLS : visibleCells % COLS;
        int y = (int) my - contentTop();
        if (y < 0 || y >= occupiedRows * SLOT) {
            return false;
        }
        int row = y / SLOT;
        int width = row == occupiedRows - 1 ? lastCols * SLOT : COLS * SLOT;
        return mx >= contentLeft() && mx < contentLeft() + width;
    }

    private boolean inScrollbar(double mx, double my) {
        if (maxScroll() <= 0) {
            return false;
        }
        Rect2i bounds = scrollbar.getBounds();
        return mx >= bounds.getX() && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY() && my < bounds.getY() + bounds.getHeight();
    }
}
