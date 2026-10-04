package com.lhy.mest.client.dock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.widgets.ITooltip;
import appeng.core.AppEng;

import com.lhy.mest.MESplicedterminal;

/**
 * AE2 {@code VerticalButtonBar} equivalent. It is not a dock module: the terminal screen
 * attaches it to the left of the non-floating spliced group at runtime, matching vanilla.
 */
public class PanelSideBar {
    private static final ResourceLocation ATTACHED_BACKGROUND = AppEng.makeId("vertical_buttons_bg");
    private static final ResourceLocation COMPLETE_BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "vertical_buttons_bg_complete");

    public static final int BUTTON = 16;
    public static final int SPACING = 6;
    public static final int MARGIN = 2;
    public static final int ANCHOR_X = 3;
    public static final int ANCHOR_Y = 1;

    private final List<Button> buttons = new ArrayList<>();
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);
    private boolean shown;

    public void add(Button button) {
        buttons.add(button);
    }

    public void clearFrom(int index) {
        if (index < 0) {
            buttons.clear();
            return;
        }
        if (index >= buttons.size()) {
            return;
        }
        buttons.subList(index, buttons.size()).clear();
    }

    public List<Button> buttons() {
        return buttons;
    }

    public Rect2i bounds() {
        return bounds;
    }

    public boolean isVisible() {
        return shown && bounds.getWidth() > 0 && bounds.getHeight() > 0;
    }

    public void layoutAgainst(int groupLeft, int groupTop, boolean show) {
        shown = show;
        if (!show) {
            bounds = new Rect2i(0, 0, 0, 0);
            return;
        }
        int currentY = groupTop + ANCHOR_Y + MARGIN;
        int maxWidth = 0;
        for (Button button : buttons) {
            if (!button.visible) {
                continue;
            }
            int width = Math.max(0, button.getWidth());
            int height = Math.max(0, button.getHeight());
            button.setX(groupLeft + ANCHOR_X - MARGIN - Math.max(width, BUTTON));
            button.setY(currentY);
            if (width <= 0 || height <= 0) {
                continue;
            }
            currentY += height + SPACING;
            maxWidth = Math.max(width, maxWidth);
        }
        if (maxWidth == 0) {
            bounds = new Rect2i(0, 0, 0, 0);
            return;
        }
        bounds = new Rect2i(
                groupLeft + ANCHOR_X - maxWidth - 2 * MARGIN,
                groupTop + ANCHOR_Y,
                maxWidth + 2 * MARGIN,
                currentY - (groupTop + ANCHOR_Y));
    }

    public void hide() {
        layoutAgainst(0, 0, false);
    }

    public void renderBackground(GuiGraphics g, List<Rect2i> nearbyElements) {
        if (!isVisible()) {
            return;
        }
        int x = bounds.getX() - 2;
        int width = bounds.getWidth() + 1;
        int runTop = bounds.getY() - 1;
        int bottom = bounds.getY() + bounds.getHeight() + 3;
        ResourceLocation runBackground = null;
        List<Integer> corners = new ArrayList<>();

        for (int y = runTop; y < bottom; y++) {
            ResourceLocation background = hasRightNeighbor(y, x + width, nearbyElements)
                    ? ATTACHED_BACKGROUND : COMPLETE_BACKGROUND;
            if (runBackground != null && !runBackground.equals(background)) {
                renderBackgroundRun(g, runBackground, x, width, runTop, y);
                corners.add(y);
                runTop = y;
            }
            runBackground = background;
        }
        if (runBackground != null) {
            renderBackgroundRun(g, runBackground, x, width, runTop, bottom);
        }
        if (!corners.isEmpty()) {
            g.flush();
            for (int corner : corners) {
                renderCornerConnection(g, x + width, corner,
                        hasRightNeighbor(corner, x + width, nearbyElements));
            }
            g.flush();
        }
    }

    private void renderCornerConnection(GuiGraphics g, int railRight, int y, boolean attachedBelow) {
        int top = Math.max(bounds.getY() - 1, attachedBelow ? y : y - 4);
        int bottom = Math.min(bounds.getY() + bounds.getHeight() + 3, attachedBelow ? y + 2 : y);
        int edgeX = bounds.getX() - 2 + bounds.getWidth() + 2 * MARGIN - 2;
        g.fill(railRight - MARGIN, top, edgeX, bottom, 2, 0xFFCBCCD4);
        g.fill(edgeX, top, edgeX + 1, bottom, 2, 0xFFF2F2F2);
    }

    private boolean hasRightNeighbor(int y, int railRight, List<Rect2i> nearbyElements) {
        for (Rect2i element : nearbyElements) {
            if (element.getHeight() <= 0 || element.getWidth() <= 0
                    || y < element.getY() || y >= element.getY() + element.getHeight()) {
                continue;
            }
            int rightGap = element.getX() - railRight;
            if (rightGap >= -MARGIN && rightGap <= 0) {
                return true;
            }
        }
        return false;
    }

    private void renderBackgroundRun(GuiGraphics g, ResourceLocation background,
            int x, int width, int top, int bottom) {
        if (COMPLETE_BACKGROUND.equals(background)) {
            width = bounds.getWidth() + 2 * MARGIN;
        }
        g.enableScissor(x, top, x + width, bottom);
        try {
            g.blitSprite(background, x, bounds.getY() - 1, 1, width, bounds.getHeight() + 4);
        } finally {
            g.disableScissor();
        }
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!isVisible()) {
            return false;
        }
        for (Button widget : buttons) {
            if (!widget.visible) {
                continue;
            }
            if (widget.isMouseOver(mouseX, mouseY)) {
                return true;
            }
            if (widget instanceof ITooltip tooltip && tooltip.getTooltipArea().contains((int) mouseX, (int) mouseY)) {
                return true;
            }
        }
        return false;
    }

    public AbstractWidget hovered(int mouseX, int mouseY) {
        if (!isVisible()) {
            return null;
        }
        for (Button widget : buttons) {
            if (!widget.visible) {
                continue;
            }
            if (widget.isMouseOver(mouseX, mouseY)) {
                return widget;
            }
            if (widget instanceof ITooltip tooltip && tooltip.getTooltipArea().contains(mouseX, mouseY)) {
                return widget;
            }
        }
        return null;
    }

    public ITooltip hoveredTooltip(int mouseX, int mouseY) {
        AbstractWidget widget = hovered(mouseX, mouseY);
        return widget instanceof ITooltip tooltip ? tooltip : null;
    }
}
