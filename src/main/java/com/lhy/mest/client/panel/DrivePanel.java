package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.api.storage.cells.CellState;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.network.DriveCellRequestPacket;
import com.lhy.mest.network.DrivePriorityPacket;
import com.lhy.mest.terminal.MESTMenu;

/** AE2-style physical cell wells grouped by drive with state LEDs; the server owns the inventories. */
public final class DrivePanel extends ModulePanel {
    private static final int SLOT = 18;
    // Cells flow in runs of five, echoing the five-cell rows of the physical drive face.
    private static final int SEGMENT = 5;
    private static final int SEGMENT_WIDTH = SEGMENT * SLOT;
    private static final int SEGMENT_GAP = 3;
    private static final int HEADER = 14;
    private static final int GROUP_GAP = 5;
    private static final int CONTROL_TOP = 1;
    private static final int BUTTON = 11;
    private static final int VALUE_WIDTH = 34;
    private static final int COLOR_VALUE = 0xFFF4F5F8;
    private static final int COLOR_VALUE_HOVER = 0xFF4F6FB0;
    private static final int COLOR_OFFLINE = 0xFFB33A3A;
    private static final int COLOR_LED_OFF = 0xFF45454D;
    private static final int SCROLL_STEP = 18;
    // The same narrow track and AE2 SMALL thumb used by ProviderSelectPanel.
    private static final int TRACK_WIDTH = 5;
    private static final int TRACK_GAP = 2;
    private static final int TRACK_SHIFT_X = 2;
    private static final int GUTTER = TRACK_WIDTH + TRACK_GAP + TRACK_SHIFT_X;
    private static final int TRACK_BORDER = 0xFFF2F2F2;
    private static final int TRACK_FILL = 0xFF9A9FB4;

    private final MESTMenu menu;
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.SMALL);
    private List<DriveCellListPacket.Group> groups = List.of();
    private List<DriveCellListPacket.Row> rows = List.of();
    private int receivedWindow = -1;
    private int requestedWindow;
    private int revision = -1;
    private int ticks;
    private int requestedAt;
    private boolean subscribed;
    private boolean pending;
    private boolean dragging;

    private record CellHit(int group, int index, DriveCellListPacket.Row row) {
    }

    private enum HeaderPart { NAME, MINUS, VALUE, PLUS }

    private record HeaderHit(int group, HeaderPart part) {
    }

    public DrivePanel(MESTMenu menu) {
        this.menu = menu;
        scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public String id() {
        return "drive";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.drive");
    }

    @Override
    public Icon icon() {
        return Icon.BACKGROUND_STORAGE_CELL;
    }

    @Override
    public ModuleDefaults defaults() {
        return new ModuleDefaults(false, false, false, false);
    }

    @Override
    public int defaultWidth() {
        return 218;
    }

    @Override
    public int defaultHeight() {
        return 198;
    }

    @Override
    public int minWidth() {
        return 137;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER + SLOT + GROUP_GAP;
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    @Override
    public void layoutSlots() {
        // The grid is virtual: dynamically changing remote drives cannot alter menu slot indices.
        layoutScrollbar();
        if (subscribed) {
            syncWindow();
        }
    }

    public void update(DriveCellListPacket packet) {
        if (!subscribed || packet.containerId() != menu.containerId) {
            return;
        }
        if (packet.windowStart() != requestedWindow
                && !(requestedWindow >= packet.totalSlots() && packet.windowStart() <= requestedWindow)) {
            return;
        }
        groups = List.copyOf(packet.groups());
        rows = List.copyOf(packet.rows());
        receivedWindow = packet.windowStart();
        revision = packet.revision();
        requestedWindow = receivedWindow;
        pending = false;
        layoutScrollbar();
        syncWindow();
    }

    public void tick(boolean visible) {
        ticks++;
        if (!visible) {
            closeSubscription();
            return;
        }
        if (!subscribed) {
            subscribed = true;
            requestWindow(0);
        } else if (pending && ticks - requestedAt >= 10) {
            requestWindow(requestedWindow);
        } else if (!pending && ticks - requestedAt >= 40) {
            requestWindow(requestedWindow);
        }
    }

    public void closeSubscription() {
        if (subscribed) {
            PacketDistributor.sendToServer(new DriveCellRequestPacket(menu.containerId, -1));
        }
        subscribed = false;
        pending = false;
        dragging = false;
        revision = -1;
        receivedWindow = -1;
        requestedWindow = 0;
        groups = List.of();
        rows = List.of();
        scrollbar.setCurrentScroll(0);
    }

    private void requestWindow(int start) {
        requestedWindow = start;
        requestedAt = ticks;
        pending = receivedWindow != requestedWindow;
        PacketDistributor.sendToServer(new DriveCellRequestPacket(menu.containerId, requestedWindow));
    }

    private void syncWindow() {
        if (!subscribed || groups.isEmpty()) {
            return;
        }
        int position = scrollPixels();
        int preceding = 0;
        int firstSlot = 0;
        for (var group : groups) {
            if (preceding + groupHeight(group) > position) {
                break;
            }
            preceding += groupHeight(group);
            firstSlot += group.slots();
        }
        if (firstSlot != requestedWindow) {
            requestWindow(firstSlot);
        }
    }

    private int segmentsPerRow() {
        return Math.max(1, (listWidth() + SEGMENT_GAP) / (SEGMENT_WIDTH + SEGMENT_GAP));
    }

    private int cellsPerRow() {
        return segmentsPerRow() * SEGMENT;
    }

    private int cellRows(DriveCellListPacket.Group group) {
        return (group.slots() + cellsPerRow() - 1) / cellsPerRow();
    }

    private int groupHeight(DriveCellListPacket.Group group) {
        return HEADER + cellRows(group) * SLOT + GROUP_GAP;
    }

    private int cellX(int slot) {
        int column = slot % cellsPerRow();
        return contentLeft() + column / SEGMENT * (SEGMENT_WIDTH + SEGMENT_GAP) + column % SEGMENT * SLOT;
    }

    private int cellY(int groupTop, int slot) {
        return groupTop + HEADER + slot / cellsPerRow() * SLOT;
    }

    private int totalHeight() {
        int total = 0;
        for (var group : groups) {
            total += groupHeight(group);
        }
        return total;
    }

    private int scrollPixels() {
        return Math.min(scrollbar.getCurrentScroll() * SCROLL_STEP,
                Math.max(0, totalHeight() - contentHeight()));
    }

    private int listWidth() {
        return Math.max(1, contentWidth() - GUTTER);
    }

    private int trackX() {
        return contentLeft() + listWidth() + TRACK_GAP + TRACK_SHIFT_X;
    }

    private void layoutScrollbar() {
        int max = Math.max(0, totalHeight() - contentHeight());
        scrollbar.setRange(0, (max + SCROLL_STEP - 1) / SCROLL_STEP, 1);
        scrollbar.setHeight(Math.max(1, contentHeight() - 2));
        scrollbar.setPosition(new Point(trackX() - 1, contentTop()));
    }

    private int slotIndex(int drive, int slot) {
        int absolute = slot;
        for (int i = 0; i < drive; i++) {
            absolute += groups.get(i).slots();
        }
        int index = absolute - receivedWindow;
        if (index < 0 || index >= rows.size()) {
            return -1;
        }
        var row = rows.get(index);
        return row.drive() == drive && row.slot() == slot ? index : -1;
    }

    private int listRight() {
        return contentLeft() + listWidth();
    }

    private int plusX() {
        return listRight() - BUTTON;
    }

    private int valueX() {
        return plusX() - 1 - VALUE_WIDTH;
    }

    private int minusX() {
        return valueX() - 1 - BUTTON;
    }

    private HeaderHit headerAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            if (my >= y && my < y + HEADER) {
                var group = groups.get(i);
                if (group.editable() && my >= y + CONTROL_TOP && my < y + CONTROL_TOP + BUTTON) {
                    if (mx >= plusX()) {
                        return new HeaderHit(i, HeaderPart.PLUS);
                    }
                    if (mx >= valueX() && mx < valueX() + VALUE_WIDTH) {
                        return new HeaderHit(i, HeaderPart.VALUE);
                    }
                    if (mx >= minusX() && mx < minusX() + BUTTON) {
                        return new HeaderHit(i, HeaderPart.MINUS);
                    }
                }
                return mx < (group.editable() ? minusX() - 2 : listRight()) ? new HeaderHit(i, HeaderPart.NAME) : null;
            }
            y += groupHeight(groups.get(i));
        }
        return null;
    }

    private CellHit cellAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int relX = (int) mx - contentLeft();
        int segment = relX / (SEGMENT_WIDTH + SEGMENT_GAP);
        int within = relX - segment * (SEGMENT_WIDTH + SEGMENT_GAP);
        if (segment >= segmentsPerRow() || within >= SEGMENT_WIDTH) {
            return null;
        }
        int column = segment * SEGMENT + within / SLOT;
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            int cellsTop = y + HEADER;
            if (my >= cellsTop && my < cellsTop + cellRows(group) * SLOT) {
                int slot = (int) (my - cellsTop) / SLOT * cellsPerRow() + column;
                if (slot >= group.slots()) {
                    return null;
                }
                int index = slotIndex(i, slot);
                return index < 0 ? null : new CellHit(i, index, rows.get(index));
            }
            y += groupHeight(group);
        }
        return null;
    }

    private boolean inList(double mx, double my) {
        return visible && mx >= contentLeft() && mx < listRight()
                && my >= contentTop() && my < contentTop() + contentHeight();
    }

    public ItemStack hoveredItem(int x, int y) {
        CellHit cell = cellAt(x, y);
        return cell == null ? ItemStack.EMPTY : cell.row().stack();
    }

    /** Capacity lines appended below the cell item's own tooltip. */
    public List<Component> hoveredCellDetails(int x, int y) {
        CellHit cell = cellAt(x, y);
        if (cell == null || cell.row().stack().isEmpty()) {
            return List.of();
        }
        var row = cell.row();
        var lines = new ArrayList<Component>();
        CellState state = CellState.values()[row.status()];
        lines.add(Component.translatable("gui.mesplicedterminal.drive.status",
                Component.translatable("gui.mesplicedterminal.drive.status." + state.name().toLowerCase(Locale.ROOT))
                        .withColor(state.getStateColor())).withStyle(ChatFormatting.GRAY));
        if (row.hasCapacity()) {
            lines.add(Component.translatable("gui.mesplicedterminal.drive.bytes",
                    number(row.usedBytes()), number(row.totalBytes())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("gui.mesplicedterminal.drive.types",
                    number(row.usedTypes()), number(row.totalTypes())).withStyle(ChatFormatting.GRAY));
        }
        if (!groups.get(cell.group()).powered()) {
            lines.add(Component.translatable("gui.mesplicedterminal.drive.offline").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    public List<Component> hoveredText(int x, int y) {
        HeaderHit hit = headerAt(x, y);
        if (hit == null) {
            return List.of();
        }
        var group = groups.get(hit.group());
        return switch (hit.part()) {
            case MINUS -> List.of(Component.translatable("gui.mesplicedterminal.drive.priority.down"), stepHint());
            case PLUS -> List.of(Component.translatable("gui.mesplicedterminal.drive.priority.up"), stepHint());
            case VALUE -> List.of(
                    Component.translatable("gui.mesplicedterminal.drive.priority.value", number(group.priority())),
                    Component.translatable("gui.mesplicedterminal.drive.priority.wheel")
                            .withStyle(ChatFormatting.GRAY),
                    stepHint(),
                    GuiText.PriorityInsertionHint.text().copy().withStyle(ChatFormatting.DARK_GRAY),
                    GuiText.PriorityExtractionHint.text().copy().withStyle(ChatFormatting.DARK_GRAY));
            case NAME -> groupSummary(hit.group(), group);
        };
    }

    private List<Component> groupSummary(int index, DriveCellListPacket.Group group) {
        int cells = 0;
        long used = 0;
        long total = 0;
        for (var row : rows) {
            if (row.drive() == index && !row.stack().isEmpty()) {
                cells++;
                used += row.usedBytes();
                total += row.totalBytes();
            }
        }
        var lines = new ArrayList<Component>();
        lines.add(group.name());
        lines.add(Component.translatable(group.powered()
                ? "gui.mesplicedterminal.drive.online" : "gui.mesplicedterminal.drive.offline")
                .withStyle(group.powered() ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Component.translatable("gui.mesplicedterminal.drive.cells", cells, group.slots())
                .withStyle(ChatFormatting.GRAY));
        if (total > 0) {
            lines.add(Component.translatable("gui.mesplicedterminal.drive.bytes", number(used), number(total))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (group.editable()) {
            lines.add(Component.translatable("gui.mesplicedterminal.drive.priority.value", number(group.priority()))
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static Component stepHint() {
        return Component.translatable("gui.mesplicedterminal.drive.priority.step").withStyle(ChatFormatting.DARK_GRAY);
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static int priorityStep() {
        boolean shift = Screen.hasShiftDown();
        boolean control = Screen.hasControlDown();
        return shift && control ? 1000 : control ? 100 : shift ? 10 : 1;
    }

    private void sendPriority(int group, int direction) {
        if (revision < 0 || direction == 0 || group < 0 || group >= groups.size() || !groups.get(group).editable()) {
            return;
        }
        PacketDistributor.sendToServer(new DrivePriorityPacket(menu.containerId, revision, group,
                direction * priorityStep()));
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (contentWidth() <= 0 || contentHeight() <= 0) {
            return;
        }
        int left = contentLeft();
        int top = contentTop();
        int bottom = top + contentHeight();
        CellHit hovered = cellAt(mouseX, mouseY);
        HeaderHit header = headerAt(mouseX, mouseY);
        g.enableScissor(left, top, left + contentWidth(), bottom);
        try {
            int y = top - scrollPixels();
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                int height = groupHeight(group);
                if (y + height > top && y < bottom) {
                    drawGroup(g, font, i, group, y, hovered == null ? -1 : hovered.index(),
                            header != null && header.group() == i ? header.part() : null);
                }
                y += height;
            }
            if (groups.isEmpty() && receivedWindow >= 0) {
                g.drawString(font, Component.translatable("gui.mesplicedterminal.drive.empty"),
                        left + 2, top + 3, COLOR_MUTED, false);
            }
            drawTrack(g, trackX(), top, contentHeight());
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
        } finally {
            g.disableScissor();
        }
    }

    private void drawGroup(GuiGraphics g, Font font, int index, DriveCellListPacket.Group group,
            int y, int hovered, HeaderPart headerPart) {
        int left = contentLeft();
        int nameRight = group.editable() ? minusX() - 3 : listRight();
        if (!group.powered()) {
            Component offline = Component.translatable("gui.mesplicedterminal.drive.offline");
            nameRight -= font.width(offline);
            g.drawString(font, offline, nameRight, y + 3, COLOR_OFFLINE, false);
            nameRight -= 4;
        }
        String name = group.name().getString();
        int nameWidth = Math.max(0, nameRight - left - 1);
        String shown = name;
        if (font.width(name) > nameWidth) {
            shown = font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("..."))) + "...";
        }
        g.drawString(font, shown, left + 1, y + 3, group.powered() ? COLOR_TITLE_TEXT : COLOR_MUTED, false);
        if (group.editable()) {
            int controlY = y + CONTROL_TOP;
            drawButton(g, font, Component.literal("-"), minusX(), controlY, BUTTON, BUTTON,
                    headerPart == HeaderPart.MINUS);
            drawButton(g, font, Component.literal("+"), plusX(), controlY, BUTTON, BUTTON,
                    headerPart == HeaderPart.PLUS);
            int valueX = valueX();
            g.fill(valueX, controlY, valueX + VALUE_WIDTH, controlY + BUTTON,
                    headerPart == HeaderPart.VALUE ? COLOR_VALUE_HOVER : COLOR_DARK);
            g.fill(valueX + 1, controlY + 1, valueX + VALUE_WIDTH - 1, controlY + BUTTON - 1, COLOR_VALUE);
            String priority = font.plainSubstrByWidth(Integer.toString(group.priority()), VALUE_WIDTH - 4);
            g.drawString(font, priority, valueX + (VALUE_WIDTH - font.width(priority) + 1) / 2, controlY + 2,
                    COLOR_TITLE_TEXT, false);
        }
        for (int slot = 0; slot < group.slots(); slot++) {
            int px = cellX(slot);
            int py = cellY(y, slot);
            drawSlot(g, px, py);
            int rowIndex = slotIndex(index, slot);
            var row = rowIndex >= 0 ? rows.get(rowIndex) : null;
            if (row == null || row.stack().isEmpty()) {
                Icon.BACKGROUND_STORAGE_CELL.getBlitter().dest(px + 1, py + 1).blit(g);
            } else {
                g.renderItem(row.stack(), px + 1, py + 1);
                drawCellState(g, row, px, py, group.powered());
            }
            if (rowIndex >= 0 && rowIndex == hovered) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                g.fill(px + 1, py + 1, px + SLOT - 1, py + SLOT - 1, 0x669CD3FF);
                g.pose().popPose();
            }
        }
        int rule = y + groupHeight(group) - 3;
        g.fill(left, rule, listRight(), rule + 1, SECTION_RULE_COLOR);
    }

    private static void drawCellState(GuiGraphics g, DriveCellListPacket.Row row, int px, int py, boolean powered) {
        CellState state = CellState.values()[row.status()];
        int color = powered ? 0xFF000000 | state.getStateColor() : COLOR_LED_OFF;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        // Status LED in the top-right corner, like the light on the physical drive face.
        g.fill(px + 13, py + 1, px + 17, py + 5, 0xFF1A1A1A);
        g.fill(px + 14, py + 2, px + 16, py + 4, color);
        if (row.hasCapacity()) {
            // Same footprint as the vanilla durability bar.
            double fraction = Math.min(1.0, row.usedBytes() / (double) row.totalBytes());
            int width = row.usedBytes() > 0 ? Math.max(1, (int) Math.round(13 * fraction)) : 0;
            g.fill(px + 3, py + 14, px + 16, py + 16, 0xFF000000);
            if (width > 0) {
                g.fill(px + 3, py + 14, px + 3 + width, py + 15, color);
            }
        }
        if (!powered) {
            g.fill(px + 1, py + 1, px + SLOT - 1, py + SLOT - 1, 0x55303038);
        }
        g.pose().popPose();
    }

    private static void drawTrack(GuiGraphics g, int x, int y, int height) {
        int x1 = x + TRACK_WIDTH - 1;
        int y1 = y + height - 1;
        g.hLine(x, x1, y, TRACK_BORDER);
        g.hLine(x, x1, y1, TRACK_BORDER);
        g.vLine(x, y, y1, TRACK_BORDER);
        g.vLine(x1, y, y1, TRACK_BORDER);
        g.fill(x + 1, y + 1, x + 4, y1, TRACK_FILL);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!inList(mx, my)) {
            return false;
        }
        HeaderHit header = headerAt(mx, my);
        if (header != null) {
            if (button == 0 && header.part() == HeaderPart.PLUS) {
                sendPriority(header.group(), 1);
            } else if (button == 0 && header.part() == HeaderPart.MINUS) {
                sendPriority(header.group(), -1);
            } else if (header.part() == HeaderPart.VALUE && (button == 0 || button == 1)) {
                sendPriority(header.group(), button == 0 ? 1 : -1);
            }
            return true;
        }
        CellHit cell = cellAt(mx, my);
        if (cell != null && (button == 0 || button == 1) && revision >= 0) {
            PacketDistributor.sendToServer(new DriveCellActionPacket(menu.containerId, revision, cell.index(),
                    button == 1 || Screen.hasShiftDown()
                            ? DriveCellActionPacket.Action.QUICK_MOVE : DriveCellActionPacket.Action.CURSOR));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || !contains(mx, my)) {
            return false;
        }
        HeaderHit header = headerAt(mx, my);
        if (header != null && header.part() == HeaderPart.VALUE) {
            sendPriority(header.group(), (int) Math.signum(scrollY));
            return true;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - 3 * (int) Math.signum(scrollY));
        syncWindow();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || mx < trackX() || mx >= trackX() + TRACK_WIDTH
                || my < contentTop() || my >= contentTop() + contentHeight()) {
            return false;
        }
        dragging = scrollbar.onMouseDown(new Point((int) mx, (int) my), 0);
        syncWindow();
        return dragging;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!dragging) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDrag(new Point((int) mx, (int) my), 0);
        syncWindow();
        return consumed;
    }

    @Override
    public void scrollbarReleased() {
        if (dragging) {
            scrollbar.onMouseUp(new Point(0, 0), 0);
        }
        dragging = false;
    }

    @Override
    public boolean scrollbarDragging() {
        return dragging;
    }
}
