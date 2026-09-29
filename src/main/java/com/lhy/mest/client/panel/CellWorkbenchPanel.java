package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

import com.lhy.mest.MESplicedterminal;
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
 * AE2's cell workbench as a module: the edited cell and its 9x7 partition. AE2's tool bar and the
 * cell's upgrade slots share one side bar that hangs outside the window, on the left unless another
 * section sits there, then on the right; inside the terminal group it moves into the panel's right
 * edge when there is room for it.
 */
public class CellWorkbenchPanel extends ModulePanel {
    public static final String ID = "cell_workbench";
    private static final int SLOT = 18;
    private static final int COLS = 9;
    private static final int ROWS = 7;
    private static final int GRID_TOP = 22;
    private static final int GRID_W = COLS * SLOT;
    private static final int CONTENT_H = GRID_TOP + ROWS * SLOT;
    private static final int COLUMN_W = SLOT + 2 * PanelSideBar.MARGIN;
    /** AE2's vertical tool bar tucks this far under the window frame. */
    private static final int BAR_OVERLAP = PanelSideBar.ANCHOR_X;
    private static final int UPGRADE_GAP = 2;
    private static final int SLOT_OUTLINE = 0xFFF2F2F2;
    private static final ResourceLocation BAR_SPRITE = AppEng.makeId("vertical_buttons_bg");
    private static final ResourceLocation MIRRORED_BAR_SPRITE = ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "vertical_buttons_bg");

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
    private int barColumns;
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

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + GRID_W;
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
        return visible && side() == Side.INSIDE ? columnsFor(Side.INSIDE) * COLUMN_W + 2 : 0;
    }

    @Override
    public int outsideHitLeftWidth() {
        return visible && side() == Side.LEFT ? columnsFor(Side.LEFT) * COLUMN_W - BAR_OVERLAP : 0;
    }

    @Override
    public int outsideHitWidth() {
        return visible && side() == Side.RIGHT ? columnsFor(Side.RIGHT) * COLUMN_W - BAR_OVERLAP : 0;
    }

    private Side side() {
        Side preferred = !inFloatingWindow() ? Side.INSIDE
                : leftmostInWindow ? Side.LEFT
                : rightmostInWindow ? Side.RIGHT
                : Side.INSIDE;
        // The dock fixes the minimum width once, so the inside bar only fits a wider section.
        if (preferred == Side.INSIDE
                && width - 2 * CONTENT_PADDING < GRID_W + columnsFor(Side.INSIDE) * COLUMN_W + 2) {
            if (rightmostInWindow) {
                return Side.RIGHT;
            }
            if (leftmostInWindow) {
                return Side.LEFT;
            }
        }
        return preferred;
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
        layoutBar();
    }

    private void updateWidgets() {
        for (AbstractWidget widget : widgets) {
            widget.visible = true;
            widget.active = true;
        }
        copyMode.setState(menu.workbenchCopyMode == CopyMode.CLEAR_ON_REMOVE);
        fuzzyMode.set(menu.workbenchFuzzyMode);
        fuzzyMode.setVisibility(logic.hasFuzzyCard());
        ItemStack cell = logic.currentStack();
        for (CellWorkbenchButton extension : extensions) {
            extension.update(cell.copy());
        }
    }

    private int barTop(Side side) {
        return side == Side.INSIDE ? contentTop() : y + PanelSideBar.ANCHOR_Y;
    }

    private int barBottom(Side side) {
        return side == Side.INSIDE ? y + height - CONTENT_PADDING : y + height;
    }

    private int columnX(Side side, int column) {
        return switch (side) {
            case LEFT -> x + BAR_OVERLAP - (column + 1) * COLUMN_W;
            case RIGHT -> x + width - BAR_OVERLAP + column * COLUMN_W;
            case INSIDE -> x + width - CONTENT_PADDING - (column + 1) * COLUMN_W;
        };
    }

    private int columnsFor(Side side) {
        return flow(side, false);
    }

    private void layoutBar() {
        updateWidgets();
        barColumns = flow(side(), true);
    }

    /**
     * {@code VerticalButtonBar} followed by the upgrade slots, wrapping into further columns
     * (away from the grid) when the panel is not tall enough. Returns the column count.
     */
    private int flow(Side side, boolean place) {
        int top = barTop(side) + PanelSideBar.MARGIN;
        int bottom = barBottom(side) - PanelSideBar.MARGIN;
        int column = 0;
        int currentY = top;
        int used = 0;
        boolean columnEmpty = true;
        for (AbstractWidget widget : widgets) {
            if (!widget.visible) {
                continue;
            }
            int height = widget.getHeight();
            if (!columnEmpty && currentY + height > bottom) {
                column++;
                currentY = top;
            }
            if (place) {
                widget.setX(columnX(side, column) + (COLUMN_W - widget.getWidth()) / 2);
                widget.setY(currentY);
            }
            currentY += height + PanelSideBar.SPACING;
            used = Math.max(used, currentY - PanelSideBar.SPACING);
            columnEmpty = false;
        }
        var upgrades = menu.getCellWorkbenchUpgradeSlots();
        if (!columnEmpty) {
            currentY += UPGRADE_GAP - PanelSideBar.SPACING;
        }
        for (int i = 0; i < upgrades.size(); i++) {
            if (!logic.isUpgradeSlotEnabled(i)) {
                continue;
            }
            if (!columnEmpty && currentY + SLOT > bottom) {
                column++;
                currentY = top;
            }
            if (place) {
                placeSlot(upgrades.get(i), columnX(side, column) + PanelSideBar.MARGIN + 1, currentY + 1);
            }
            currentY += SLOT;
            used = Math.max(used, currentY);
            columnEmpty = false;
        }
        if (place) {
            barHeight = columnEmpty ? 0 : used + PanelSideBar.MARGIN - barTop(side);
        }
        return columnEmpty ? 0 : column + 1;
    }

    @Override
    public void renderUnderlay(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (visible && side() != Side.INSIDE) {
            drawBarBackground(g, side());
        }
    }

    private void drawBarBackground(GuiGraphics g, Side side) {
        if (barColumns == 0 || barHeight <= 0) {
            return;
        }
        int w = barColumns * COLUMN_W;
        int top = barTop(side);
        switch (side) {
            case LEFT -> g.blitSprite(BAR_SPRITE, columnX(side, barColumns - 1) - 2, top - 1, 1, w + 1, barHeight + 4);
            case RIGHT -> g.blitSprite(MIRRORED_BAR_SPRITE, columnX(side, 0) + 1, top - 1, 1, w + 1, barHeight + 4);
            case INSIDE -> g.blitSprite(BAR_SPRITE, columnX(side, barColumns - 1), top, 1, w, barHeight);
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        layoutSlots();
        if (side() == Side.INSIDE) {
            drawBarBackground(g, Side.INSIDE);
        }
        int left = contentLeft();
        int top = contentTop();
        drawOutlinedSlot(g, left, top);
        ItemStack cell = logic.currentStack();
        if (cell.isEmpty()) {
            Icon.BACKGROUND_STORAGE_CELL.getBlitter().dest(left + 1, top + 1).blit(g);
        }
        int textX = left + SLOT + 4;
        int textWidth = Math.max(0, left + GRID_W - textX);
        Component name = cell.isEmpty()
                ? Component.translatable("gui.mesplicedterminal.cell_workbench.empty")
                : cell.getHoverName();
        g.drawString(font, font.plainSubstrByWidth(name.getString(), textWidth), textX, top + 1,
                COLOR_TITLE_TEXT, false);
        if (menu.workbenchRemote) {
            g.drawString(font, font.plainSubstrByWidth(
                    Component.translatable("gui.mesplicedterminal.cell_workbench.remote").getString(), textWidth),
                    textX, top + 10, COLOR_MUTED, false);
        }
        for (int i = 0; i < CellWorkbenchLogic.CONFIG_SLOTS; i++) {
            int px = left + (i % COLS) * SLOT;
            int py = top + GRID_TOP + (i / COLS) * SLOT;
            // AEBaseScreen.drawOptionalSlotBackground: a disabled partition slot is a faint well.
            Icon.SLOT_BACKGROUND.getBlitter().dest(px, py)
                    .color(1, 1, 1, logic.isPartitionSlotEnabled(i) ? 1.0F : 0.2F)
                    .blit(g);
        }
        for (Slot slot : menu.getCellWorkbenchUpgradeSlots()) {
            if (slot.x <= -1000) {
                continue;
            }
            drawSlot(g, slot.x - 1, slot.y - 1);
            if (!slot.hasItem()) {
                Icon.BACKGROUND_UPGRADE.getBlitter().dest(slot.x, slot.y).blit(g);
            }
        }
        for (AbstractWidget widget : widgets) {
            widget.render(g, mouseX, mouseY, partialTicks);
        }
    }

    /** The recessed well with the white rim AE2 bakes into the workbench's cell slot. */
    private static void drawOutlinedSlot(GuiGraphics g, int px, int py) {
        drawSlot(g, px, py);
        int x0 = px + 1;
        int y0 = py + 1;
        int x1 = px + SLOT - 2;
        int y1 = py + SLOT - 2;
        g.hLine(x0, x1, y0, SLOT_OUTLINE);
        g.hLine(x0, x1, y1, SLOT_OUTLINE);
        g.vLine(x0, y0, y1, SLOT_OUTLINE);
        g.vLine(x1, y0, y1, SLOT_OUTLINE);
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
