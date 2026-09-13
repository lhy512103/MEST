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
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;
import appeng.menu.slot.AppEngSlot;

/**
 * MEST's own 3×3 network-tool panel for AE machine screens: the same compact chrome the terminal's
 * network-tool module uses, with a scrollbar for the extra upgrade rows. The first slot sits at
 * {@link #PAD}, so the caller anchors the panel at the vanilla slot position minus that inset.
 * Bounds are GUI-relative, like AE2's own {@code ToolboxPanel}.
 */
public class ToolboxChrome implements ICompositeWidget {
    /** Inset from the panel edge to the first slot: the AE2 window bevel is 4px wide. */
    public static final int PAD = 4;
    /**
     * Offset from the panel origin to the first slot's item origin. AE2 puts {@code slot.x} at the
     * item area, and the 18×18 well is drawn one pixel before it, so callers anchor the panel at
     * {@code vanillaSlotPosition - SLOT_ORIGIN}.
     */
    public static final int SLOT_ORIGIN = PAD + 1;
    private static final int SLOT = 18;
    private static final int COLS = 3;
    private static final int ROWS = 3;
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_INNER = 3;
    private static final int TRACK_GAP = 2;
    private static final int INSIDE_GUTTER = TRACK_GAP + TRACK_WIDTH + 2;
    private static final int TRACK_BORDER = 0xFFF2F2F2;
    private static final int TRACK_FILL = 0xFF9A9FB4;
    public static final int WIDTH = 2 * PAD + COLS * SLOT + INSIDE_GUTTER;
    public static final int HEIGHT = 2 * PAD + ROWS * SLOT;

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

    /** True when at least one toolbox slot is usable; hidden toolboxes leave every slot disabled. */
    public static boolean hasVisibleSlots(List<Slot> slots) {
        return enabledCount(slots) > 0;
    }

    public boolean isVisible() {        return bounds.getWidth() > 0 && bounds.getHeight() > 0;
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
                slot.x = bounds.getX() + PAD + col * SLOT + 1;
                slot.y = bounds.getY() + PAD + row * SLOT + 1;
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
            int x = bounds.getX();
            int y = bounds.getY();
            ModulePanel.drawGeneratedBackground(g, x, y, WIDTH, HEIGHT, 0);
            int visibleCells = Math.min(enabledCount(slots), ROWS * COLS);
            for (int cell = 0; cell < visibleCells; cell++) {
                ModulePanel.drawSlot(g, x + PAD + (cell % COLS) * SLOT, y + PAD + (cell / COLS) * SLOT);
            }
            if (scrollbar.isVisible()) {
                drawTrack(g, trackLeft(), y + PAD, ROWS * SLOT - 1);
                scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), mouse);
            }
        } finally {
            g.pose().popPose();
        }
    }

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

    private int trackLeft() {
        return bounds.getX() + PAD + COLS * SLOT + TRACK_GAP;
    }

    private void layoutScrollbar() {
        int neededRows = Math.max(1, (enabledCount(slots) + COLS - 1) / COLS);
        int max = Math.max(0, neededRows - ROWS);
        scrollbar.setRange(0, max, 1);
        scrollbar.setVisible(max > 0 && isVisible());
        int height = ROWS * SLOT - 1;
        scrollbar.setHeight(Math.max(1, height - 2));
        // 7px handle centred on the 5px track, same inset as ToolkitPanel.
        scrollbar.setPosition(new Point(trackLeft() - 1, bounds.getY() + PAD + 1));
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
