package com.lhy.mest.client.dock;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Tooltip;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;
import appeng.menu.slot.AppEngSlot;

/**
 * Vanilla AE2 network-tool 3×3 sprite. Extra rows scroll; the 59×66 well stays the same size.
 */
public class ToolboxChrome implements ExtraChrome, ICompositeWidget {
    public static final int WIDTH = 59;
    public static final int HEIGHT = 66;
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int ROWS = 3;
    private static final int SLOT_X = 1;
    private static final int SLOT_Y = 6;
    private static final int TRACK_X = 53;
    private static final Blitter BACKGROUND = Blitter.texture("guis/extra_panels.png", 128, 128)
            .src(69, 62, WIDTH, HEIGHT);

    private final List<Slot> slots;
    private final Component toolName;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);
    private boolean draggingScrollbar;
    private int drawOffsetX;
    private int drawOffsetY;

    public ToolboxChrome(List<Slot> slots, Component toolName) {
        this.slots = slots;
        this.toolName = toolName;
        this.scrollbar.setCaptureMouseWheel(false);
    }

    public static boolean needsScroll(List<Slot> slots) {
        return enabledCount(slots) > COLS * ROWS;
    }

    @Override
    public boolean hasSlots() {
        return enabledCount(slots) > 0;
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
    public Rect2i getBounds() {
        return bounds;
    }

    @Override
    public void setPosition(Point position) {
        bounds = new Rect2i(position.getX(), position.getY(), WIDTH, HEIGHT);
        layoutScrollbar();
        placeSlots();
    }

    @Override
    public void setSize(int width, int height) {
        bounds = new Rect2i(bounds.getX(), bounds.getY(), WIDTH, HEIGHT);
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
        layoutScrollbar();
        placeSlots();
    }

    @Override
    public void hide() {
        bounds = new Rect2i(0, 0, 0, 0);
        scrollbar.setVisible(false);
        draggingScrollbar = false;
        hideSlots();
    }

    public void hideChrome() {
        bounds = new Rect2i(0, 0, 0, 0);
        scrollbar.setVisible(false);
        draggingScrollbar = false;
    }

    private void hideSlots() {
        for (Slot slot : slots) {
            slot.x = -9999;
            slot.y = -9999;
        }
    }

    public void placeSlots() {
        int first = scrollbar.getCurrentScroll() * COLS;
        int index = 0;
        for (Slot slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot && !appEngSlot.isSlotEnabled()) {
                slot.x = -9999;
                slot.y = -9999;
                continue;
            }
            int visible = index - first;
            int row = COLS == 0 ? 0 : visible / COLS;
            int col = COLS == 0 ? 0 : visible % COLS;
            if (visible < 0 || row >= ROWS) {
                slot.x = -9999;
                slot.y = -9999;
            } else {
                slot.x = bounds.getX() + SLOT_X + col * SLOT;
                slot.y = bounds.getY() + SLOT_Y + row * SLOT;
            }
            index++;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        drawBackground(g, bounds.getX(), bounds.getY());
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics g, Rect2i gui, Point mouse) {
        if (!isVisible()) {
            return;
        }
        drawBackground(g, gui.getX() + bounds.getX(), gui.getY() + bounds.getY());
    }

    private void drawBackground(GuiGraphics g, int x, int y) {
        drawOffsetX = x - bounds.getX();
        drawOffsetY = y - bounds.getY();
        BACKGROUND.dest(x, y, WIDTH, HEIGHT).blit(g);
        layoutScrollbar();
        if (scrollbar.isVisible()) {
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), Point.ZERO);
        }
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
        Tooltip tooltip = getTooltip((int) mx, (int) my);
        return tooltip == null ? List.of() : tooltip.getContent();
    }

    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        for (Slot slot : slots) {
            if (slot.x <= -1000 || slot.y <= -1000) {
                continue;
            }
            if (mouseX >= slot.x - 1 && mouseX < slot.x + 17 && mouseY >= slot.y - 1 && mouseY < slot.y + 17
                    && !slot.getItem().isEmpty()) {
                return null;
            }
        }
        return new Tooltip(
                toolName,
                GuiText.UpgradeToolbelt.text().plainCopy().withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return onMouseDown(new Point((int) mx, (int) my), button);
    }

    @Override
    public boolean onMouseDown(Point mouse, int button) {
        Point screen = screenPoint(mouse);
        if (!scrollbar.isVisible() || !overScrollbar(screen.getX(), screen.getY())) {
            return false;
        }
        boolean handled = scrollbar.onMouseDown(screen, button);
        draggingScrollbar = handled;
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my) {
        return onMouseDrag(new Point((int) mx, (int) my), 0);
    }

    @Override
    public boolean onMouseDrag(Point mouse, int button) {
        if (!draggingScrollbar) {
            return false;
        }
        boolean handled = scrollbar.onMouseDrag(screenPoint(mouse), button);
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = draggingScrollbar;
        onMouseUp(new Point((int) mx, (int) my), button);
        return was;
    }

    @Override
    public boolean onMouseUp(Point mouse, int button) {
        draggingScrollbar = false;
        scrollbar.onMouseUp(mouse, button);
        placeSlots();
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return onMouseWheel(new Point((int) mx, (int) my), delta);
    }

    @Override
    public boolean onMouseWheel(Point mouse, double delta) {
        if (!isVisible() || !scrollbar.isVisible() || !contains(mouse.getX(), mouse.getY()) || delta == 0) {
            return false;
        }
        boolean handled = scrollbar.onMouseWheel(screenPoint(mouse), delta);
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    private void layoutScrollbar() {
        int enabled = enabledCount(slots);
        int neededRows = Math.max(1, (enabled + COLS - 1) / COLS);
        int max = Math.max(0, neededRows - ROWS);
        scrollbar.setRange(0, max, 1);
        scrollbar.setVisible(max > 0 && isVisible());
        scrollbar.setHeight(Math.max(1, ROWS * SLOT - 2));
        scrollbar.setPosition(new Point(
                drawOffsetX + bounds.getX() + TRACK_X,
                drawOffsetY + bounds.getY() + SLOT_Y + 1));
    }

    private Point screenPoint(Point mouse) {
        return new Point(mouse.getX() + drawOffsetX, mouse.getY() + drawOffsetY);
    }

    private boolean overScrollbar(int mx, int my) {
        Rect2i bar = scrollbar.getBounds();
        return mx >= bar.getX() && mx < bar.getX() + bar.getWidth()
                && my >= bar.getY() && my < bar.getY() + bar.getHeight();
    }

    private static int enabledCount(List<Slot> slots) {
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
