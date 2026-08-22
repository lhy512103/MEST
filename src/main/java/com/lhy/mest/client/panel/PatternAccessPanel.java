package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;

import com.lhy.mest.client.PatternProviderClientHandler;
import com.lhy.mest.client.PatternProviderClientHandler.Entry;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.Scrollbar;
import com.lhy.mest.network.PatternProviderActionPacket;
import com.lhy.mest.network.PatternProviderListPacket;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Lightweight pattern access view for the dockable workspace. It mirrors the visible pattern
 * providers and their pattern slots without using AE2's fixed PatternAccessTermScreen.
 */
public class PatternAccessPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int ROW = 18;
    private static final int COLUMNS = 9;
    private static final int SCROLLBAR_WIDTH = 10;
    private static final int SCROLLBAR_GAP = 2;
    private static final int REQUIRED_CONTENT_WIDTH = COLUMNS * SLOT + SCROLLBAR_GAP + SCROLLBAR_WIDTH;

    private final List<Entry> providers = new ArrayList<>();
    private final Scrollbar scrollbar = new Scrollbar();
    private int scrollRows;
    private int subscribedContainerId = -1;
    private MESTMenu subscribedMenu;
    private ItemStack hoveredPattern = ItemStack.EMPTY;
    private List<Component> hoveredProviderTooltip = List.of();

    @Override
    public String id() {
        return "pattern_access";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.pattern_access");
    }

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + REQUIRED_CONTENT_WIDTH;
    }

    @Override
    public int defaultHeight() {
        return 174;
    }

    @Override
    public int minWidth() {
        return 2 * CONTENT_PADDING + REQUIRED_CONTENT_WIDTH;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + 4 * ROW;
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    public void tick() {
        updateSubscription();
    }

    public void setProviders(List<Entry> entries) {
        if (subscribedContainerId < 0) {
            return;
        }
        providers.clear();
        providers.addAll(entries);
        scrollRows = Math.min(scrollRows, maxScrollRows());
        scrollbar.setScroll(scrollRows);
    }

    public void requestProviders() {
        var menu = currentMenu();
        if (menu == null || !visible) {
            unsubscribe();
            return;
        }
        if (subscribedMenu != menu) {
            unsubscribe();
            subscribedContainerId = menu.containerId;
            subscribedMenu = menu;
            PatternProviderClientHandler.beginSubscription(menu);
        }
        PacketDistributor.sendToServer(new PatternProviderListPacket.Request(menu.containerId, true));
    }

    @Override
    public void layoutSlots() {
        // Pattern provider contents are drawn from packet data, not menu Slot objects.
        updateSubscription();
    }

    private void updateSubscription() {
        var menu = currentMenu();
        if (!visible || menu == null) {
            unsubscribe();
            return;
        }
        if (subscribedMenu != menu) {
            unsubscribe();
            subscribedContainerId = menu.containerId;
            subscribedMenu = menu;
            PatternProviderClientHandler.beginSubscription(menu);
            PacketDistributor.sendToServer(new PatternProviderListPacket.Request(menu.containerId, true));
        }
    }

    public void closeSubscription() {
        unsubscribe();
    }

    private void unsubscribe() {
        var minecraft = Minecraft.getInstance();
        if (subscribedContainerId >= 0
                && subscribedMenu != null
                && minecraft.player != null
                && minecraft.player.containerMenu == subscribedMenu
                && minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(
                    new PatternProviderListPacket.Request(subscribedContainerId, false));
        }
        if (subscribedMenu != null) {
            PatternProviderClientHandler.endSubscription(subscribedMenu);
        }
        subscribedContainerId = -1;
        subscribedMenu = null;
        providers.clear();
        scrollRows = 0;
        scrollbar.setScroll(0);
    }

    private static MESTMenu currentMenu() {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof MESTMenu menu ? menu : null;
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        hoveredPattern = ItemStack.EMPTY;
        hoveredProviderTooltip = List.of();

        int maxRows = visibleRows();
        if (providers.isEmpty()) {
            g.drawString(font, Component.translatable("gui.mesplicedterminal.pattern_access.empty"),
                    contentLeft() + 2, contentTop() + 2, COLOR_TITLE_TEXT, false);
            return;
        }
        g.drawString(font, Component.translatable("gui.mesplicedterminal.pattern_access.click_hint"),
                contentLeft() + 2, contentTop(), COLOR_MUTED, false);

        for (PatternAccessRowLayout.Row row : layoutRows(maxRows)) {
            var provider = providers.get(row.providerIndex());
            if (row.isHeader()) {
                renderProviderHeader(g, font, provider, row.visibleRow(), mouseX, mouseY);
            } else {
                renderProviderSlotRow(g, font, provider, row.slotRow(), row.visibleRow(), mouseX, mouseY);
            }
        }

        int maxScroll = Math.max(0, totalRows() - Math.max(1, maxRows - 1));
        scrollbar.setScroll(scrollRows);
        scrollbar.render(g,
                contentLeft() + contentWidth() - SCROLLBAR_WIDTH, contentTop(),
                SCROLLBAR_WIDTH, contentHeight(),
                maxScroll, true);
    }

    @Override
    public void renderForegroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (!hoveredPattern.isEmpty()) {
            var details = PatternDetailsHelper.decodePattern(hoveredPattern, net.minecraft.client.Minecraft.getInstance().level);
            var lines = new ArrayList<Component>();
            lines.add(hoveredPattern.getHoverName());
            if (details != null) {
                for (var output : details.getOutputs()) {
                    lines.add(output.what().getDisplayName()
                            .copy()
                            .append(Component.literal(" x" + output.amount()))
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (!hoveredProviderTooltip.isEmpty()) {
            g.renderComponentTooltip(font, hoveredProviderTooltip, mouseX, mouseY);
        }
    }

    private void renderProviderHeader(GuiGraphics g, Font font, Entry provider,
            int row, int mouseX, int mouseY) {
        int x = contentLeft();
        int y = contentTop() + row * ROW;
        g.fill(x, y, x + contentWidth(), y + ROW - 1, 0xFFD5D7DF);
        g.fill(x, y + ROW - 2, x + contentWidth(), y + ROW - 1, COLOR_LIGHT);
        AEItemKey icon = provider.group().icon();
        int textX = x + 2;
        if (icon != null) {
            ItemStack iconStack = icon.getReadOnlyStack();
            g.renderItem(iconStack, x + 1, y + 1);
            textX += 18;
        }
        Component name = provider.group().name();
        g.drawString(font, font.plainSubstrByWidth(name.getString(), Math.max(24, contentWidth() - (textX - x) - 2)),
                textX, y + 5, COLOR_TITLE_TEXT, false);
        if (contains(mouseX, mouseY, x, y, contentWidth(), ROW)) {
            hoveredProviderTooltip = provider.group().tooltip().isEmpty()
                    ? List.of(name)
                    : provider.group().tooltip();
        }
    }

    private void renderProviderSlotRow(GuiGraphics g, Font font, Entry provider,
            int slotRow, int visibleRow, int mouseX, int mouseY) {
        int x = contentLeft();
        int y = contentTop() + visibleRow * ROW;
        for (int col = 0; col < COLUMNS; col++) {
            int providerSlot = slotRow * COLUMNS + col;
            if (providerSlot >= provider.inventorySize()) {
                break;
            }
            int slotX = x + col * SLOT;
            ModulePanel.drawSlot(g, slotX - 1, y - 1);
            ItemStack pattern = provider.slots().get(providerSlot);
            if (pattern != null && !pattern.isEmpty()) {
                ItemStack display = displayPatternStack(pattern);
                g.renderItem(display, slotX, y);
                g.renderItemDecorations(font, display, slotX, y);
                if (contains(mouseX, mouseY, slotX, y, 16, 16)) {
                    hoveredPattern = pattern;
                }
            }
        }
    }

    private ItemStack displayPatternStack(ItemStack pattern) {
        if (pattern.getItem() instanceof appeng.crafting.pattern.EncodedPatternItem encodedPattern) {
            ItemStack out = encodedPattern.getOutput(pattern);
            if (!out.isEmpty()) {
                return out;
            }
        }
        return pattern;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || !contains(mx, my) || scrollY == 0) {
            return false;
        }
        int max = Math.max(0, totalRows() - Math.max(1, visibleRows() - 1));
        if (max <= 0) {
            return false;
        }
        scrollRows = Math.max(0, Math.min(max, scrollRows - (int) Math.signum(scrollY)));
        scrollbar.setScroll(scrollRows);
        return true;
    }

    // --- Scrollbar drag interaction -------------------------------------

    private int scrollbarTrackX() {
        return contentLeft() + contentWidth() - SCROLLBAR_WIDTH;
    }

    private int scrollbarTrackY() {
        return contentTop();
    }

    private int scrollbarTrackH() {
        return contentHeight();
    }

    private int maxScrollRows() {
        return Math.max(0, totalRows() - Math.max(1, visibleRows() - 1));
    }

    private int visibleScrollRows() {
        return Math.max(1, visibleRows() - 1);
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || maxScrollRows() <= 0) {
            return false;
        }
        if (!(mx >= scrollbarTrackX() && mx < scrollbarTrackX() + SCROLLBAR_WIDTH
                && my >= scrollbarTrackY() && my < scrollbarTrackY() + scrollbarTrackH())) {
            return false;
        }
        scrollbar.setScroll(scrollRows);
        boolean consumed = scrollbar.mousePressed(mx, my,
                scrollbarTrackX(), scrollbarTrackY(), SCROLLBAR_WIDTH, scrollbarTrackH(),
                visibleScrollRows(), maxScrollRows());
        if (consumed) {
            scrollRows = scrollbar.scroll();
        }
        return consumed;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbar.isDragging()) {
            return false;
        }
        scrollbar.mouseDragged(my,
                scrollbarTrackY(), scrollbarTrackH(),
                maxScrollRows());
        scrollRows = scrollbar.scroll();
        return true;
    }

    @Override
    public void scrollbarReleased() {
        scrollbar.mouseReleased();
    }

    @Override
    public boolean scrollbarDragging() {
        return scrollbar.isDragging();
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || (button != 0 && button != 1) || !contains(mx, my)) {
            return false;
        }
        SlotHit hit = slotAt(mx, my);
        if (hit != null) {
            var menu = currentMenu();
            if (menu == null || menu.containerId != subscribedContainerId) {
                updateSubscription();
                return true;
            }
            PacketDistributor.sendToServer(new PatternProviderActionPacket(
                    menu.containerId,
                    hit.epoch(),
                    hit.providerId(),
                    hit.revision(),
                    hit.providerSlot(),
                    button == 1
                            ? PatternProviderActionPacket.Action.QUICK_MOVE_TO_PLAYER
                            : PatternProviderActionPacket.Action.PICKUP_OR_SET_DOWN));
            return true;
        }
        requestProviders();
        return false;
    }

    /** Shared render/hit-test row traversal; see {@link PatternAccessRowLayout}. */
    private List<PatternAccessRowLayout.Row> layoutRows(int maxRows) {
        var slotRowCounts = new ArrayList<Integer>(providers.size());
        for (var provider : providers) {
            slotRowCounts.add((provider.inventorySize() + COLUMNS - 1) / COLUMNS);
        }
        return PatternAccessRowLayout.visibleRows(slotRowCounts, scrollRows, maxRows);
    }

    private SlotHit slotAt(double mx, double my) {
        for (PatternAccessRowLayout.Row row : layoutRows(visibleRows())) {
            if (row.isHeader()) {
                continue;
            }
            var provider = providers.get(row.providerIndex());
            int y = contentTop() + row.visibleRow() * ROW;
            for (int col = 0; col < COLUMNS; col++) {
                int providerSlot = row.slotRow() * COLUMNS + col;
                if (providerSlot >= provider.inventorySize()) {
                    break;
                }
                int x = contentLeft() + col * SLOT;
                if (contains(mx, my, x, y, 16, 16)) {
                    return new SlotHit(provider.epoch(), provider.providerId(), provider.revision(), providerSlot);
                }
            }
        }
        return null;
    }

    private int visibleRows() {
        return Math.max(1, contentHeight() / ROW);
    }

    private int totalRows() {
        int rows = 0;
        for (var provider : providers) {
            rows += 1 + (provider.inventorySize() + COLUMNS - 1) / COLUMNS;
        }
        return rows;
    }

    private static boolean contains(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private record SlotHit(long epoch, long providerId, long revision, int providerSlot) {
    }
}
