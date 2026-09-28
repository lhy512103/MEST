package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.Scrollbar;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.network.DriveCellRequestPacket;
import com.lhy.mest.network.DrivePriorityPacket;
import com.lhy.mest.terminal.MESTMenu;

/** AE2-style physical cell wells grouped by drive; the server owns the actual inventories. */
public final class DrivePanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int BANK_SLOTS = 10;
    private static final int BANK_WIDTH = 2 * SLOT;
    private static final int BANK_GAP = 12;
    private static final int BANK_ROW_GAP = 7;
    private static final int HEADER = 29;
    private static final int GROUP_GAP = 9;
    private static final int PRIORITY_TOP = 15;
    private static final int PRIORITY_WIDTH = 92;
    private static final int BUTTON = 12;
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

    private record CellHit(int index, ItemStack stack) {
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
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER + 2 * SLOT + GROUP_GAP;
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

    private int bankColumns() {
        return Math.max(1, (listWidth() + BANK_GAP) / (BANK_WIDTH + BANK_GAP));
    }

    private int bankRows(DriveCellListPacket.Group group, int bank) {
        int slots = Math.min(BANK_SLOTS, group.slots() - bank * BANK_SLOTS);
        return Math.max(0, (slots + 1) / 2);
    }

    private int groupHeight(DriveCellListPacket.Group group) {
        int bankCount = (group.slots() + BANK_SLOTS - 1) / BANK_SLOTS;
        int height = HEADER + GROUP_GAP;
        for (int first = 0; first < bankCount; first += bankColumns()) {
            int count = Math.min(bankColumns(), bankCount - first);
            int rows = 0;
            for (int bank = 0; bank < count; bank++) {
                rows = Math.max(rows, bankRows(group, first + bank));
            }
            height += rows * SLOT;
            if (first + count < bankCount) {
                height += BANK_ROW_GAP;
            }
        }
        return height;
    }

    private int groupY(int groupIndex) {
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groupIndex; i++) {
            y += groupHeight(groups.get(i));
        }
        return y;
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

    private int groupAt(double mx, double my) {
        if (!inList(mx, my)) {
            return -1;
        }
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            int bottom = y + groupHeight(groups.get(i));
            if (my >= y && my < y + HEADER) {
                return i;
            }
            y = bottom;
        }
        return -1;
    }

    private CellHit cellAt(double mx, double my) {
        if (!inList(mx, my)) {
            return null;
        }
        int y = contentTop() - scrollPixels();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            int bottom = y + groupHeight(group);
            if (my >= y + HEADER && my < bottom - GROUP_GAP) {
                int firstBank = 0;
                int bankTop = y + HEADER;
                int bankCount = (group.slots() + BANK_SLOTS - 1) / BANK_SLOTS;
                while (firstBank < bankCount) {
                    int count = Math.min(bankColumns(), bankCount - firstBank);
                    int height = 0;
                    for (int n = 0; n < count; n++) {
                        height = Math.max(height, bankRows(group, firstBank + n) * SLOT);
                    }
                    int startX = contentLeft() + (listWidth() - count * BANK_WIDTH - (count - 1) * BANK_GAP) / 2;
                    for (int n = 0; n < count; n++) {
                        int bankX = startX + n * (BANK_WIDTH + BANK_GAP);
                        int column = (int) (mx - bankX) / SLOT;
                        int row = (int) (my - bankTop) / SLOT;
                        if (mx >= bankX && mx < bankX + BANK_WIDTH && my >= bankTop
                                && my < bankTop + bankRows(group, firstBank + n) * SLOT) {
                            int slot = (firstBank + n) * BANK_SLOTS + row * 2 + column;
                            if (slot < group.slots() && slot < (firstBank + n + 1) * BANK_SLOTS) {
                                int index = slotIndex(i, slot);
                                return index < 0 ? null : new CellHit(index, rows.get(index).stack());
                            }
                        }
                    }
                    firstBank += count;
                    bankTop += height + BANK_ROW_GAP;
                }
            }
            y = bottom;
        }
        return null;
    }

    private boolean inList(double mx, double my) {
        return visible && mx >= contentLeft() && mx < contentLeft() + listWidth()
                && my >= contentTop() && my < contentTop() + contentHeight();
    }

    public ItemStack hoveredItem(int x, int y) {
        CellHit cell = cellAt(x, y);
        return cell == null ? ItemStack.EMPTY : cell.stack();
    }

    public List<Component> hoveredText(int x, int y) {
        int group = groupAt(x, y);
        if (group < 0 || !groups.get(group).editable()
                || y < groupY(group) + PRIORITY_TOP || y >= groupY(group) + PRIORITY_TOP + BUTTON) {
            return List.of();
        }
        int right = contentLeft() + listWidth();
        if (x >= right - PRIORITY_WIDTH && x < right - PRIORITY_WIDTH + BUTTON) {
            return List.of(Component.translatable("gui.mesplicedterminal.drive.priority.down"));
        }
        if (x >= right - BUTTON && x < right) {
            return List.of(Component.translatable("gui.mesplicedterminal.drive.priority.up"));
        }
        return List.of();
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (contentWidth() <= 0 || contentHeight() <= 0) {
            return;
        }
        int left = contentLeft();
        int top = contentTop();
        int right = left + listWidth();
        int bottom = top + contentHeight();
        CellHit hovered = cellAt(mouseX, mouseY);
        g.enableScissor(left, top, left + contentWidth(), bottom);
        try {
            int y = top - scrollPixels();
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                int height = groupHeight(group);
                if (y + height > top && y < bottom) {
                    drawGroup(g, font, i, group, y, right, hovered == null ? -1 : hovered.index(), mouseX, mouseY);
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
            int y, int right, int hovered, int mouseX, int mouseY) {
        int left = contentLeft();
        int textWidth = Math.max(12, listWidth() - 4);
        g.drawString(font, font.plainSubstrByWidth(group.name().getString(), textWidth),
                left + 2, y + 3, COLOR_TITLE_TEXT, false);
        if (group.editable()) {
            int minus = right - PRIORITY_WIDTH;
            drawButton(g, font, Component.literal("-"), minus, y + PRIORITY_TOP, BUTTON, BUTTON,
                    groupAt(mouseX, mouseY) == index && mouseY >= y + PRIORITY_TOP
                            && mouseY < y + PRIORITY_TOP + BUTTON && mouseX >= minus && mouseX < minus + BUTTON);
            int plus = right - BUTTON;
            drawButton(g, font, Component.literal("+"), plus, y + PRIORITY_TOP, BUTTON, BUTTON,
                    groupAt(mouseX, mouseY) == index && mouseY >= y + PRIORITY_TOP
                            && mouseY < y + PRIORITY_TOP + BUTTON && mouseX >= plus && mouseX < right);
            String priority = font.plainSubstrByWidth(Integer.toString(group.priority()), PRIORITY_WIDTH - 30);
            g.drawString(font, priority, minus + BUTTON + (PRIORITY_WIDTH - 2 * BUTTON - font.width(priority)) / 2,
                    y + PRIORITY_TOP + 2, COLOR_TITLE_TEXT, false);
        }
        int bankTop = y + HEADER;
        int banks = (group.slots() + BANK_SLOTS - 1) / BANK_SLOTS;
        for (int first = 0; first < banks; first += bankColumns()) {
            int count = Math.min(bankColumns(), banks - first);
            int rowCount = 0;
            for (int n = 0; n < count; n++) {
                rowCount = Math.max(rowCount, bankRows(group, first + n));
            }
            int startX = left + (listWidth() - count * BANK_WIDTH - (count - 1) * BANK_GAP) / 2;
            for (int n = 0; n < count; n++) {
                int bankX = startX + n * (BANK_WIDTH + BANK_GAP);
                for (int slotInBank = 0; slotInBank < Math.min(BANK_SLOTS, group.slots() - (first + n) * BANK_SLOTS);
                        slotInBank++) {
                    int slot = (first + n) * BANK_SLOTS + slotInBank;
                    int px = bankX + (slotInBank % 2) * SLOT;
                    int py = bankTop + (slotInBank / 2) * SLOT;
                    drawSlot(g, px, py);
                    Icon.BACKGROUND_STORAGE_CELL.getBlitter().dest(px + 1, py + 1).blit(g);
                    int rowIndex = slotIndex(index, slot);
                    if (rowIndex >= 0) {
                        ItemStack stack = rows.get(rowIndex).stack();
                        if (!stack.isEmpty()) {
                            g.renderItem(stack, px + 1, py + 1);
                        }
                        if (rowIndex == hovered) {
                            g.fill(px + 1, py + 1, px + SLOT - 1, py + SLOT - 1, 0x669CD3FF);
                        }
                    }
                }
            }
            bankTop += rowCount * SLOT + BANK_ROW_GAP;
        }
        g.fill(left, y + groupHeight(group) - 3, right, y + groupHeight(group) - 2, SECTION_RULE_COLOR);
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
        int group = groupAt(mx, my);
        if (group >= 0 && button == 0 && revision >= 0
                && groups.get(group).editable() && my >= groupY(group) + PRIORITY_TOP
                && my < groupY(group) + PRIORITY_TOP + BUTTON) {
            int right = contentLeft() + listWidth();
            int delta = mx >= right - BUTTON && mx < right ? 1
                    : mx >= right - PRIORITY_WIDTH && mx < right - PRIORITY_WIDTH + BUTTON ? -1 : 0;
            if (delta != 0) {
                PacketDistributor.sendToServer(new DrivePriorityPacket(menu.containerId, revision, group, delta));
                return true;
            }
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
