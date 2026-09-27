package com.lhy.mest.client.panel;

import appeng.client.gui.Icon;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.client.Point;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AECheckbox;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.Scrollbar;

import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.hotkey.HotkeyActions;
import com.lhy.mest.client.hotkey.PanelHotkey;
import com.lhy.mest.client.hotkey.PanelHotkeys;

/**
 * Hotkeys that open terminal panels or switch layout presets. Left-click a key button and press a combo to bind it, right-click
 * to clear it; the checkbox limits the binding to the open terminal.
 */
public class HotkeysPanel extends ModulePanel {
    public static final String ID = "hotkeys";

    private static final int ROW = 18;
    private static final int HEADER = 12;
    private static final int HINT = 12;
    private static final int KEY_W = 104;
    private static final int CHECK_W = 22;
    private static final int CHECK_H = 12;
    private static final int GAP = 4;
    private static final int MIN_W = 232;
    private static final int MIN_ROWS = 4;
    private static final int DEFAULT_ROWS = 8;
    /** Same 5px track as the toolkit and trash panels, parked in the right gutter. */
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_INNER = 3;
    private static final int TRACK_GUTTER = TRACK_WIDTH + 3;
    private static final int TRACK_BORDER = 0xFFF2F2F2;
    private static final int TRACK_FILL = 0xFF9A9FB4;

    private final ScreenStyle style;
    private final DockManager dock;
    private final List<Row> rows = new ArrayList<>();
    @Nullable
    private String capturing;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private boolean scrollbarDragging;

    /** A panel or a layout action; {@code label} is re-read each frame so preset renames show. */
    private record Row(String id, Supplier<Component> label, AECheckbox terminalOnly) {
    }

    public HotkeysPanel(ScreenStyle style, DockManager dock) {
        this.style = style;
        this.dock = dock;
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public ModuleDefaults defaults() {
        return new ModuleDefaults(false, true, false, true);
    }

    @Override
    public Icon icon() {
        return Icon.TYPE_FILTER_ALL;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.hotkeys");
    }

    @Override
    public int minWidth() {
        return MIN_W;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER + ROW * MIN_ROWS + HINT;
    }

    @Override
    public int defaultWidth() {
        return MIN_W;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER + ROW * DEFAULT_ROWS + HINT;
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    public boolean isCapturing() {
        return capturing != null;
    }

    public void cancelCapture() {
        capturing = null;
    }

    /** Takes the next key press while a key button waits for a combo. */
    public boolean captureKey(int keyCode, int modifiers) {
        if (capturing == null) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            capturing = null;
            return true;
        }
        if (PanelHotkey.isModifierKey(keyCode)) {
            return true;
        }
        PanelHotkey current = PanelHotkeys.get(capturing);
        PanelHotkeys.set(capturing, keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE
                ? current.cleared()
                : current.withCombo(keyCode, modifiers));
        capturing = null;
        return true;
    }

    private void ensureRows() {
        if (!rows.isEmpty()) {
            return;
        }
        for (ModulePanel panel : dock.panels()) {
            addRow(panel.id(), panel::title);
        }
        for (int group = 1; group <= 3; group++) {
            int index = group;
            addRow(HotkeyActions.groupId(index),
                    () -> Component.translatable("gui.mesplicedterminal.combined_module", index));
        }
        addRow(HotkeyActions.CYCLE_PRESET,
                () -> Component.translatable("gui.mesplicedterminal.hotkeys.cycle_preset"));
        for (int slot = 0; slot < dock.presetCount(); slot++) {
            int index = slot;
            addRow(HotkeyActions.presetId(index), () -> Component.translatable(
                    "gui.mesplicedterminal.hotkeys.select_preset", dock.presetName(index)));
        }
    }

    private void addRow(String id, Supplier<Component> label) {
        AECheckbox checkbox = new AECheckbox(0, 0, CHECK_W, CHECK_H, style, Component.empty());
        checkbox.setChangeListener(() -> PanelHotkeys.set(
                id, PanelHotkeys.get(id).withTerminalOnly(checkbox.isSelected())));
        rows.add(new Row(id, label, checkbox));
    }

    private int listTop() {
        return contentTop() + HEADER;
    }

    private int listHeight() {
        return Math.max(ROW, contentHeight() - HEADER - HINT);
    }

    private int visibleRows() {
        return Math.max(1, listHeight() / ROW);
    }

    /** Scrolls by whole rows so every shown row keeps its key button and checkbox. */
    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows());
    }

    private int columnsRight() {
        return contentLeft() + contentWidth() - TRACK_GUTTER;
    }

    private int keyX() {
        return columnsRight() - CHECK_W - GAP - KEY_W;
    }

    private int checkX() {
        return columnsRight() - CHECK_W;
    }

    private int trackLeft() {
        return contentLeft() + contentWidth() - TRACK_WIDTH;
    }

    private int trackHeight() {
        return visibleRows() * ROW;
    }

    private int rowY(int index) {
        return listTop() + (index - scrollbar.getCurrentScroll()) * ROW;
    }

    private boolean rowShown(int index) {
        int y = rowY(index);
        return y >= listTop() && y + ROW <= listTop() + listHeight();
    }

    @Override
    public void layoutSlots() {
        ensureRows();
        if (!visible) {
            capturing = null;
        }
        scrollbar.setRange(0, maxScroll(), 1);
        scrollbar.setHeight(Math.max(1, trackHeight() - 2));
        scrollbar.setPosition(new Point(trackLeft() - 1, listTop() + 1));
        for (int i = 0; i < rows.size(); i++) {
            AECheckbox checkbox = rows.get(i).terminalOnly();
            checkbox.visible = visible && rowShown(i);
            checkbox.setX(checkX());
            checkbox.setY(rowY(i) + (ROW - CHECK_H) / 2);
            checkbox.setSelected(PanelHotkeys.get(rows.get(i).id()).terminalOnly());
        }
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        layoutSlots();
        int color = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        int left = contentLeft();
        int top = contentTop();
        g.drawString(font, Component.translatable("gui.mesplicedterminal.hotkeys.module"), left, top + 1, color, false);
        g.drawString(font, Component.translatable("gui.mesplicedterminal.hotkeys.key"), keyX(), top + 1, color, false);
        Component onlyLabel = Component.translatable("gui.mesplicedterminal.hotkeys.terminal_only");
        g.drawString(font, onlyLabel, checkX() + CHECK_W - font.width(onlyLabel), top + 1, color, false);

        int nameWidth = keyX() - GAP - left;
        for (int i = 0; i < rows.size(); i++) {
            if (!rowShown(i)) {
                continue;
            }
            Row row = rows.get(i);
            String moduleId = row.id();
            int y = rowY(i);
            String name = font.plainSubstrByWidth(row.label().get().getString(), nameWidth);
            g.drawString(font, name, left, y + (ROW - 8) / 2, color, false);
            boolean waiting = moduleId.equals(capturing);
            Component label = waiting
                    ? Component.translatable("gui.mesplicedterminal.hotkeys.press")
                    : PanelHotkeys.describe(PanelHotkeys.get(moduleId));
            boolean hovered = mouseX >= keyX() && mouseX < keyX() + KEY_W && mouseY >= y + 1 && mouseY < y + ROW - 1;
            drawButton(g, font, label, keyX(), y + 1, KEY_W, ROW - 2, waiting || hovered);
            row.terminalOnly().render(g, mouseX, mouseY, partialTicks);
        }
        if (maxScroll() > 0) {
            drawTrack(g, trackLeft(), listTop(), trackHeight());
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
        }
        g.drawString(font, Component.translatable("gui.mesplicedterminal.hotkeys.hint"),
                left, listTop() + listHeight() + 2, COLOR_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!rowShown(i)) {
                continue;
            }
            int y = rowY(i);
            String moduleId = rows.get(i).id();
            if (mx >= keyX() && mx < keyX() + KEY_W && my >= y + 1 && my < y + ROW - 1) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    capturing = null;
                    PanelHotkeys.set(moduleId, PanelHotkeys.get(moduleId).cleared());
                } else {
                    capturing = moduleId.equals(capturing) ? null : moduleId;
                }
                return true;
            }
            AECheckbox checkbox = rows.get(i).terminalOnly();
            if (checkbox.visible && checkbox.mouseClicked(mx, my, button)) {
                capturing = null;
                return true;
            }
        }
        capturing = null;
        return false;
    }

    /** Drawn by the screen's tooltip overlay, so it sits above every window instead of under them. */
    @Nullable
    public ITooltip hoveredTooltip(int mouseX, int mouseY) {
        for (Row row : rows) {
            AECheckbox checkbox = row.terminalOnly();
            if (checkbox.visible && checkbox.isMouseOver(mouseX, mouseY)) {
                return TERMINAL_ONLY_TOOLTIP;
            }
        }
        return null;
    }

    private static final ITooltip TERMINAL_ONLY_TOOLTIP = new ITooltip() {
        @Override
        public List<Component> getTooltipMessage() {
            return List.of(
                    Component.translatable("gui.mesplicedterminal.hotkeys.terminal_only"),
                    Component.translatable("gui.mesplicedterminal.hotkeys.terminal_only.hint"));
        }

        @Override
        public Rect2i getTooltipArea() {
            return new Rect2i(0, 0, 0, 0);
        }

        @Override
        public boolean isTooltipAreaVisible() {
            return true;
        }
    };

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || !contains(mx, my) || maxScroll() == 0) {
            return false;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - (int) Math.signum(scrollY));
        layoutSlots();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        Rect2i bounds = scrollbar.getBounds();
        if (!visible || maxScroll() == 0 || mx < bounds.getX() || mx >= bounds.getX() + bounds.getWidth()
                || my < bounds.getY() || my >= bounds.getY() + bounds.getHeight()) {
            return false;
        }
        capturing = null;
        scrollbarDragging = scrollbar.onMouseDown(new Point((int) mx, (int) my), 0);
        layoutSlots();
        return scrollbarDragging;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbarDragging) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDrag(new Point((int) mx, (int) my), 0);
        layoutSlots();
        return consumed;
    }

    @Override
    public void scrollbarReleased() {
        if (scrollbarDragging) {
            scrollbar.onMouseUp(Point.ZERO, 0);
        }
        scrollbarDragging = false;
    }

    @Override
    public boolean scrollbarDragging() {
        return scrollbarDragging;
    }

    private static void drawTrack(GuiGraphics g, int x, int y, int height) {
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
}
