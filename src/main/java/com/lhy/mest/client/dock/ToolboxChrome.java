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
 * Vanilla AE2 network-tool 3×3 panel for machine screens, plus a scrollbar for the extra rows.
 * The vanilla sprite already carries a 3px groove on its right edge, so the handle sits in it and
 * the panel keeps its original 59×66 size. Bounds are GUI-relative, like AE2's own
 * {@code ToolboxPanel}; the sprite and the handle are drawn inside a translated pose.
 */
public class ToolboxChrome implements ICompositeWidget {
    public static final int WIDTH = 59;
    public static final int HEIGHT = 66;
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int ROWS = 3;
    private static final int SLOT_X = 1;
    private static final int SLOT_Y = 6;
    /** Vanilla sprite groove: x 54..56 inside the panel, i.e. centred on 55. */
    private static final int GROOVE_CENTER_X = 55;
    private static final int TRACK_TOP = SLOT_Y + 1;
    private static final int TRACK_HEIGHT = ROWS * SLOT - 2;
    private static final Blitter BACKGROUND = Blitter.texture("guis/extra_panels.png", 128, 128)
            .src(69, 62, WIDTH, HEIGHT);

    private final List<Slot> slots;
    private final Component toolName;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);
    private boolean draggingScrollbar;

    public ToolboxChrome(List<Slot> slots, Component toolName) {
        this.slots = slots;
        this.toolName = toolName;
        this.scrollbar.setCaptureMouseWheel(false);
    }

    public static boolean needsScroll(List<Slot> slots) {
        return enabledCount(slots) > COLS * ROWS;
    }

    public boolean isVisible() {
        return bounds.getWidth() > 0 && bounds.getHeight() > 0;
    }

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

    public boolean ownsSlot(Slot slot) {
        for (Slot owned : slots) {
            if (owned == slot) {
                return true;
            }
        }
        return false;
    }

    public void hide() {
        bounds = new Rect2i(0, 0, 0, 0);
        scrollbar.setVisible(false);
        draggingScrollbar = false;
        for (Slot slot : slots) {
            slot.x = -9999;
            slot.y = -9999;
        }
    }

    public void placeSlots() {
        if (!isVisible()) {
            return;
        }
        int first = scrollbar.getCurrentScroll() * COLS;
        int index = 0;
        for (Slot slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot && !appEngSlot.isSlotEnabled()) {
                slot.x = -9999;
                slot.y = -9999;
                continue;
            }
            int visible = index - first;
            int row = visible / COLS;
            int col = visible % COLS;
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
    public void drawBackgroundLayer(GuiGraphics g, Rect2i gui, Point mouse) {
        if (!isVisible()) {
            return;
        }
        layoutScrollbar();
        g.pose().pushPose();
        try {
            g.pose().translate(gui.getX(), gui.getY(), 0.0F);
            BACKGROUND.dest(bounds.getX(), bounds.getY(), WIDTH, HEIGHT).blit(g);
            if (scrollbar.isVisible()) {
                scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), mouse);
            }
        } finally {
            g.pose().popPose();
        }
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
    public boolean onMouseDown(Point mouse, int button) {
        if (!scrollbar.isVisible() || !overScrollbar(mouse.getX(), mouse.getY())) {
            return false;
        }
        boolean handled = scrollbar.onMouseDown(mouse, button);
        draggingScrollbar = handled;
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    @Override
    public boolean onMouseDrag(Point mouse, int button) {
        if (!draggingScrollbar) {
            return false;
        }
        boolean handled = scrollbar.onMouseDrag(mouse, button);
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    @Override
    public boolean onMouseUp(Point mouse, int button) {
        draggingScrollbar = false;
        scrollbar.onMouseUp(mouse, button);
        placeSlots();
        return false;
    }

    @Override
    public boolean onMouseWheel(Point mouse, double delta) {
        if (!isVisible() || !scrollbar.isVisible() || !contains(mouse.getX(), mouse.getY()) || delta == 0) {
            return false;
        }
        boolean handled = scrollbar.onMouseWheel(mouse, delta);
        if (handled) {
            placeSlots();
        }
        return handled;
    }

    private boolean contains(int mx, int my) {
        return isVisible()
                && mx >= bounds.getX()
                && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY()
                && my < bounds.getY() + bounds.getHeight();
    }

    private void layoutScrollbar() {
        int neededRows = Math.max(1, (enabledCount(slots) + COLS - 1) / COLS);
        int max = Math.max(0, neededRows - ROWS);
        scrollbar.setRange(0, max, 1);
        scrollbar.setVisible(max > 0 && isVisible());
        scrollbar.setHeight(TRACK_HEIGHT);
        // 7px handle centred on the sprite's 3px groove.
        scrollbar.setPosition(new Point(
                bounds.getX() + GROOVE_CENTER_X - Scrollbar.SMALL.handleWidth() / 2,
                bounds.getY() + TRACK_TOP));
    }

    private boolean overScrollbar(int mx, int my) {
        if (!scrollbar.isVisible()) {
            return false;
        }
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
