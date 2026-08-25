package com.lhy.mest.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AECheckbox;
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
 * the right (visibility / movable / resizable / floating toggles plus interaction hints).
 * “Center on return” lives on the toolbar: when enabled, Save recenters every visible
 * non-floating panel as one group.
 *
 * <p>Both sidebars stay just wide enough for their labels and can collapse to an icon rail. The
 * terminal screen remains a runtime consumer of the saved layout; this editor commits the
 * workspace through {@link DockManager#commitLayoutEditing()} and reverts to the pre-edit
 * snapshot on cancel.
 */
public final class MESTLayoutEditorScreen extends Screen {
    private static final int TOOLBAR_HEIGHT = 28;
    private static final int COLLAPSED_WIDTH = 26;
    private static final int SIDEBAR_PAD = 6;
    private static final int ICON_SIZE = 16;
    private static final int TOGGLE_SIZE = 18;
    private static final int SIDEBAR_ROW_HEIGHT = 22;
    private static final int SIDEBAR_HEADER_TOP = TOOLBAR_HEIGHT + 4;
    private static final int SIDEBAR_ROW_TOP = SIDEBAR_HEADER_TOP + SIDEBAR_ROW_HEIGHT;
    private static final int PROPERTY_COUNT = 5;
    private static final int PROPERTY_ROW_HEIGHT = 18;
    private static final int PROPERTY_ROW_TOP = SIDEBAR_ROW_TOP + 20;
    private static final int CHECK_H = 14;
    private static final int TEXT_BUTTON_HEIGHT = 20;
    private static final int TEXT_BUTTON_PAD = 12;
    /** Above every dock root layer so editor chrome is never punched through by item icons. */
    private static final float CHROME_Z = 4000.0F;

    private static final int COLOR_CANVAS = 0xFF171B24;
    private static final int COLOR_GRID_MINOR = 0x0EFFFFFF;
    private static final int COLOR_GRID_MAJOR = 0x1CFFFFFF;
    private static final int COLOR_SEPARATOR = 0xFF777B8C;
    private static final int COLOR_SELECTION = 0x33ACE9FF;
    private static final int COLOR_HOVER = 0x22ACE9FF;
    private static final int GRID_STEP = 16;
    private static final int GRID_MAJOR_EVERY = 128;

    private static final List<String> HINT_KEYS = List.of(
            "gui.mesplicedterminal.layout_hint_drag",
            "gui.mesplicedterminal.layout_hint_move_all",
            "gui.mesplicedterminal.layout_hint_splice",
            "gui.mesplicedterminal.layout_hint_detach",
            "gui.mesplicedterminal.layout_hint_resize",
            "gui.mesplicedterminal.layout_hint_palette");

    private final Screen parent;
    private final DockManager dock;
    private final ScreenStyle style;
    private final List<ModulePanel> panels;
    private final List<IconButton> toolbarButtons = new ArrayList<>();
    private final AECheckbox[] propertyChecks = new AECheckbox[PROPERTY_COUNT];

    private ModulePanel selected;
    private boolean sidebarCollapsed;
    private boolean propertiesCollapsed;
    private ModulePanel pendingPalette;
    private double pendingPaletteX;
    private double pendingPaletteY;
    private boolean paletteDragStarted;

    public MESTLayoutEditorScreen(Screen parent, DockManager dock) {
        super(Component.translatable("gui.mesplicedterminal.layout_editor"));
        this.parent = parent;
        this.dock = dock;
        this.style = parent instanceof AEBaseScreen<?> screen ? screen.getStyle() : null;
        this.panels = List.copyOf(dock.panels());
        dock.beginLayoutEditing();
        if (style != null) {
            for (int index = 0; index < PROPERTY_COUNT; index++) {
                int property = index;
                AECheckbox checkbox = new AECheckbox(0, 0, 120, CHECK_H, style, propertyLabel(index));
                checkbox.setChangeListener(() -> applyProperty(property, checkbox.isSelected()));
                propertyChecks[index] = checkbox;
            }
        }
    }

    // --- Geometry ---------------------------------------------------------

    private int sidebarWidth() {
        if (sidebarCollapsed) {
            return COLLAPSED_WIDTH;
        }
        int maxText = measuredWidth(Component.translatable("gui.mesplicedterminal.layout_modules"));
        for (ModulePanel panel : panels) {
            maxText = Math.max(maxText, measuredWidth(panel.title()));
        }
        return SIDEBAR_PAD + ICON_SIZE + 4 + maxText + 4 + TOGGLE_SIZE + SIDEBAR_PAD;
    }

    private int propertyWidth() {
        if (propertiesCollapsed) {
            return COLLAPSED_WIDTH;
        }
        int maxText = measuredWidth(Component.translatable("gui.mesplicedterminal.layout_properties"));
        if (selected != null) {
            maxText = Math.max(maxText, measuredWidth(selected.title()));
        }
        for (int index = 0; index < PROPERTY_COUNT; index++) {
            maxText = Math.max(maxText, measuredWidth(propertyLabel(index)));
        }
        return SIDEBAR_PAD + 26 + maxText + SIDEBAR_PAD;
    }

    private int measuredWidth(Component text) {
        return font == null ? text.getString().length() * 6 : font.width(text);
    }

    private int canvasLeft() {
        return sidebarWidth();
    }

    private int canvasRight() {
        return width - propertyWidth();
    }

    private int canvasTop() {
        return TOOLBAR_HEIGHT;
    }

    private Rect cancelRect() {
        Component label = Component.translatable("gui.mesplicedterminal.layout_cancel");
        int buttonWidth = measuredWidth(label) + TEXT_BUTTON_PAD;
        return new Rect(width - buttonWidth - 8, 4, buttonWidth, TEXT_BUTTON_HEIGHT);
    }

    private Rect saveRect() {
        Component label = Component.translatable("gui.mesplicedterminal.layout_save");
        int buttonWidth = measuredWidth(label) + TEXT_BUTTON_PAD;
        Rect cancel = cancelRect();
        return new Rect(cancel.x - 6 - buttonWidth, 4, buttonWidth, TEXT_BUTTON_HEIGHT);
    }

    private Rect undoRect() {
        Component label = Component.translatable("gui.mesplicedterminal.layout_undo");
        int buttonWidth = measuredWidth(label) + TEXT_BUTTON_PAD;
        Rect save = saveRect();
        return new Rect(save.x - 6 - buttonWidth, 4, buttonWidth, TEXT_BUTTON_HEIGHT);
    }

    private Rect centerOnReturnRect() {
        Component label = Component.translatable("gui.mesplicedterminal.layout_center_on_return");
        int buttonWidth = measuredWidth(label) + TEXT_BUTTON_PAD;
        Rect undo = undoRect();
        return new Rect(undo.x - 6 - buttonWidth, 4, buttonWidth, TEXT_BUTTON_HEIGHT);
    }

    private Rect propertySelectedRect() {
        int px = width - propertyWidth();
        return new Rect(px + SIDEBAR_PAD, SIDEBAR_ROW_TOP, TOGGLE_SIZE, TOGGLE_SIZE);
    }

    private Rect sidebarCollapseRect() {
        if (sidebarCollapsed) {
            return collapsedRailRect(SIDEBAR_HEADER_TOP);
        }
        return new Rect(sidebarWidth() - SIDEBAR_PAD - TOGGLE_SIZE, SIDEBAR_HEADER_TOP, TOGGLE_SIZE, TOGGLE_SIZE);
    }

    private Rect sidebarRowRect(int index) {
        int y = SIDEBAR_ROW_TOP + index * SIDEBAR_ROW_HEIGHT;
        if (sidebarCollapsed) {
            return collapsedRailRect(y);
        }
        return new Rect(3, y, sidebarWidth() - 6, SIDEBAR_ROW_HEIGHT - 2);
    }

    private Rect collapsedRailRect(int y) {
        return new Rect(
                (sidebarWidth() - Icon.TOOLBAR_BUTTON_BACKGROUND.width) / 2,
                y,
                Icon.TOOLBAR_BUTTON_BACKGROUND.width,
                Icon.TOOLBAR_BUTTON_BACKGROUND.height);
    }

    private Rect propertyCollapseRect() {
        int px = width - propertyWidth();
        return new Rect(px + SIDEBAR_PAD, SIDEBAR_HEADER_TOP, TOGGLE_SIZE, TOGGLE_SIZE);
    }

    private Rect propertyToggleRect(int index) {
        int px = width - propertyWidth();
        int buttonWidth = propertiesCollapsed
                ? TOGGLE_SIZE
                : Math.max(TOGGLE_SIZE, propertyWidth() - SIDEBAR_PAD * 2);
        return new Rect(px + SIDEBAR_PAD, PROPERTY_ROW_TOP + index * PROPERTY_ROW_HEIGHT,
                buttonWidth, TEXT_BUTTON_HEIGHT);
    }

    private Rect propertyHelpRect() {
        int px = width - propertyWidth();
        int y = propertiesCollapsed
                ? PROPERTY_ROW_TOP + PROPERTY_COUNT * PROPERTY_ROW_HEIGHT
                : PROPERTY_ROW_TOP + PROPERTY_COUNT * PROPERTY_ROW_HEIGHT + 4;
        return new Rect(px + SIDEBAR_PAD, y, TOGGLE_SIZE, TOGGLE_SIZE);
    }

    // --- Lifecycle ---------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        dock.updateViewport(width, height);
        syncEditorCanvas();
        buildToolbarWidgets();
        if (selected == null || !panels.contains(selected)) {
            if (!panels.isEmpty()) {
                selected = panels.getFirst();
            }
        }
    }

    private void syncEditorCanvas() {
        dock.setEditorCanvasInsets(canvasLeft(), canvasTop(), width - canvasRight(), 0);
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
        toolbarButtons.getLast().active = dock.canUndoLayout();
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
        CursorHelper.resetCursor();
        dock.commitLayoutEditing();
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancel() {
        CursorHelper.resetCursor();
        dock.cancelLayoutEditing();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        syncResizeCursor(mouseX, mouseY);
    }

    private void syncResizeCursor(double mouseX, double mouseY) {
        CursorHelper.apply(dock.pointerCursor(mouseX, mouseY));
    }

    private void select(ModulePanel panel) {
        selected = panel;
    }

    private void toggleProperty(int index) {
        if (selected == null) {
            return;
        }
        ModuleLayoutPolicy policy = dock.policyFor(selected);
        boolean enabled = switch (index) {
            case 0 -> !policy.visible();
            case 1 -> !policy.movable();
            case 2 -> !policy.resizable();
            case 3 -> !policy.floating();
            default -> !policy.showTerminalButton();
        };
        applyProperty(index, enabled);
    }

    private void applyProperty(int index, boolean enabled) {
        if (selected == null) {
            return;
        }
        ModuleLayoutPolicy policy = dock.policyFor(selected);
        ModuleLayoutPolicy next = switch (index) {
            case 0 -> policy.withVisible(enabled);
            case 1 -> new ModuleLayoutPolicy(
                    policy.visible(), enabled, policy.resizable(),
                    policy.floating(), policy.pinned(), policy.showTerminalButton());
            case 2 -> new ModuleLayoutPolicy(
                    policy.visible(), policy.movable(), enabled,
                    policy.floating(), policy.pinned(), policy.showTerminalButton());
            case 3 -> policy.withFloating(enabled);
            default -> policy.withShowTerminalButton(enabled);
        };
        dock.setModulePolicy(selected, next);
    }

    private static Component propertyLabel(int index) {
        return Component.translatable(switch (index) {
            case 0 -> "gui.mesplicedterminal.layout_state_visible";
            case 1 -> "gui.mesplicedterminal.layout_state_movable";
            case 2 -> "gui.mesplicedterminal.layout_state_resizable";
            case 3 -> "gui.mesplicedterminal.layout_state_floating";
            default -> "gui.mesplicedterminal.layout_state_terminal_button";
        });
    }

    private Component propertyCaption(int index, boolean enabled) {
        Component state = Component.translatable(enabled
                ? "gui.mesplicedterminal.layout_yes"
                : "gui.mesplicedterminal.layout_no");
        return propertyLabel(index).copy().append(": ").append(state);
    }

    private List<Component> hintTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.mesplicedterminal.layout_help"));
        for (String key : HINT_KEYS) {
            lines.add(Component.translatable(key));
        }
        return lines;
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
                if (undoRect().contains(mouseX, mouseY) && dock.canUndoLayout()) {
                    dock.undoLayout();
                    return true;
                }
                if (centerOnReturnRect().contains(mouseX, mouseY)) {
                    dock.setCenterOnReturn(!dock.centerOnReturn());
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && sidebarCollapseRect().contains(mouseX, mouseY)) {
            sidebarCollapsed = !sidebarCollapsed;
            syncEditorCanvas();
            return true;
        }
        if (button == 0 && propertyCollapseRect().contains(mouseX, mouseY)) {
            propertiesCollapsed = !propertiesCollapsed;
            syncEditorCanvas();
            return true;
        }
        if (button == 0 && mouseX < sidebarWidth()) {
            for (int index = 0; index < panels.size(); index++) {
                if (sidebarRowRect(index).contains(mouseX, mouseY)) {
                    ModulePanel panel = panels.get(index);
                    select(panel);
                    pendingPalette = panel;
                    pendingPaletteX = mouseX;
                    pendingPaletteY = mouseY;
                    paletteDragStarted = false;
                    return true;
                }
            }
            return true;
        }
        if (mouseX >= width - propertyWidth()) {
            if (button == 0 && selected != null) {
                if (!propertiesCollapsed && propertyChecks[0] != null) {
                    for (AECheckbox checkbox : propertyChecks) {
                        if (checkbox != null && checkbox.visible
                                && checkbox.mouseClicked(mouseX, mouseY, button)) {
                            return true;
                        }
                    }
                } else {
                    for (int index = 0; index < PROPERTY_COUNT; index++) {
                        if (propertyToggleRect(index).contains(mouseX, mouseY)) {
                            toggleProperty(index);
                            return true;
                        }
                    }
                }
            }
            return true;
        }

        ModulePanel under = dock.topLeafAt(mouseX, mouseY);
        if (under != null) {
            select(under);
        }
        if (dock.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pendingPalette != null && !paletteDragStarted && button == 0) {
            double dx = mouseX - pendingPaletteX;
            double dy = mouseY - pendingPaletteY;
            if (dx * dx + dy * dy >= 16.0) {
                paletteDragStarted = dock.startExternalDrag(pendingPalette, pendingPaletteX, pendingPaletteY);
            }
        }
        if (dock.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pendingPalette != null && button == 0) {
            ModulePanel panel = pendingPalette;
            boolean dragged = paletteDragStarted;
            pendingPalette = null;
            paletteDragStarted = false;
            if (!dragged) {
                dock.toggleVisible(panel);
                return true;
            }
        }
        boolean handled = dock.mouseReleased(mouseX, mouseY, button)
                || super.mouseReleased(mouseX, mouseY, button);
        syncResizeCursor(mouseX, mouseY);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
        syncResizeCursor(mouseX, mouseY);
        renderBackground(graphics, mouseX, mouseY, partialTick);
        syncEditorCanvas();
        renderCanvas(graphics);
        dock.renderBackground(graphics, font, mouseX, mouseY, partialTick, (g, slot) -> { });
        dock.renderForeground(graphics, font, mouseX, mouseY, partialTick);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, CHROME_Z);
        try {
            renderSidebar(graphics, mouseX, mouseY);
            renderProperties(graphics, mouseX, mouseY);
            renderToolbar(graphics, mouseX, mouseY);
            for (IconButton button : toolbarButtons) {
                button.render(graphics, mouseX, mouseY, partialTick);
            }
            renderWidgetTooltips(graphics, mouseX, mouseY);
            renderChromeTooltips(graphics, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

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

    private void renderChromeTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (centerOnReturnRect().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, List.of(
                    Component.translatable(dock.centerOnReturn()
                            ? "gui.mesplicedterminal.layout_center_on_return_on"
                            : "gui.mesplicedterminal.layout_center_on_return"),
                    Component.translatable("gui.mesplicedterminal.layout_center_on_return_hint")),
                    mouseX, mouseY);
            return;
        }
        if (sidebarCollapseRect().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable(sidebarCollapsed
                    ? "gui.mesplicedterminal.layout_expand_modules"
                    : "gui.mesplicedterminal.layout_collapse_modules")), mouseX, mouseY);
            return;
        }
        if (propertyCollapseRect().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable(propertiesCollapsed
                    ? "gui.mesplicedterminal.layout_expand_properties"
                    : "gui.mesplicedterminal.layout_collapse_properties")), mouseX, mouseY);
            return;
        }
        if (propertiesCollapsed && propertySelectedRect().contains(mouseX, mouseY) && selected != null) {
            graphics.renderComponentTooltip(font, List.of(selected.title()), mouseX, mouseY);
            return;
        }
        if (propertyHelpRect().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, hintTooltip(), mouseX, mouseY);
            return;
        }
        for (int index = 0; index < panels.size(); index++) {
            Rect row = sidebarRowRect(index);
            if (!row.contains(mouseX, mouseY)) {
                continue;
            }
            ModulePanel panel = panels.get(index);
            ModuleLayoutPolicy policy = dock.policyFor(panel);
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(panel.title());
            if (!policy.visible()) {
                tooltip.add(Component.translatable("gui.mesplicedterminal.layout_palette_hidden"));
            }
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
            return;
        }
        if (selected != null) {
            ModuleLayoutPolicy policy = dock.policyFor(selected);
            for (int index = 0; index < PROPERTY_COUNT; index++) {
                if (propertyToggleRect(index).contains(mouseX, mouseY)) {
                    boolean enabled = propertyEnabled(policy, index);
                    List<Component> lines = new ArrayList<>();
                    lines.add(propertyCaption(index, enabled));
                    if (index == 3) {
                        lines.add(Component.translatable("gui.mesplicedterminal.layout_state_floating_hint"));
                    }
                    if (index == 4) {
                        lines.add(Component.translatable("gui.mesplicedterminal.layout_state_terminal_button_hint"));
                    }
                    graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
                    return;
                }
            }
        }
    }

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

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = sidebarWidth();
        BackgroundGenerator.draw(width, height, graphics, 0, 0);
        graphics.fill(width - 1, 0, width, height, COLOR_SEPARATOR);
        drawCollapseButton(graphics, sidebarCollapseRect(), sidebarCollapsed,
                sidebarCollapseRect().contains(mouseX, mouseY));
        if (!sidebarCollapsed) {
            graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_modules"),
                    SIDEBAR_PAD, SIDEBAR_HEADER_TOP + 5, ModulePanel.COLOR_TITLE_TEXT, false);
        }

        for (int i = 0; i < panels.size(); i++) {
            ModulePanel panel = panels.get(i);
            Rect row = sidebarRowRect(i);
            boolean hovered = row.contains(mouseX, mouseY);
            ModuleLayoutPolicy policy = dock.policyFor(panel);
            if (sidebarCollapsed) {
                drawToolbarPanelButton(graphics, row, panel, hovered, panel == selected);
                continue;
            }
            if (panel == selected) {
                graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, COLOR_SELECTION);
            } else if (hovered) {
                graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, COLOR_HOVER);
            }
            MESTScreen.blitPanelIcon(graphics, panel, row.x + 2, row.y + 3);
            int textColor = policy.visible() ? ModulePanel.COLOR_TITLE_TEXT : ModulePanel.COLOR_MUTED;
            graphics.drawString(font, panel.title(), row.x + 22, row.y + 7, textColor, false);
        }
    }

    private void renderProperties(GuiGraphics graphics, int mouseX, int mouseY) {
        int panelWidth = propertyWidth();
        int px = width - panelWidth;
        BackgroundGenerator.draw(panelWidth, height, graphics, px, 0);
        graphics.fill(px - 1, 0, px, height, COLOR_SEPARATOR);
        drawCollapseButton(graphics, propertyCollapseRect(), !propertiesCollapsed,
                propertyCollapseRect().contains(mouseX, mouseY));
        if (!propertiesCollapsed) {
            graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_properties"),
                    px + SIDEBAR_PAD + TOGGLE_SIZE + 4, SIDEBAR_HEADER_TOP + 5,
                    ModulePanel.COLOR_TITLE_TEXT, false);
        }
        if (selected == null) {
            hidePropertyChecks();
            drawHelpButton(graphics, mouseX, mouseY);
            return;
        }

        if (propertiesCollapsed && selected != null) {
            drawToolbarPanelButton(graphics, propertySelectedRect(), selected,
                    propertySelectedRect().contains(mouseX, mouseY), true);
        } else if (!propertiesCollapsed) {
            graphics.drawString(font, selected.title(), px + SIDEBAR_PAD, SIDEBAR_ROW_TOP + 4,
                    ModulePanel.COLOR_TITLE_TEXT, false);
        }

        ModuleLayoutPolicy policy = dock.policyFor(selected);
        for (int index = 0; index < PROPERTY_COUNT; index++) {
            drawPropertyControl(graphics, mouseX, mouseY, index, propertyEnabled(policy, index));
        }
        drawHelpButton(graphics, mouseX, mouseY);
    }

    private void hidePropertyChecks() {
        for (AECheckbox checkbox : propertyChecks) {
            if (checkbox != null) {
                checkbox.visible = false;
            }
        }
    }

    private static boolean propertyEnabled(ModuleLayoutPolicy policy, int index) {
        return switch (index) {
            case 0 -> policy.visible();
            case 1 -> policy.movable();
            case 2 -> policy.resizable();
            case 3 -> policy.floating();
            default -> policy.showTerminalButton();
        };
    }

    private void drawPropertyControl(GuiGraphics graphics, int mouseX, int mouseY, int index, boolean enabled) {
        Rect rect = propertyToggleRect(index);
        boolean hovered = rect.contains(mouseX, mouseY);
        AECheckbox checkbox = propertyChecks[index];
        if (checkbox != null) {
            checkbox.visible = !propertiesCollapsed && selected != null;
            if (checkbox.visible) {
                checkbox.setX(rect.x);
                checkbox.setY(rect.y);
                checkbox.setWidth(rect.w);
                checkbox.setSelected(enabled);
                checkbox.render(graphics, mouseX, mouseY, 0);
                return;
            }
        }
        if (propertiesCollapsed) {
            drawToolbarIconButton(graphics, rect, propertyIcon(index, enabled), hovered, false);
            return;
        }
        ModulePanel.drawButton(graphics, font, propertyCaption(index, enabled),
                rect.x, rect.y, rect.w, rect.h, hovered);
    }

    private void drawHelpButton(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect rect = propertyHelpRect();
        drawToolbarIconButton(graphics, rect, Icon.HELP, rect.contains(mouseX, mouseY), false);
    }

    private void drawCollapseButton(GuiGraphics graphics, Rect rect, boolean expandToRight, boolean hovered) {
        drawToolbarIconButton(graphics, rect,
                expandToRight ? Icon.ARROW_RIGHT : Icon.ARROW_LEFT, hovered, false);
    }

    private static Icon propertyIcon(int index, boolean enabled) {
        return switch (index) {
            case 0 -> enabled ? Icon.OVERLAY_ON : Icon.OVERLAY_OFF;
            case 1 -> enabled ? Icon.ACCESS_READ_WRITE : Icon.ACCESS_READ;
            case 2 -> enabled ? Icon.TERMINAL_STYLE_FULL : Icon.TERMINAL_STYLE_SMALL;
            case 3 -> enabled ? Icon.PATTERN_ACCESS_SHOW : Icon.PATTERN_ACCESS_HIDE;
            default -> enabled ? Icon.COG : Icon.BACKGROUND_PRIMARY_OUTPUT;
        };
    }

    private static void drawToolbarPanelButton(
            GuiGraphics graphics, Rect rect, ModulePanel panel, boolean hovered, boolean focused) {
        Icon background = focused
                ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                : hovered ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER : Icon.TOOLBAR_BUTTON_BACKGROUND;
        int bx = rect.x + (rect.w - background.width) / 2;
        int by = rect.y + (rect.h - background.height) / 2;
        background.getBlitter().dest(bx, by).blit(graphics);
        MESTScreen.blitPanelIcon(graphics, panel,
                bx + (background.width - ICON_SIZE) / 2,
                by + (background.height - ICON_SIZE) / 2);
    }

    private static void drawToolbarIconButton(
            GuiGraphics graphics, Rect rect, Icon icon, boolean hovered, boolean focused) {
        Icon background = focused
                ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                : hovered ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER : Icon.TOOLBAR_BUTTON_BACKGROUND;
        int bx = rect.x + (rect.w - background.width) / 2;
        int by = rect.y + (rect.h - background.height) / 2;
        background.getBlitter().dest(bx, by).blit(graphics);
        blitIcon(graphics, icon, bx + (background.width - ICON_SIZE) / 2, by + (background.height - ICON_SIZE) / 2);
    }

    private static void blitIcon(GuiGraphics graphics, Icon icon, int x, int y) {
        icon.getBlitter().dest(x + (ICON_SIZE - icon.width) / 2, y + (ICON_SIZE - icon.height) / 2).blit(graphics);
    }

    private void renderToolbar(GuiGraphics graphics, int mouseX, int mouseY) {
        BackgroundGenerator.draw(width, TOOLBAR_HEIGHT, graphics, 0, 0);
        graphics.fill(0, TOOLBAR_HEIGHT - 2, width, TOOLBAR_HEIGHT - 1, ModulePanel.COLOR_DARK);
        graphics.fill(0, TOOLBAR_HEIGHT - 1, width, TOOLBAR_HEIGHT, ModulePanel.COLOR_LIGHT);
        graphics.drawString(font, Component.translatable("gui.mesplicedterminal.layout_editor"),
                6 + 4 * 22 + 8, 9, ModulePanel.COLOR_TITLE_TEXT, false);

        boolean canUndo = dock.canUndoLayout();
        if (toolbarButtons.size() > 1) {
            toolbarButtons.get(1).active = canUndo;
        }
        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_center_on_return"),
                centerOnReturnRect().x, centerOnReturnRect().y, centerOnReturnRect().w, centerOnReturnRect().h,
                dock.centerOnReturn() || centerOnReturnRect().contains(mouseX, mouseY));
        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_undo"),
                undoRect().x, undoRect().y, undoRect().w, undoRect().h,
                canUndo && undoRect().contains(mouseX, mouseY));
        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_save"),
                saveRect().x, saveRect().y, saveRect().w, saveRect().h,
                saveRect().contains(mouseX, mouseY));
        ModulePanel.drawButton(graphics, font, Component.translatable("gui.mesplicedterminal.layout_cancel"),
                cancelRect().x, cancelRect().y, cancelRect().w, cancelRect().h,
                cancelRect().contains(mouseX, mouseY));
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