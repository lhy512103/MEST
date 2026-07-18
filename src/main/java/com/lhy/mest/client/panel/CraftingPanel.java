package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.Icon;
import appeng.core.AppEng;
import appeng.core.localization.ButtonToolTips;
import appeng.menu.SlotSemantics;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Floating panel hosting the 3x3 crafting matrix and its result slot. The slots already live in the
 * {@link MESTMenu} (a CraftingTermMenu); this panel just re-homes them into its content area, so the
 * full AE2 crafting behaviour (recipe matching, shift-craft, pull from network) works unchanged.
 */
public class CraftingPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int ACTION_SIZE = 10;

    private final MESTMenu menu;
    private final List<Slot> gridSlots;
    private final List<Slot> resultSlots;

    public CraftingPanel(MESTMenu menu) {
        this.menu = menu;
        this.gridSlots = menu.getSlots(SlotSemantics.CRAFTING_GRID);
        this.resultSlots = menu.getSlots(SlotSemantics.CRAFTING_RESULT);
        for (Slot s : gridSlots) {
            registerSlot(s);
        }
        for (Slot s : resultSlots) {
            registerSlot(s);
        }
    }

    @Override
    public String id() {
        return "crafting";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.crafting");
    }

    @Override
    public int defaultWidth() {
        // 3x3 grid + arrow gap + result slot.
        return 2 * CONTENT_PADDING + 3 * SLOT + 24 + SLOT;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + 3 * SLOT;
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
    public void layoutSlots() {
        if (!visible) {
            for (Slot s : ownedSlots()) {
                s.x = -9999;
                s.y = -9999;
            }
            return;
        }
        int left = contentLeft();
        int top = contentTop();
        for (int i = 0; i < gridSlots.size(); i++) {
            Slot s = gridSlots.get(i);
            s.x = left + (i % 3) * SLOT;
            s.y = top + (i / 3) * SLOT;
        }
        // Result slot sits to the right of the grid, vertically centred.
        int resultX = left + 3 * SLOT + 24;
        int resultY = top + SLOT;
        for (Slot s : resultSlots) {
            s.x = resultX;
            s.y = resultY;
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        for (Slot s : gridSlots) {
            ModulePanel.drawSlot(g, s.x - 1, s.y - 1);
        }
        // Arrow between grid and result.
        int arrowY = contentTop() + SLOT + 4;
        int arrowX = contentLeft() + 3 * SLOT + 4;
        ModulePanel.drawCraftingArrow(g, arrowX, arrowY);
        for (Slot s : resultSlots) {
            ModulePanel.drawSlot(g, s.x - 1, s.y - 1);
        }
    }

    @Override
    public void renderForegroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (hosted) {
            return;
        }
        Rect toPlayer = toPlayerButton();
        Rect toNetwork = toNetworkButton();
        drawActionButton(g, toPlayer, Icon.S_ARROW_DOWN, toPlayer.contains(mouseX, mouseY));
        drawActionButton(g, toNetwork, Icon.S_ARROW_UP, toNetwork.contains(mouseX, mouseY));

        if (toPlayer.contains(mouseX, mouseY)) {
            g.renderComponentTooltip(font,
                    List.of(ButtonToolTips.StashToPlayer.text(), ButtonToolTips.StashToPlayerDesc.text()),
                    mouseX, mouseY);
        } else if (toNetwork.contains(mouseX, mouseY)) {
            g.renderComponentTooltip(font,
                    List.of(ButtonToolTips.Stash.text(), ButtonToolTips.StashDesc.text()),
                    mouseX, mouseY);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || hosted || button != 0) {
            return false;
        }
        if (toPlayerButton().contains(mouseX, mouseY)) {
            menu.clearToPlayerInventory();
            return true;
        }
        if (toNetworkButton().contains(mouseX, mouseY)) {
            menu.clearCraftingGrid();
            return true;
        }
        return false;
    }

    private void drawActionButton(GuiGraphics g, Rect rect, Icon icon, boolean hovered) {
        g.blitSprite(AppEng.makeId(hovered ? "button_highlighted" : "button"),
                rect.x(), rect.y(), rect.width(), rect.height());
        icon.getBlitter().dest(rect.x() + 1, rect.y() + 1).blit(g);
    }

    private Rect toNetworkButton() {
        return new Rect(x + width - 2 * ACTION_SIZE - 5, y + 4, ACTION_SIZE, ACTION_SIZE);
    }

    private Rect toPlayerButton() {
        return new Rect(x + width - ACTION_SIZE - 3, y + 4, ACTION_SIZE, ACTION_SIZE);
    }

    private record Rect(int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}
