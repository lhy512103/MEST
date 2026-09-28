package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.AEKey;
import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.api.storage.cells.CellState;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.MestGuiIcons;
import com.lhy.mest.client.PinyinSearch;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.network.DriveCellRequestPacket;
import com.lhy.mest.network.DriveCellSearchPacket;
import com.lhy.mest.network.DrivePriorityPacket;
import com.lhy.mest.terminal.MESTMenu;

/** AE2-style physical cell wells grouped by drive with state LEDs; the server owns the inventories. */
public final class DrivePanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int MIN_COLUMNS = 5;
    private static final int HEADER = 18;
    // One well outlined in white around every drive, matching the pattern access panel.
    private static final int FRAME = 1;
    private static final int WELL_FILL = 0xFF9A9FB4;
    private static final int SLOT_FILL = 0xFFADB0C4;
    private static final int SLOT_BORDER = 0xFF878FA5;
    // AE2's 20px tab button, scaled to sit inside the header row.
    private static final int PRIORITY_BUTTON = 16;
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
    private static final int SEARCH_ROW = 16;
    private static final int SEARCH_WIDTH = 65;
    private static final int SEARCH_HEIGHT = 10;
    private static final long RAINBOW_PERIOD_MS = 4000L;
    private static final int SLOT_HOVER_FILL = 0x669CD3FF;
    private static final int SLOT_HOVER_BORDER = 0xFFDAFFFF;

    private final MESTMenu menu;
    private final ScreenStyle style;
    private AETextField searchField;
    private String search = "";
    private int searchId;
    private int pendingScrollId = -1;
    private int matchCount;
    private BitSet matches = new BitSet();
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

    public DrivePanel(MESTMenu menu, ScreenStyle style) {
        this.menu = menu;
        this.style = style;
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
    public void renderIcon(GuiGraphics g, int x, int y, int w, int h, float opacity) {
        MestGuiIcons.blit(g, 12, 0, x, y, w, h, opacity);
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
        return 2 * CONTENT_PADDING + contentRightInset + MIN_COLUMNS * SLOT + 2 * FRAME + GUTTER;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + SEARCH_ROW + CONTENT_PADDING + HEADER + SLOT + 2 * FRAME;
    }

    @Override
    public int contentTop() {
        return super.contentTop() + SEARCH_ROW;
    }

    @Override
    public int contentHeight() {
        return Math.max(0, super.contentHeight() - SEARCH_ROW);
    }

    @Override
    public boolean inTitleBarControls(double mx, double my) {
        return super.inTitleBarControls(mx, my)
                || (searchField != null && searchField.visible && searchField.isMouseOver(mx, my));
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    @Override
    public void layoutSlots() {
        // The grid is virtual: dynamically changing remote drives cannot alter menu slot indices.
        layoutSearch();
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
        matches = new BitSet();
        for (int match : packet.matches()) {
            matches.set(match);
        }
        matchCount = packet.matches().size();
        layoutScrollbar();
        if (packet.searchId() == pendingScrollId) {
            pendingScrollId = -1;
            if (!packet.matches().isEmpty()) {
                scrollToSlot(packet.matches().get(0));
            }
        }
        syncWindow();
    }

    private void scrollToSlot(int absolute) {
        int pixels = 0;
        int first = 0;
        for (var group : groups) {
            if (absolute < first + group.slots()) {
                pixels += (absolute - first) / cellsPerRow() * SLOT;
                break;
            }
            pixels += groupHeight(group);
            first += group.slots();
        }
        scrollbar.setCurrentScroll(pixels / SCROLL_STEP);
    }

    private void onSearchChanged(String value) {
        search = value;
        searchId = (searchId + 1) & Integer.MAX_VALUE;
        pendingScrollId = search.isBlank() ? -1 : searchId;
        if (search.isBlank()) {
            matches = new BitSet();
            matchCount = 0;
        }
        if (subscribed) {
            sendSearch();
        }
    }

    private void sendSearch() {
        String query = search.strip();
        var keys = new ArrayList<AEKey>();
        var repo = menu.getClientRepo();
        if (!query.isEmpty() && repo != null) {
            for (var entry : repo.getAllEntries()) {
                if (keys.size() >= DriveCellSearchPacket.MAX_KEYS) {
                    break;
                }
                if (entry.getStoredAmount() > 0
                        && PinyinSearch.contains(entry.getWhat().getDisplayName().getString(), query)) {
                    keys.add(entry.getWhat());
                }
            }
        }
        PacketDistributor.sendToServer(new DriveCellSearchPacket(menu.containerId, searchId, query, keys));
    }

    public void tick(boolean visible) {
        ticks++;
        layoutSearch();
        if (!visible) {
            closeSubscription();
            return;
        }
        if (!subscribed) {
            subscribed = true;
            requestWindow(0);
            if (!search.isBlank()) {
                pendingScrollId = searchId;
                sendSearch();
            }
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
        matches = new BitSet();
        matchCount = 0;
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

    private int cellsPerRow() {
        return Math.max(1, (listWidth() - 2 * FRAME) / SLOT);
    }

    private int cellRows(DriveCellListPacket.Group group) {
        return (group.slots() + cellsPerRow() - 1) / cellsPerRow();
    }

    private int groupHeight(DriveCellListPacket.Group group) {
        return HEADER + cellRows(group) * SLOT;
    }

    private int cellX(int slot) {
        return gridLeft() + slot % cellsPerRow() * SLOT;
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
                Math.max(0, totalHeight() - innerHeight()));
    }

    private int listWidth() {
        return Math.max(1, contentWidth() - GUTTER);
    }

    private int gridLeft() {
        return contentLeft() + FRAME;
    }

    private int gridRight() {
        return gridLeft() + cellsPerRow() * SLOT;
    }

    private int innerTop() {
        return contentTop() + FRAME;
    }

    private int innerHeight() {
        return Math.max(0, contentHeight() - 2 * FRAME);
    }

    private int trackX() {
        return gridRight() + FRAME + TRACK_GAP + TRACK_SHIFT_X;
    }

    private void layoutScrollbar() {
        int max = Math.max(0, totalHeight() - innerHeight());
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

    private int priorityX() {
        return gridRight() - PRIORITY_BUTTON - 1;
    }

    private HeaderHit headerAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int y = innerTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            if (my >= y && my < y + HEADER) {
                var group = groups.get(i);
                if (group.editable() && mx >= priorityX() && mx < priorityX() + PRIORITY_BUTTON
                        && my >= y + 1 && my < y + 1 + PRIORITY_BUTTON) {
                    return new HeaderHit(i, HeaderPart.PRIORITY);
                }
                return mx < (group.editable() ? priorityX() - 2 : gridRight()) ? new HeaderHit(i, HeaderPart.NAME) : null;
            }
            y += groupHeight(groups.get(i));
        }
        return null;
    }

    private CellHit cellAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int relX = (int) mx - gridLeft();
        if (relX < 0 || relX >= cellsPerRow() * SLOT) {
            return null;
        }
        int column = relX / SLOT;
        int y = innerTop() - scrollPixels();
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
        return visible && mx >= gridLeft() && mx < gridRight()
                && my >= innerTop() && my < innerTop() + innerHeight();
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
        // Stacks sent without components (over the size budget) lack AE2's own capacity lines.
        if (row.hasCapacity() && row.usedBytes() > 0 && row.stack().getComponentsPatch().isEmpty()) {
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
        ensureSearch(font);
        layoutSearch();
        if (searchField != null) {
            searchField.render(g, mouseX, mouseY, partialTicks);
            if (searchField.visible && !search.isBlank()) {
                g.drawString(font, Component.translatable("gui.mesplicedterminal.drive.matches", matchCount),
                        contentLeft() + SEARCH_WIDTH + 4, y + TITLE_BAR_HEIGHT + 2, COLOR_MUTED, false);
            }
        }
        if (contentWidth() <= 0 || contentHeight() <= 0) {
            return;
        }
        int left = gridLeft();
        int top = innerTop();
        int bottom = top + innerHeight();
        CellHit hovered = cellAt(mouseX, mouseY);
        HeaderHit header = headerAt(mouseX, mouseY);
        drawWell(g);
        g.enableScissor(left, top, gridRight(), bottom);
        try {
            int groupY = top - scrollPixels();
            int firstSlot = 0;
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                int height = groupHeight(group);
                if (groupY + height > top && groupY < bottom) {
                    drawGroup(g, font, i, group, groupY, firstSlot, hovered == null ? -1 : hovered.index(),
                            header != null && header.group() == i ? header.part() : null);
                }
                groupY += height;
                firstSlot += group.slots();
            }
            if (groups.isEmpty() && receivedWindow >= 0) {
                g.drawString(font, Component.translatable("gui.mesplicedterminal.drive.empty"),
                        left + 2, top + 3, COLOR_MUTED, false);
            }
        } finally {
            g.disableScissor();
        }
        // Outside the scissor: the SMALL thumb is wider than the track and would be clipped on the right.
        drawTrack(g, trackX(), contentTop(), contentHeight());
        scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
    }

    private void drawGroup(GuiGraphics g, Font font, int index, DriveCellListPacket.Group group,
            int y, int firstSlot, int hovered, HeaderPart headerPart) {
        int left = gridLeft();
        int nameRight = group.editable() ? priorityX() - 2 : gridRight() - 2;
        if (!group.powered()) {
            Component offline = Component.translatable("gui.mesplicedterminal.drive.offline");
            nameRight -= font.width(offline);
            g.drawString(font, offline, nameRight, y + 6, COLOR_OFFLINE, false);
            nameRight -= 4;
        }
        g.pose().pushPose();
        g.pose().translate(left + 2, y + 5, 0);
        g.pose().scale(0.5F, 0.5F, 1.0F);
        g.renderItem(new ItemStack(group.icon()), 0, 0);
        g.pose().popPose();
        String name = group.name().getString();
        int nameWidth = Math.max(0, nameRight - left - 12);
        String shown = name;
        if (font.width(name) > nameWidth) {
            shown = font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("..."))) + "...";
        }
        g.drawString(font, shown, left + 12, y + 6, group.powered() ? titleColor(group) : COLOR_MUTED, false);
        if (group.editable()) {
            drawPriorityButton(g, priorityX(), y + 1, headerPart == HeaderPart.PRIORITY);
        }
        int columns = cellsPerRow();
        for (int slot = 0; slot < group.slots(); slot++) {
            int px = cellX(slot);
            int py = cellY(y, slot);
            drawSlotCell(g, px, py, slot % columns, columns);
            int rowIndex = slotIndex(index, slot);
            var row = rowIndex >= 0 ? rows.get(rowIndex) : null;
            if (row == null || row.stack().isEmpty()) {
                Icon.BACKGROUND_STORAGE_CELL.getBlitter().dest(px + 1, py + 1).blit(g);
            } else {
                g.renderItem(row.stack(), px + 1, py + 1);
                drawCellState(g, row, px, py, group.powered());
            }
            boolean matched = !search.isBlank() && matches.get(firstSlot + slot);
            boolean hovering = rowIndex >= 0 && rowIndex == hovered;
            if (!matched && !hovering) {
                continue;
            }
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            if (matched) {
                drawRainbowHighlight(g, px + 1, py + 1);
            }
            if (hovering) {
                int ix = px + 1;
                int iy = py + 1;
                g.hLine(ix, ix + 16, iy - 1, SLOT_HOVER_BORDER);
                g.hLine(ix - 1, ix + 16, iy + 16, SLOT_HOVER_BORDER);
                g.vLine(ix - 1, iy - 2, iy + 16, SLOT_HOVER_BORDER);
                g.vLine(ix + 16, iy - 2, iy + 16, SLOT_HOVER_BORDER);
                g.fillGradient(RenderType.guiOverlay(), ix, iy, ix + 16, iy + 16,
                        SLOT_HOVER_FILL, SLOT_HOVER_FILL, 0);
            }
            g.pose().popPose();
        }
    }

    private void drawWell(GuiGraphics g) {
        int x0 = contentLeft();
        int y0 = contentTop();
        int x1 = gridRight() + FRAME;
        int y1 = contentTop() + contentHeight();
        g.fill(x0, y0, x1, y1, WELL_FILL);
        g.fill(x0, y0, x1, y0 + 1, COLOR_LIGHT);
        g.fill(x0, y1 - 1, x1, y1, COLOR_LIGHT);
        g.fill(x0, y0, x0 + 1, y1, COLOR_LIGHT);
        g.fill(x1 - 1, y0, x1, y1, COLOR_LIGHT);
    }

    /** Same look as ExtendedAE Plus's pattern search highlight, without depending on it. */
    private static void drawRainbowHighlight(GuiGraphics g, int x, int y) {
        float hue = (System.currentTimeMillis() % RAINBOW_PERIOD_MS) / (float) RAINBOW_PERIOD_MS;
        int rgb = Mth.hsvToRgb(hue, 1.0F, 1.0F) & 0xFFFFFF;
        int border = 0xA0000000 | rgb;
        g.fill(x - 1, y - 1, x + 17, y, border);
        g.fill(x - 1, y + 16, x + 17, y + 17, border);
        g.fill(x - 1, y, x, y + 16, border);
        g.fill(x + 16, y, x + 17, y + 16, border);
        g.fill(x, y, x + 16, y + 16, 0x3C000000 | rgb);
    }

    private static void drawSlotCell(GuiGraphics g, int x, int y, int col, int columns) {
        g.fill(x, y, x + SLOT, y + SLOT, SLOT_FILL);
        g.fill(x, y, x + SLOT, y + 1, SLOT_BORDER);
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, SLOT_BORDER);
        if (col > 0) {
            g.fill(x, y, x + 1, y + SLOT, SLOT_BORDER);
        }
        if (col < columns - 1) {
            g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, SLOT_BORDER);
        }
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

    private static void drawCellState(GuiGraphics g, DriveCellListPacket.Row row, int px, int py, boolean powered) {
        CellState state = CellState.values()[row.status()];
        int color = powered ? 0xFF000000 | state.getStateColor() : COLOR_LED_OFF;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        // Status LED in the top-right corner, like the light on the physical drive face.
        g.fill(px + 13, py + 1, px + 17, py + 5, 0xFF1A1A1A);
        g.fill(px + 14, py + 2, px + 16, py + 4, color);
        if (row.hasCapacity()) {
            // Spans the slot interior and rests on its bottom border.
            double fraction = Math.min(1.0, row.usedBytes() / (double) row.totalBytes());
            int width = row.usedBytes() > 0 ? Math.max(1, (int) Math.round((SLOT - 2) * fraction)) : 0;
            g.fill(px + 1, py + SLOT - 3, px + SLOT - 1, py + SLOT - 1, 0xFF000000);
            if (width > 0) {
                g.fill(px + 1, py + SLOT - 3, px + 1 + width, py + SLOT - 2, color);
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
        if (inResizeHandle(mx, my)) {
            return false;
        }
        if (!visible || !contains(mx, my)) {
            if (searchField != null) {
                searchField.setFocused(false);
            }
            return false;
        }
        // AE2's MEStorageScreen clears its search field on right-click; AETextField itself does not.
        if (searchField != null && searchField.visible && searchField.isMouseOver(mx, my) && button == 1) {
            searchField.setValue("");
            setSearchFocused(true);
            return true;
        }
        if (searchField != null && searchField.mouseClicked(mx, my, button)) {
            setSearchFocused(true);
            return true;
        }
        if (searchField != null) {
            searchField.setFocused(false);
        }
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

    public boolean searchCharTyped(char character, int modifiers) {
        return searchField != null && searchField.isFocused() && searchField.charTyped(character, modifiers);
    }

    public boolean searchKeyPressed(int keyCode, int scanCode, int modifiers) {
        return searchField != null && searchField.isFocused() && searchField.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean isSearchFocused() {
        return searchField != null && searchField.isFocused();
    }

    public void setSearchFocused(boolean focused) {
        if (searchField == null) {
            return;
        }
        searchField.setFocused(focused);
        if (Minecraft.getInstance().screen instanceof MESTScreen screen) {
            if (focused) {
                screen.setFocused(searchField);
            } else if (screen.getFocused() == searchField) {
                screen.setFocused(null);
            }
        }
    }

    public AETextField searchField() {
        return searchField;
    }

    public void rebindSearchField(MESTScreen screen) {
        if (searchField != null) {
            screen.attachPatternSearchField(searchField);
        }
    }

    private void ensureSearch(Font font) {
        if (searchField != null) {
            return;
        }
        searchField = new AETextField(style, font, 0, 0, SEARCH_WIDTH, SEARCH_HEIGHT);
        searchField.setBordered(false);
        searchField.setMaxLength(DriveCellSearchPacket.MAX_QUERY);
        searchField.setValue(search);
        searchField.setResponder(this::onSearchChanged);
        searchField.setPlaceholder(GuiText.SearchPlaceholder.text());
        if (Minecraft.getInstance().screen instanceof MESTScreen screen) {
            screen.attachPatternSearchField(searchField);
        }
    }

    private void layoutSearch() {
        if (searchField == null) {
            return;
        }
        boolean show = visible && drawsTitleBar();
        searchField.setVisible(show);
        if (show) {
            searchField.resize(SEARCH_WIDTH, SEARCH_HEIGHT);
            searchField.move(new Point(contentLeft(), y + TITLE_BAR_HEIGHT + 1));
        } else {
            searchField.setFocused(false);
        }
    }
}
