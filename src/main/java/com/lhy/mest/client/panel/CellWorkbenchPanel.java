package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.ActionItems;
import appeng.api.config.CopyMode;
import appeng.api.config.FuzzyMode;
import appeng.api.config.Settings;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.AppEng;
import appeng.core.localization.GuiText;

import com.lhy.mest.api.client.CellWorkbenchButton;
import com.lhy.mest.api.client.CellWorkbenchButtonContext;
import com.lhy.mest.client.CellWorkbenchButtons;
import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.MestGuiIcons;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.PanelSideBar;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import com.lhy.mest.terminal.MESTMenu;
import com.lhy.mest.terminal.workbench.CellWorkbenchLogic;

/**
 * AE2's cell workbench as a module: the edited cell, its 9x7 partition and its upgrade cards. AE2's
 * left tool bar hangs outside the window, on the left unless another section sits there, then on
 * the right; inside the terminal group it moves into the panel's right edge.
 */
public class CellWorkbenchPanel extends ModulePanel {
    public static final String ID = "cell_workbench";
    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int ROWS = 7;
    private static final int GRID_TOP = 22;
    private static final int UPGRADE_GAP = 4;
    private static final int CONTENT_W = COLS * SLOT + UPGRADE_GAP + SLOT;
    private static final int CONTENT_H = GRID_TOP + ROWS * SLOT;
    private static final int BAR_W = PanelSideBar.BUTTON + 2 * PanelSideBar.MARGIN;
    private static final int BAR_INSET = BAR_W + 2;
    /** AE2's vertical tool bar tucks this far under the window frame. */
    private static final int BAR_OVERLAP = PanelSideBar.ANCHOR_X;
    private static final int DISABLED_SLOT = 0x80373737;

    private enum Side {
        LEFT, RIGHT, INSIDE
    }

    private final MESTMenu menu;
    private final CellWorkbenchLogic logic;
    private final SettingToggleButton<FuzzyMode> fuzzyMode;
    private final ToggleButton copyMode;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<CellWorkbenchButton> extensions = new ArrayList<>();
    private boolean rightClick;
    private int barTop;
    private int barHeight;

    public CellWorkbenchPanel(MESTMenu menu) {
        this.menu = menu;
        this.logic = menu.cellWorkbench();
        for (Slot slot : menu.getCellWorkbenchCellSlots()) {
            registerSlot(slot);
        }
        for (Slot slot : menu.getCellWorkbenchConfigSlots()) {
            registerSlot(slot);
        }
        for (Slot slot : menu.getCellWorkbenchUpgradeSlots()) {
            registerSlot(slot);
        }
        fuzzyMode = new SettingToggleButton<>(Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL,
                (button, backwards) -> menu.setWorkbenchFuzzyMode(button.getNextValue(backwards)));
        widgets.add(fuzzyMode);
        widgets.add(new ActionButton(ActionItems.COG, menu::workbenchPartition));
        widgets.add(new ActionButton(ActionItems.CLOSE, menu::workbenchClear));
        copyMode = new ToggleButton(Icon.COPY_MODE_ON, Icon.COPY_MODE_OFF,
                GuiText.CopyMode.text(), GuiText.CopyModeDesc.text(), state -> menu.workbenchNextCopyMode());
        widgets.add(copyMode);
        var context = new Context();
        for (var factory : CellWorkbenchButtons.factories()) {
            CellWorkbenchButton button = factory.create(context);
            if (button != null && button.widget() != null) {
                extensions.add(button);
                widgets.add(button.widget());
            }
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.cell_workbench");
    }

    @Override
    public void renderIcon(GuiGraphics g, int x, int y, int w, int h, float opacity) {
        MestGuiIcons.blit(g, 13, 0, x, y, w, h, opacity);
    }

    @Override
    public ModuleDefaults defaults() {
        return new ModuleDefaults(false, true, false, false);
    }

    // Room for the tool bar inside the panel is always reserved: the dock reads the minimum once.
    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + CONTENT_W + BAR_INSET;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_H + CONTENT_PADDING;
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
    public int preferredContentRightInset() {
        return visible && side() == Side.INSIDE ? BAR_INSET : 0;
    }

    @Override
    public int outsideHitLeftWidth() {
        return visible && side() == Side.LEFT ? BAR_W - BAR_OVERLAP : 0;
    }

    @Override
    public int outsideHitWidth() {
        return visible && side() == Side.RIGHT ? BAR_W - BAR_OVERLAP : 0;
    }

    private Side side() {
        if (!inFloatingWindow()) {
            return Side.INSIDE;
        }
        if (leftmostInWindow) {
            return Side.LEFT;
        }
        return rightmostInWindow ? Side.RIGHT : Side.INSIDE;
    }

    public void tick() {
        layoutSlots();
    }

    @Override
    public void layoutSlots() {
        for (Slot slot : ownedSlots()) {
            hideSlot(slot);
        }
        if (!visible) {
            for (AbstractWidget widget : widgets) {
                widget.visible = false;
            }
            return;
        }
        int left = contentLeft();
        int top = contentTop();
        for (Slot slot : menu.getCellWorkbenchCellSlots()) {
            placeSlot(slot, left + 1, top + 1);
        }
        var config = menu.getCellWorkbenchConfigSlots();
        for (int i = 0; i < config.size(); i++) {
            if (logic.isPartitionSlotEnabled(i)) {
                placeSlot(config.get(i), left + 1 + (i % COLS) * SLOT, top + GRID_TOP + 1 + (i / COLS) * SLOT);
            }
        }
        var upgrades = menu.getCellWorkbenchUpgradeSlots();
        for (int i = 0; i < upgrades.size(); i++) {
            if (logic.isUpgradeSlotEnabled(i)) {
                placeSlot(upgrades.get(i), upgradeX() + 1, top + 1 + i * SLOT);
            }
        }
        layoutBar();
    }

    private int upgradeX() {
        return contentLeft() + contentWidth() - SLOT;
    }

    private int barX() {
        return switch (side()) {
            case LEFT -> x + BAR_OVERLAP - BAR_W;
            case RIGHT -> x + width - BAR_OVERLAP;
            case INSIDE -> x + width - CONTENT_PADDING - BAR_W;
        };
    }

    private void updateWidgets() {
        copyMode.setState(menu.workbenchCopyMode == CopyMode.CLEAR_ON_REMOVE);
        fuzzyMode.set(menu.workbenchFuzzyMode);
        fuzzyMode.setVisibility(logic.hasFuzzyCard());
        ItemStack cell = logic.currentStack();
        for (CellWorkbenchButton extension : extensions) {
            extension.update(cell.copy());
        }
    }

    /** {@code VerticalButtonBar}: hidden buttons take no space. */
    private void layoutBar() {
        for (AbstractWidget widget : widgets) {
            widget.visible = true;
            widget.active = true;
        }
        updateWidgets();
        int barX = barX();
        barTop = side() == Side.INSIDE ? contentTop() : y + PanelSideBar.ANCHOR_Y;
        int currentY = barTop + PanelSideBar.MARGIN;
        for (AbstractWidget widget : widgets) {
            if (!widget.visible) {
                continue;
            }
            widget.setX(barX + PanelSideBar.MARGIN);
            widget.setY(currentY);
            currentY += widget.getHeight() + PanelSideBar.SPACING;
        }
        barHeight = currentY - barTop;
    }

    @Override
    public void renderUnderlay(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (visible && side() != Side.INSIDE) {
            drawBarBackground(g);
        }
    }

    private void drawBarBackground(GuiGraphics g) {
        if (barHeight <= PanelSideBar.MARGIN) {
            return;
        }
        g.blitSprite(AppEng.makeId("vertical_buttons_bg"), barX() - 2, barTop - 1, 1, BAR_W + 1, barHeight + 4);
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        layoutSlots();
        if (side() == Side.INSIDE) {
            drawBarBackground(g);
        }
        int left = contentLeft();
        int top = contentTop();
        drawSlot(g, left, top);
        ItemStack cell = logic.currentStack();
        if (cell.isEmpty()) {
            Icon.BACKGROUND_STORAGE_CELL.getBlitter().dest(left + 1, top + 1).blit(g);
        }
        int textWidth = Math.max(0, upgradeX() - UPGRADE_GAP - (left + SLOT + 4));
        Component name = cell.isEmpty()
                ? Component.translatable("gui.mesplicedterminal.cell_workbench.empty")
                : cell.getHoverName();
        g.drawString(font, font.plainSubstrByWidth(name.getString(), textWidth), left + SLOT + 4, top + 1,
                COLOR_TITLE_TEXT, false);
        if (menu.workbenchRemote) {
            g.drawString(font, font.plainSubstrByWidth(
                    Component.translatable("gui.mesplicedterminal.cell_workbench.remote").getString(), textWidth),
                    left + SLOT + 4, top + 10, COLOR_MUTED, false);
        }
        for (int i = 0; i < CellWorkbenchLogic.CONFIG_SLOTS; i++) {
            int px = left + (i % COLS) * SLOT;
            int py = top + GRID_TOP + (i / COLS) * SLOT;
            drawSlot(g, px, py);
            if (!logic.isPartitionSlotEnabled(i)) {
                g.fill(px + 1, py + 1, px + SLOT - 1, py + SLOT - 1, DISABLED_SLOT);
            }
        }
        var upgrades = menu.getCellWorkbenchUpgradeSlots();
        for (int i = 0; i < upgrades.size(); i++) {
            if (!logic.isUpgradeSlotEnabled(i)) {
                continue;
            }
            int py = top + i * SLOT;
            drawSlot(g, upgradeX(), py);
            if (!upgrades.get(i).hasItem()) {
                Icon.BACKGROUND_UPGRADE.getBlitter().dest(upgradeX() + 1, py + 1).blit(g);
            }
        }
        for (AbstractWidget widget : widgets) {
            widget.render(g, mouseX, mouseY, partialTicks);
        }
    }

    public ITooltip hoveredTooltip(int mouseX, int mouseY) {
        if (!visible) {
            return null;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.visible && widget.isMouseOver(mouseX, mouseY) && widget instanceof ITooltip tooltip
                    && tooltip.isTooltipAreaVisible() && !tooltip.getTooltipMessage().isEmpty()) {
                return tooltip;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || (button != 0 && button != 1)) {
            return false;
        }
        for (AbstractWidget widget : widgets) {
            if (!widget.visible || !widget.isMouseOver(mx, my)) {
                continue;
            }
            if (button == 0) {
                return widget.mouseClicked(mx, my, 0);
            }
            BooleanSupplier click = () -> widget.mouseClicked(mx, my, 0);
            rightClick = true;
            try {
                return Minecraft.getInstance().screen instanceof MESTScreen screen
                        ? screen.clickAsRightClick(click)
                        : click.getAsBoolean();
            } finally {
                rightClick = false;
            }
        }
        return false;
    }

    private final class Context implements CellWorkbenchButtonContext {
        @Override
        public ItemStack cell() {
            return logic.currentStack().copy();
        }

        @Override
        public boolean isRemote() {
            return menu.workbenchRemote;
        }

        @Override
        public boolean isRightClick() {
            return rightClick;
        }

        @Override
        public void sendAction(String actionId, boolean alternate) {
            menu.runWorkbenchAction(actionId, alternate);
        }
    }
}
