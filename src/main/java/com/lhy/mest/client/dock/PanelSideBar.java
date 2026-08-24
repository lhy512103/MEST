package com.lhy.mest.client.dock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;

import appeng.client.gui.widgets.ITooltip;
import appeng.core.AppEng;

/**
 * AE2 {@code VerticalButtonBar} equivalent. It is not a dock module: the terminal screen
 * attaches it to the left of the non-floating spliced group at runtime, matching vanilla.
 */
public class PanelSideBar {
    public static final int BUTTON = 16;
    public static final int SPACING = 6;
    public static final int MARGIN = 2;
    public static final int ANCHOR_X = 3;
    public static final int ANCHOR_Y = 1;

    private final List<Button> buttons = new ArrayList<>();
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    public void add(Button button) {
        buttons.add(button);
    }

    public List<Button> buttons() {
        return buttons;
    }

    public Rect2i bounds() {
        return bounds;
    }

    public boolean isVisible() {
        return bounds.getWidth() > 0 && bounds.getHeight() > 0;
    }

    public void layoutAgainst(int groupLeft, int groupTop, boolean show) {
        int currentY = groupTop + ANCHOR_Y + MARGIN;
        int maxWidth = 0;
        for (Button button : buttons) {
            if (!show) {
                button.visible = false;
                continue;
            }
            if (!button.visible) {
                continue;
            }
            button.active = true;
            button.setX(groupLeft + ANCHOR_X - MARGIN - button.getWidth());
            button.setY(currentY);
            currentY += button.getHeight() + SPACING;
            maxWidth = Math.max(button.getWidth(), maxWidth);
        }
        if (!show || maxWidth == 0) {
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

    public void renderBackground(GuiGraphics g) {
        if (!isVisible()) {
            return;
        }
        g.blitSprite(
                AppEng.makeId("vertical_buttons_bg"),
                bounds.getX() - 2,
                bounds.getY() - 1,
                1,
                bounds.getWidth() + 1,
                bounds.getHeight() + 4);
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        for (Button widget : buttons) {
            if (widget.visible && widget.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    public AbstractWidget hovered(int mouseX, int mouseY) {
        for (Button widget : buttons) {
            if (widget.visible && widget.isMouseOver(mouseX, mouseY)) {
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