package com.lhy.mest.client.panel;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.Icon;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.ModuleDefaults;
import com.lhy.mest.network.DriveCellActionPacket;
import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.network.DriveCellRequestPacket;
import com.lhy.mest.terminal.MESTMenu;

/** A paged, read-only view of physical drive slots; all transfers are validated server-side. */
public final class DrivePanel extends ModulePanel {
    private static final int ROW_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 17;
    private static final int FOOTER_HEIGHT = 27;
    private static final int WELL = 18;
    private static final int TRACK_WIDTH = 6;
    private static final int TRACK_GAP = 2;

    private final MESTMenu menu;
    private List<DriveCellListPacket.Row> rows = List.of();
    private int page;
    private int receivedPage = -1;
    private int revision = -1;
    private int totalSlots;
    private int totalDrives;
    private int scroll;
    private int ticks;
    private int requestedAt;
    private boolean truncated;
    private boolean subscribed;
    private boolean pending;
    private boolean dragging;

    public DrivePanel(MESTMenu menu) {
        this.menu = menu;
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
        return 132;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + HEADER_HEIGHT + ROW_HEIGHT + FOOTER_HEIGHT;
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    @Override
    public void layoutSlots() {
        // Remote cell icons are not menu slots; no client-owned slots are registered.
    }

    /** Accept only the current menu and requested page (including a server-clamped last page). */
    public void update(DriveCellListPacket packet) {
        if (!subscribed || packet.containerId() != menu.containerId || packet.page() < 0) {
            return;
        }
        int last = Math.max(0, (packet.totalSlots() - 1) / DriveCellListPacket.PAGE_SIZE);
        if (packet.page() != Math.min(page, last)) {
            return;
        }
        page = packet.page();
        receivedPage = page;
        revision = packet.revision();
        totalSlots = packet.totalSlots();
        totalDrives = packet.totalDrives();
        truncated = packet.truncated();
        rows = List.copyOf(packet.rows());
        scroll = Math.min(scroll, maxScroll());
        pending = false;
    }

    /** Called by the owning screen once each client tick with this panel's effective visibility. */
    public void tick(boolean visible) {
        ticks++;
        if (!visible) {
            closeSubscription();
            return;
        }
        if (!subscribed) {
            subscribed = true;
            requestPage();
        } else if (pending && ticks - requestedAt >= 10) {
            requestPage();
        } else if (!pending && ticks - requestedAt >= 40) {
            requestPage();
        }
    }

    public void closeSubscription() {
        if (subscribed) {
            PacketDistributor.sendToServer(new DriveCellRequestPacket(menu.containerId, -1));
            subscribed = false;
        }
        pending = false;
        dragging = false;
        receivedPage = -1;
        revision = -1;
        page = 0;
        totalSlots = 0;
        totalDrives = 0;
        truncated = false;
        rows = List.of();
        scroll = 0;
    }

    private void requestPage() {
        PacketDistributor.sendToServer(new DriveCellRequestPacket(menu.containerId, page));
        requestedAt = ticks;
        pending = receivedPage != page;
    }

    private void changePage(int next) {
        if (next < 0 || next > Math.max(0, (totalSlots - 1) / DriveCellListPacket.PAGE_SIZE)
                || next == page) {
            return;
        }
        page = next;
        scroll = 0;
        receivedPage = -1;
        revision = -1;
        rows = List.of();
        if (subscribed) {
            requestPage();
        }
    }

    private int visibleRows() {
        return Math.max(1, (contentHeight() - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows());
    }

    private int rowAt(double mx, double my) {
        int left = contentLeft();
        int top = contentTop() + HEADER_HEIGHT;
        int shown = Math.min(visibleRows(), rows.size() - scroll);
        int right = left + contentWidth() - (maxScroll() > 0 ? TRACK_WIDTH + TRACK_GAP : 0);
        if (!this.visible || receivedPage != page || mx < left || mx >= right
                || my < top || my >= top + shown * ROW_HEIGHT) {
            return -1;
        }
        return scroll + ((int) my - top) / ROW_HEIGHT;
    }

    /** Item tooltip should be rendered by the parent screen, above every panel. */
    public ItemStack hoveredItem(int x, int y) {
        int row = rowAt(x, y);
        return row < 0 ? ItemStack.EMPTY : rows.get(row).stack();
    }

    /** Additional location/transfer hints for the parent screen's hover tooltip. */
    public List<Component> hoveredText(int x, int y) {
        int row = rowAt(x, y);
        if (row < 0) {
            return List.of();
        }
        DriveCellListPacket.Row cell = rows.get(row);
        return List.of(cell.name(), Component.translatable("gui.mesplicedterminal.drive.slot", cell.slot() + 1),
                Component.translatable("gui.mesplicedterminal.drive.hint"));
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        int left = contentLeft();
        int top = contentTop();
        int right = left + contentWidth();
        if (right <= left) {
            return;
        }
        String summary = Component.translatable("gui.mesplicedterminal.drive.summary", totalDrives, totalSlots).getString();
        if (truncated) {
            summary += " +";
        }
        g.drawString(font, font.plainSubstrByWidth(summary, contentWidth()), left, top + 3, COLOR_TITLE_TEXT, false);
        int shown = Math.min(visibleRows(), Math.max(0, rows.size() - scroll));
        int rowRight = right - (maxScroll() > 0 ? TRACK_WIDTH + TRACK_GAP : 0);
        if (rows.isEmpty() && receivedPage == page) {
            g.drawString(font, Component.translatable("gui.mesplicedterminal.drive.empty"),
                    left + 2, top + HEADER_HEIGHT + 4, COLOR_MUTED, false);
        }
        for (int i = 0; i < shown; i++) {
            DriveCellListPacket.Row cell = rows.get(scroll + i);
            int ry = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            g.fill(left, ry, rowRight, ry + ROW_HEIGHT - 1, 0xFFADB0C4);
            g.hLine(left, rowRight - 1, ry, COLOR_LIGHT);
            g.vLine(left, ry, ry + ROW_HEIGHT - 2, COLOR_LIGHT);
            g.hLine(left, rowRight - 1, ry + ROW_HEIGHT - 2, COLOR_DARK);
            g.vLine(rowRight - 1, ry, ry + ROW_HEIGHT - 2, COLOR_DARK);
            if (rowAt(mouseX, mouseY) == scroll + i) {
                g.fill(left + 1, ry + 1, rowRight - 1, ry + ROW_HEIGHT - 2, 0x669CD3FF);
            }
            if (!cell.stack().isEmpty()) {
                g.renderItem(cell.stack(), left + 2, ry + 1);
            }
            String location = "#" + (cell.drive() + 1) + ":" + (cell.slot() + 1) + " " + cell.name().getString();
            g.drawString(font, font.plainSubstrByWidth(location, Math.max(0, rowRight - left - WELL - 3)),
                    left + WELL + 1, ry + 6, COLOR_TITLE_TEXT, false);
        }
        if (maxScroll() > 0) {
            int railTop = top + HEADER_HEIGHT;
            int railHeight = visibleRows() * ROW_HEIGHT;
            int railX = right - TRACK_WIDTH;
            int thumbHeight = Math.max(12, railHeight * visibleRows() / rows.size());
            int thumbTop = railTop + (railHeight - thumbHeight) * scroll / maxScroll();
            g.fill(railX, railTop, right, railTop + railHeight, COLOR_DARK);
            g.fill(railX + 1, railTop + 1, right - 1, railTop + railHeight - 1, COLOR_LIGHT);
            g.fill(railX + 1, thumbTop, right - 1, thumbTop + thumbHeight, COLOR_MUTED);
        }
        int footer = top + HEADER_HEIGHT + visibleRows() * ROW_HEIGHT + 2;
        if (footer + FOOTER_HEIGHT > top + contentHeight()) {
            footer = top + contentHeight() - FOOTER_HEIGHT;
        }
        int pages = Math.max(1, (totalSlots + DriveCellListPacket.PAGE_SIZE - 1) / DriveCellListPacket.PAGE_SIZE);
        g.drawString(font, "<", left + 2, footer + 2, page > 0 ? COLOR_TITLE_TEXT : COLOR_MUTED, false);
        String pageLabel = Component.translatable("gui.mesplicedterminal.drive.page", page + 1, pages).getString();
        g.drawString(font, pageLabel, left + (contentWidth() - font.width(pageLabel)) / 2,
                footer + 2, COLOR_TITLE_TEXT, false);
        g.drawString(font, ">", right - 9, footer + 2,
                page + 1 < pages ? COLOR_TITLE_TEXT : COLOR_MUTED, false);
        String hint = Component.translatable("gui.mesplicedterminal.drive.hint").getString();
        g.drawString(font, font.plainSubstrByWidth(truncated
                ? Component.translatable("gui.mesplicedterminal.drive.truncation").getString() : hint,
                contentWidth()), left, footer + 14, COLOR_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !contains(mx, my)) {
            return false;
        }
        int row = rowAt(mx, my);
        if (row >= 0 && (button == 0 || button == 1)) {
            PacketDistributor.sendToServer(new DriveCellActionPacket(menu.containerId, revision, row,
                    button == 1 || Screen.hasShiftDown()
                            ? DriveCellActionPacket.Action.QUICK_MOVE : DriveCellActionPacket.Action.CURSOR));
            return true;
        }
        int footer = Math.min(contentTop() + HEADER_HEIGHT + visibleRows() * ROW_HEIGHT + 2,
                contentTop() + contentHeight() - FOOTER_HEIGHT);
        if (button == 0 && my >= footer && my < footer + 13) {
            if (mx >= contentLeft() && mx < contentLeft() + 15) {
                changePage(page - 1);
                return true;
            }
            if (mx >= contentLeft() + contentWidth() - 15 && mx < contentLeft() + contentWidth()) {
                changePage(page + 1);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || !contains(mx, my) || scrollY == 0) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (maxScroll() == 0 || !visible || mx < contentLeft() + contentWidth() - TRACK_WIDTH
                || mx >= contentLeft() + contentWidth() || my < contentTop() + HEADER_HEIGHT
                || my >= contentTop() + HEADER_HEIGHT + visibleRows() * ROW_HEIGHT) {
            return false;
        }
        dragging = true;
        scrollbarDragged(mx, my);
        return true;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!dragging || maxScroll() == 0) {
            dragging = false;
            return false;
        }
        int height = visibleRows() * ROW_HEIGHT;
        int thumbHeight = Math.max(12, height * visibleRows() / rows.size());
        double fraction = (my - contentTop() - HEADER_HEIGHT - thumbHeight / 2.0) / (height - thumbHeight);
        scroll = Math.max(0, Math.min(maxScroll(), (int) Math.round(fraction * maxScroll())));
        return true;
    }

    @Override
    public boolean scrollbarDragging() {
        return dragging;
    }

    @Override
    public void scrollbarReleased() {
        dragging = false;
    }
}
