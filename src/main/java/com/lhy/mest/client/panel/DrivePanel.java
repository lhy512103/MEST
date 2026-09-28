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
    // A 1px frame around each run of cells, like the outlined slot block on AE2's drive screen.
    private static final int FRAME = 1;
    private static final int SEGMENT_STRIDE = SEGMENT_WIDTH + 2 * FRAME + SEGMENT_GAP;
    private static final int COLOR_FRAME = 0xFFFFFFFF;
    // AE2's 20px tab button, scaled to sit inside the header line.
    private static final int PRIORITY_BUTTON = 13;
    private static final float PRIORITY_SCALE = PRIORITY_BUTTON / 20f;
    private static final int COLOR_TITLE_WARN = 0xFFC07A12;
    private static final int COLOR_TITLE_FULL = 0xFFB33A3A;
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

    private enum HeaderPart { NAME, PRIORITY }

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
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER + SLOT + 2 * FRAME + GROUP_GAP;
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
        return Math.max(1, (listWidth() + SEGMENT_GAP) / SEGMENT_STRIDE);
    }

    private int cellsPerRow() {
        return segmentsPerRow() * SEGMENT;
    }

    private int cellRows(DriveCellListPacket.Group group) {
        return (group.slots() + cellsPerRow() - 1) / cellsPerRow();
    }

    private int groupHeight(DriveCellListPacket.Group group) {
        return HEADER + cellRows(group) * SLOT + 2 * FRAME + GROUP_GAP;
    }

    private int cellX(int slot) {
        int column = slot % cellsPerRow();
        return contentLeft() + column / SEGMENT * SEGMENT_STRIDE + FRAME + column % SEGMENT * SLOT;
    }

    private int cellY(int groupTop, int slot) {
        return groupTop + HEADER + FRAME + slot / cellsPerRow() * SLOT;
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

    private int priorityX() {
        return listRight() - PRIORITY_BUTTON;
    }

    private HeaderHit headerAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            if (my >= y && my < y + HEADER) {
                var group = groups.get(i);
                if (group.editable() && mx >= priorityX() && my < y + PRIORITY_BUTTON) {
                    return new HeaderHit(i, HeaderPart.PRIORITY);
                }
                return mx < (group.editable() ? priorityX() - 2 : listRight()) ? new HeaderHit(i, HeaderPart.NAME) : null;
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
        int segment = relX / SEGMENT_STRIDE;
        int within = relX - segment * SEGMENT_STRIDE - FRAME;
        if (segment >= segmentsPerRow() || within < 0 || within >= SEGMENT_WIDTH) {
            return null;
        }
        int column = segment * SEGMENT + within / SLOT;
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            int cellsTop = y + HEADER + FRAME;
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
            case PRIORITY -> List.of(
                    GuiText.Priority.text(),
                    Component.translatable("gui.mesplicedterminal.drive.priority.value", number(group.priority()))
                            .withStyle(ChatFormatting.GRAY),
                    Component.translatable("gui.mesplicedterminal.drive.priority.open")
                            .withStyle(ChatFormatting.GRAY),
                    GuiText.PriorityInsertionHint.text().copy().withStyle(ChatFormatting.DARK_GRAY),
                    GuiText.PriorityExtractionHint.text().copy().withStyle(ChatFormatting.DARK_GRAY));
            case NAME -> groupSummary(hit.group(), group);
        };
    }

    private List<Component> groupSummary(int index, DriveCellListPacket.Group group) {
        int cells = 0;
        for (var row : rows) {
            if (row.drive() == index && !row.stack().isEmpty()) {
                cells++;
            }
        }
        long used = group.usedBytes();
        long total = group.totalBytes();
        var lines = new ArrayList<Component>();
        lines.add(group.name());
        lines.add(Component.translatable("gui.mesplicedterminal.drive.location", group.location())
                .withStyle(ChatFormatting.GRAY));
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

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private void openPriority(int group) {
        if (revision < 0 || group < 0 || group >= groups.size() || !groups.get(group).editable()) {
            return;
        }
        PacketDistributor.sendToServer(new DrivePriorityPacket(menu.containerId, revision, group));
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
        } finally {
            g.disableScissor();
        }
        // Outside the scissor: the SMALL thumb is wider than the track and would be clipped on the right.
        drawTrack(g, trackX(), top, contentHeight());
        scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
    }

    private void drawGroup(GuiGraphics g, Font font, int index, DriveCellListPacket.Group group,
            int y, int hovered, HeaderPart headerPart) {
        int left = contentLeft();
        int nameRight = group.editable() ? priorityX() - 3 : listRight();
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
        g.drawString(font, shown, left + 1, y + 3, group.powered() ? titleColor(group) : COLOR_MUTED, false);
        if (group.editable()) {
            drawPriorityButton(g, priorityX(), y, headerPart == HeaderPart.PRIORITY);
        }
        drawFrames(g, group, y);
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

    private static int titleColor(DriveCellListPacket.Group group) {
        if (group.totalBytes() <= 0) {
            return COLOR_TITLE_TEXT;
        }
        double fill = Math.min(1.0, group.usedBytes() / (double) group.totalBytes());
        if (fill < 0.75) {
            return mix(COLOR_TITLE_TEXT, COLOR_TITLE_WARN, fill / 0.75 * 0.35);
        }
        if (fill < 0.95) {
            return mix(COLOR_TITLE_WARN, COLOR_TITLE_FULL, (fill - 0.75) / 0.2 * 0.5);
        }
        return COLOR_TITLE_FULL;
    }

    private static int mix(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int gr = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private static void drawPriorityButton(GuiGraphics g, int x, int y, boolean hovered) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(PRIORITY_SCALE, PRIORITY_SCALE, 1);
        Icon.TAB_BUTTON_BACKGROUND.getBlitter().dest(0, 0).blit(g);
        Icon.PRIORITY.getBlitter().dest(2, 1).blit(g);
        g.pose().popPose();
        if (hovered) {
            g.fill(x + 1, y + 1, x + PRIORITY_BUTTON - 1, y + PRIORITY_BUTTON - 1, 0x40FFFFFF);
        }
    }

    private void drawFrames(GuiGraphics g, DriveCellListPacket.Group group, int groupTop) {
        int perRow = cellsPerRow();
        int rowsTotal = cellRows(group);
        for (int segment = 0; segment < segmentsPerRow(); segment++) {
            int first = segment * SEGMENT;
            int width = 0;
            int height = 0;
            for (int row = 0; row < rowsTotal; row++) {
                int cells = Math.min(SEGMENT, group.slots() - row * perRow - first);
                if (cells > 0) {
                    width = Math.max(width, cells);
                    height = row + 1;
                }
            }
            if (width == 0) {
                continue;
            }
            int x0 = contentLeft() + segment * SEGMENT_STRIDE;
            int y0 = groupTop + HEADER;
            int x1 = x0 + width * SLOT + 2 * FRAME - 1;
            int y1 = y0 + height * SLOT + 2 * FRAME - 1;
            g.hLine(x0, x1, y0, COLOR_FRAME);
            g.hLine(x0, x1, y1, COLOR_FRAME);
            g.vLine(x0, y0, y1, COLOR_FRAME);
            g.vLine(x1, y0, y1, COLOR_FRAME);
        }
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
            if (button == 0 && header.part() == HeaderPart.PRIORITY) {
                openPriority(header.group());
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
