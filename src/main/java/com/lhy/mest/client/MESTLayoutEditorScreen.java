package com.lhy.mest.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.widgets.IconButton;

import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;

/**
 * AE2-styled, full-canvas layout authoring screen.
 *
 * <p>The whole window is the editing surface: the floating module workspace renders at its real
 * coordinates on a dark, grid-patterned canvas, and every dock gesture works here — dragging
 * panels, splicing them onto each other's edge drop zones, resizing windows and right-click
 * detaching split branches. A light AE2 chrome surrounds the canvas: a toolbar on top, a module
 * palette on the left (press-and-drag an entry to place its module) and a property inspector on
 * the right (visibility / movable / resizable toggles plus interaction hints).
 *
 * <p>The terminal screen remains a runtime consumer of the saved layout; this editor commits the
 * workspace through {@link DockManager#commitLayoutEditing()} and reverts to the pre-edit
 * snapshot on cancel.
 */
public final class MESTLayoutEditorScreen extends Screen {
    private static final int TOOLBAR_HEIGHT = 28;
    private static final int SIDEBAR_WIDTH = 156;
    private static final int PROPERTY_WIDTH = 236;
    private static final int SIDEBAR_ROW_HEIGHT = 22;
    private static final int SIDEBAR_ROW_TOP = 32;
    private static final int PROPERTY_ROW_HEIGHT = 28;
    private static final int PROPERTY_ROW_TOP = 58;
    private static final int TEXT_BUTTON_WIDTH = 100;
    private static final int TEXT_BUTTON_HEIGHT = 20;

    // AE2 terminal palette (see ModulePanel) plus the dark desktop color used by the preview area.
    private static final int COLOR_CANVAS = 0xFF171B24;
    private static final int COLOR_GRID_MINOR = 0x0EFFFFFF;
    private static final int COLOR_GRID_MAJOR = 0x1CFFFFFF;
    private static final int COLOR_SEPARATOR = 0xFF777B8C;
    private static final int COLOR_SELECTION = 0x33ACE9FF;
    private static final int COLOR_HOVER = 0x22ACE9FF;
    private static final int COLOR_HINT = 0xFFB9C6E4;
    private static final int GRID_STEP = 16;
    private static final int GRID_MAJOR_EVERY = 128;

    private final Screen parent;
    private final DockManager dock;
    private final List<ModulePanel> panels;
    private final List<IconButton> toolbarButtons = new ArrayList<>();

    private ModulePanel selected;

    public MESTLayoutEditorScreen(Screen parent, DockManager dock) {
        super(Component.translatable("gui.mesplicedterminal.layout_editor"));
        this.parent = parent;
        this.dock = dock;
        this.panels = List.copyOf(dock.panels());
        dock.beginLayoutEditing();
    }

    // --- Geometry ---------------------------------------------------------

    private int canvasLeft() {
        return SIDEBAR_WIDTH;
    }

    private int canvasRight() {
        return width - PROPERTY_WIDTH;
    }

    private int canvasTop() {
        return TOOLBAR_HEIGHT;
    }

    private Rect saveRect() {
        return new Rect(width - 2 * TEXT_BUTTON_WIDTH - 24, 4, TEXT_BUTTON_WIDTH, TEXT_BUTTON_HEIGHT);
    }

    private Rect cancelRect() {
        return new Rect(width - TEXT_BUTTON_WIDTH - 12, 4, TEXT_BUTTON_WIDTH, TEXT_BUTTON_HEIGHT);
    }

    private Rect propertyToggleRect(int index) {
        return new Rect(width - PROPERTY_WIDTH + 8, PROPERTY_ROW_TOP + index * PROPERTY_ROW_HEIGHT,
                PROPERTY_WIDTH - 16, TEXT_BUTTON_HEIGHT);
    }

    // --- Lifecycle ---------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        dock.updateViewport(width, height);
        buildToolbarWidgets();
        if (selected == null || !panels.contains(selected)) {
            if (!panels.isEmpty()) {
                selected = panels.getFirst();
            }
        }
    }

    private void buildToolbarWidgets() {
        for (IconButton button : toolbarButtons) {
            removeWidget(button);
        }
        toolbarButtons.clear();
        int x = 6;
        int y = (TOOLBAR_HEIGHT - 20) / 2;
        toolbarButtons.add(toolbarButton(
                dock.isLayoutLocked() ? Icon.LOCKED : Icon.UNLOCKED,
                Component.translatable(dock.isLayoutLocked()
                        ? "gui.mesplicedterminal.unlock_layout"
                        : "gui.mesplicedterminal.lock_layout"),
                ignored -> {
                    dock.toggleLayoutLocked();
                    buildToolbarWidgets();
                },
                x, y));
        x += 22;
        toolbarButtons.add(toolbarButton(
                Icon.BACK,
                Component.translatable("gui.mesplicedterminal.undo_layout"),
                ignored -> dock.undoLayout(),
                x, y));
        x += 22;
        toolbarButtons.add(toolbarButton(
                Icon.TERMINAL_STYLE_SMALL,
                Component.translatable("gui.mesplicedterminal.compact_layout"),
                ignored -> dock.applyCompactPreset(),
                x, y));
        x += 22;
        toolbarButtons.add(toolbarButton(
                Icon.SCHEDULING_ROUND_ROBIN,
                Component.translatable("gui.mesplicedterminal.reset_layout"),
                ignored -> dock.resetLayout(),
                x, y));
    }

    private IconButton toolbarButton(Icon icon, Component tooltip, Button.OnPress onPress, int x, int y) {
        var button = new ToolbarIconButton(icon, tooltip, onPress);
        button.setX(x);
        button.setY(y);
        addRenderableWidget(button);
        return button;
    }

    private void saveAndReturn() {
        dock.commitLayoutEditing();
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancel() {
        dock.cancelLayoutEditing();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        cancel();
    }

    private void select(ModulePanel panel) {
        selected = panel;
    }

    private void toggleProperty(int index) {
        if (selected == null) {
            return;
        }
        ModuleLayoutPolicy policy = dock.policyFor(selected);
        ModuleLayoutPolicy next = switch (index) {
            case 0 -> new ModuleLayoutPolicy(!policy.visible(), policy.movable(), policy.resizable());
            case 1 -> new ModuleLayoutPolicy(policy.visible(), !policy.movable(), policy.resizable());
            default -> new ModuleLayoutPolicy(policy.visible(), policy.movable(), !policy.resizable());
        };
        dock.setModulePolicy(selected, next);
    }

    // --- Input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY < TOOLBAR_HEIGHT) {
            if (button == 0) {
                if (cancelRect().contains(mouseX, mouseY)) {
                    cancel();
                    return true;
                }
                if (saveRect().contains(mouseX, mouseY)) {
                    saveAndReturn();
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && mouseX < SIDEBAR_WIDTH) {
            int index = (int) ((mouseY - SIDEBAR_ROW_TOP) / SIDEBAR_ROW_HEIGHT);
            if (index >= 0 && index < panels.size()) {
                ModulePanel panel = panels.get(index);
                select(panel);
                // Press-and-drag a palette entry to place its module in the workspace.
                dock.startExternalDrag(panel, mouseX, mouseY);
            }
            return true;
        }
        if (mouseX >= width - PROPERTY_WIDTH) {
            if (button == 0 && selected != null) {
                for (int index = 0; index < 3; index++) {
                    if (propertyToggleRect(index).contains(mouseX, mouseY)) {
                        toggleProperty(index);
                        return true;
                    }
                }
            }
            return true;
        }

        // Workspace canvas: keep the selection in sync with the clicked module, then let the dock
        // start whatever gesture the press belongs to (drag, splice, resize, divider detach).
        ModulePanel under = dock.topLeafAt(mouseX, mouseY);
        if (under != null) {
            select(under);
        }
        if (dock.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // Consume canvas clicks so panels and chrome never interact through each other.
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Forward unconditionally: gestures started on the canvas (or from the palette) may leave
        // the canvas region mid-drag and the dock does not care where the pointer is.
        if (dock.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dock.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Scrollbars of panels under the cursor keep working while authoring the layout.
        if (mouseX >= canvasLeft() && mouseX < canvasRight() && mouseY >= canvasTop()) {
            ModulePanel panel = dock.topLeafAt(mouseX, mouseY);
            if (panel != null && panel.mouseScrolled(mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // --- Rendering ---------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw the screen background first. The vanilla/MUI background path must not run after
        // the custom editor or it will cover the canvas with the blur overlay.
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderCanvas(graphics);
        dock.renderBackground(graphics, font, mouseX, mouseY, partialTick, this::renderEditorSlotIcon);
        dock.renderForeground(graphics, font, mouseX, mouseY, partialTick);
        renderSidebar(graphics, mouseX, mouseY);
        renderProperties(graphics, mouseX, mouseY);
        renderToolbar(graphics, mouseX, mouseY);
        // Vanilla Screen does not know AE2's ITooltip protocol, so render the registered toolbar
        // widgets explicitly after the editor surface and then draw their tooltips.
        for (IconButton button : toolbarButtons) {
            button.render(graphics, mouseX, mouseY, partialTick);
        }
        renderWidgetTooltips(graphics, mouseX, mouseY);
    }

    /**
     * AE2's IconButton tooltips are not rendered by vanilla {@link Screen}; AE2 screens render
     * them manually through the {@code ITooltip} interface. Mirror that behavior here so the
     * toolbar actions explain themselves.
     */
    private void renderWidgetTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        for (IconButton button : toolbarButtons) {
            if (button.isTooltipAreaVisible() && button.getTooltipArea().contains(mouseX, mouseY)) {
                List<Component> tooltip = button.getTooltipMessage();
                if (!tooltip.isEmpty()) {
                    graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
                }
            }
        }
    }

    /**
     * Wireframe-mode slot renderer for the editor: draw the real item contents so modules stay
     * recognizable while being arranged, without the terminal screen's hover/tooltip pipeline.
     */
    private void renderEditorSlotIcon(GuiGraphics graphics, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack != null && !stack.isEmpty()) {
            graphics.renderItem(stack, slot.x, slot.y);
            graphics.renderItemDecorations(font, stack, slot.x, slot.y);
        }
    }

    /** Dark AE2 desktop with a subtle blueprint grid, like a node-editor canvas. */
    private void renderCanvas(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, COLOR_CANVAS);
        int left = canvasLeft();
        int right = canvasRight();
        int top = canvasTop();
        for (int x = left; x <= right; x += GRID_STEP) {
            graphics.fill(x, top, x + 1, height, COLOR_GRID_MINOR);
        }
        for (int y = top; y <= height; y += GRID_STEP) {
            graphics.fill(left, y, right, y + 1, COLOR_GRID_MINOR);
        }
        for (int x = left; x <= right; x += GRID_MAJOR_EVERY) {
            graphics.fill(x, top, x + 1, height, COLOR_GRID_MAJOR);
        }
        for (int y = top; y <= height; y += GRID_MAJOR_EVERY) {
            graphics.fill(left, y, right, y + 1, COLOR_GRID_MAJOR);
        }
    }

    /** Light AE2 panel holding the module palette. Entries double as drag sources. */
    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        BackgroundGenerator.draw(SIDEBAR_WIDTH, height, graphics, 0, 0);
        graphics.fill(SIDEBAR_WIDTH - 1, 0, SIDEBAR_WIDTH, height, COLOR_SEPARATOR);
        graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_modules"),
                8, 8, ModulePanel.COLOR_TITLE_TEXT, false);

        for (int i = 0; i < panels.size(); i++) {
            ModulePanel panel = panels.get(i);
            int y = SIDEBAR_ROW_TOP + i * SIDEBAR_ROW_HEIGHT;
            Rect row = new Rect(3, y, SIDEBAR_WIDTH - 6, SIDEBAR_ROW_HEIGHT - 2);
            boolean hovered = row.contains(mouseX, mouseY);
            ModuleLayoutPolicy policy = dock.policyFor(panel);
            if (panel == selected) {
                graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, COLOR_SELECTION);
            } else if (hovered) {
                graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, COLOR_HOVER);
            }
            MESTScreen.iconForPanel(panel).getBlitter().dest(row.x + 2, row.y + 3).blit(graphics);
            String title = font.plainSubstrByWidth(
                    panel.title().getString(), Math.max(0, SIDEBAR_WIDTH - 42));
            int textColor = policy.visible() ? ModulePanel.COLOR_TITLE_TEXT : ModulePanel.COLOR_MUTED;
            graphics.drawString(font, title, row.x + 22, row.y + 7, textColor, false);
            String marker = policy.visible() ? (policy.movable() ? "~" : "|") : "x";
            graphics.drawString(font, marker, row.x + row.w - 12, row.y + 7, ModulePanel.COLOR_MUTED, false);
            if (hovered && !policy.visible()) {
                graphics.renderComponentTooltip(font,
                        List.of(Component.translatable("gui.mesplicedterminal.layout_palette_hidden")),
                        mouseX, mouseY);
            }
        }
    }

    /** Light AE2 panel with the selected module's runtime policies and the interaction hints. */
    private void renderProperties(GuiGraphics graphics, int mouseX, int mouseY) {
        int px = width - PROPERTY_WIDTH;
        BackgroundGenerator.draw(PROPERTY_WIDTH, height, graphics, px, 0);
        graphics.fill(px - 1, 0, px, height, COLOR_SEPARATOR);
        graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_properties"),
                px + 8, 8, ModulePanel.COLOR_TITLE_TEXT, false);
        if (selected == null) {
            return;
        }

        String name = font.plainSubstrByWidth(selected.title().getString(), PROPERTY_WIDTH - 20);
        graphics.drawString(font, name, px + 8, 30, ModulePanel.COLOR_TITLE_TEXT, false);

        ModuleLayoutPolicy policy = dock.policyFor(selected);
        drawPropertyToggle(graphics, mouseX, mouseY, 0,
                Component.translatable("gui.mesplicedterminal.layout_state_visible"), policy.visible());
        drawPropertyToggle(graphics, mouseX, mouseY, 1,
                Component.translatable("gui.mesplicedterminal.layout_state_movable"), policy.movable());
        drawPropertyToggle(graphics, mouseX, mouseY, 2,
                Component.translatable("gui.mesplicedterminal.layout_state_resizable"), policy.resizable());

        int hintY = PROPERTY_ROW_TOP + 3 * PROPERTY_ROW_HEIGHT + 10;
        for (String key : List.of(
                "gui.mesplicedterminal.layout_hint_drag",
                "gui.mesplicedterminal.layout_hint_splice",
                "gui.mesplicedterminal.layout_hint_detach",
                "gui.mesplicedterminal.layout_hint_resize",
                "gui.mesplicedterminal.layout_hint_palette")) {
            hintY = drawWrapped(graphics, Component.translatable(key), px + 8, hintY,
                    PROPERTY_WIDTH - 20, COLOR_HINT);
            hintY += 4;
        }
    }

    private void drawPropertyToggle(GuiGraphics graphics, int mouseX, int mouseY, int index,
            Component label, boolean enabled) {
        Rect rect = propertyToggleRect(index);
        Component state = Component.translatable(enabled
                ? "gui.mesplicedterminal.layout_yes"
                : "gui.mesplicedterminal.layout_no");
        ModulePanel.drawButton(graphics, font, label.copy().append(": ").append(state),
                rect.x, rect.y, rect.w, rect.h, rect.contains(mouseX, mouseY));
    }

    /** Light AE2 strip across the top: icon actions, title and save/cancel buttons. */
    private void renderToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        BackgroundGenerator.draw(width, TOOLBAR_HEIGHT, graphics, 0, 0);
        graphics.fill(0, TOOLBAR_HEIGHT - 2, width, TOOLBAR_HEIGHT - 1, ModulePanel.COLOR_DARK);
        graphics.fill(0, TOOLBAR_HEIGHT - 1, width, TOOLBAR_HEIGHT, ModulePanel.COLOR_LIGHT);
        graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_editor"),
                6 + 4 * 22 + 8, 9, ModulePanel.COLOR_TITLE_TEXT, false);

        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_save"),
                saveRect().x, saveRect().y, saveRect().w, saveRect().h,
                saveRect().contains(mouseX, mouseY));
        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_cancel"),
                cancelRect().x, cancelRect().y, cancelRect().w, cancelRect().h,
                cancelRect().contains(mouseX, mouseY));
    }

    /** Draws {@code text} wrapped to {@code maxWidth} pixels; returns the y just below the block. */
    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        String remaining = text.getString();
        int currentY = y;
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, maxWidth);
            if (line.isEmpty()) {
                break;
            }
            graphics.drawString(font, line, x, currentY, color, false);
            currentY += font.lineHeight + 1;
            remaining = remaining.substring(line.length());
            if (remaining.startsWith(" ")) {
                remaining = remaining.substring(1);
            }
        }
        return currentY;
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    private static final class ToolbarIconButton extends IconButton {
        private final Icon icon;

        ToolbarIconButton(Icon icon, Component tooltip, OnPress onPress) {
            super(onPress);
            this.icon = icon;
            setMessage(tooltip);
        }

        @Override
        protected Icon getIcon() {
            return icon;
        }
    }
}
