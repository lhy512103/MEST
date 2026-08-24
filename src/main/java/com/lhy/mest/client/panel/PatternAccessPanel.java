package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.config.ShowPatternProviders;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.GuiText;

import com.lhy.mest.compat.plus.PlusPatternAccess;
import com.glodblock.github.extendedae.client.button.EPPIcon;
import com.glodblock.github.extendedae.client.button.HighlightButtonSmall;
import com.glodblock.github.extendedae.util.MessageUtil;
import com.lhy.mest.client.PinyinSearch;
import com.lhy.mest.network.PatternProviderLoc;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.PatternProviderClientHandler;
import com.lhy.mest.client.PatternProviderClientHandler.Entry;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.panel.PatternAccessRowLayout.ProviderRows;
import com.lhy.mest.network.PatternProviderActionPacket;
import com.lhy.mest.network.PatternProviderListPacket;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Pattern access view laid out like ExtendedAE's pattern terminal: search row under the title,
 * search-mode button, grouped headers, variable-width slot grid, AE2 small scroller.
 */
public class PatternAccessPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int ROW = 18;
    private static final int MIN_COLUMNS = 3;
    private static final int DEFAULT_COLUMNS = 9;
    private static final int SEARCH_ROW = 16;
    private static final int SEARCH_WIDTH = 65;
    private static final int SEARCH_HEIGHT = 10;
    private static final int MODE_BUTTON = 12;
    private static final int RAIL_WIDTH = 20;
    private static final int RAIL_SPRITE_WIDTH = 21;
    private static final int RAIL_OVERLAP = 2;
    private static final int RAIL_SHIFT_X = 2;
    private static final int SCROLLBAR_INSET = 2;
    private static final int TRACK_WIDTH = 12;
    private static final int INSIDE_TRACK_GAP = 6;
    private static final int INSIDE_GUTTER = INSIDE_TRACK_GAP + TRACK_WIDTH;
    private static final ResourceLocation RAIL_SPRITE = ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "vertical_buttons_bg");
    private static final int MATCH_HIGHLIGHT = 0x66ACE9FF;
    private static final int UNMATCHED_DIM = 0x6A000000;
    private static final int SLOT_HOVER_FILL = 0x669CD3FF;
    private static final int SLOT_HOVER_BORDER = 0xFFDAFFFF;
    private static final int WELL_FILL = 0xFF9A9FB4;
    private static final int HEADER_FILL = 0xFF9A9FB4;
    private static final int SLOT_FILL = 0xFFADB0C4;
    private static final int SLOT_BORDER = 0xFF878FA5;

    private final ScreenStyle style;
    private final List<Entry> providers = new ArrayList<>();
    private final Scrollbar scrollbar = new Scrollbar(Scrollbar.BIG);
    private static SearchMode rememberedSearchMode = SearchMode.OUT;
    private static ShowPatternProviders rememberedShowMode = ShowPatternProviders.VISIBLE;
    private static boolean rememberedShowSlots = true;

    private SearchMode searchMode = rememberedSearchMode;
    private ShowPatternProviders showMode = rememberedShowMode;
    private boolean showSlots = rememberedShowSlots;
    private final CompactPatButton searchModeButton = new CompactPatButton(
            btn -> cycleSearchMode(), this::searchModeIcon, this::searchModeTooltip);
    private final CompactPatButton showModeButton = new CompactPatButton(
            btn -> cycleShowMode(), this::showModeIcon, this::showModeTooltip);
    private final CompactPatButton hideSlotsButton = new CompactPatButton(
            btn -> toggleShowSlots(), this::hideSlotsIcon, this::hideSlotsTooltip);
    private final java.util.HashMap<Long, HighlightButtonSmall> highlightButtons = new java.util.HashMap<>();
    private final java.util.HashMap<Long, Button> openUiButtons = new java.util.HashMap<>();
    private int scrollRows;
    private SlotHit lastShiftExtract;
    private int subscribedContainerId = -1;
    private MESTMenu subscribedMenu;
    private ItemStack hoveredPattern = ItemStack.EMPTY;
    private List<Component> hoveredProviderTooltip = List.of();
    private AETextField searchField;
    private String search = "";
    private boolean scrollbarDragging;

    public PatternAccessPanel(ScreenStyle style) {
        this.style = style;
        this.scrollbar.setCaptureMouseWheel(false);
        applyRememberedButtons();
    }

    public void applyRememberedButtons() {
        searchMode = rememberedSearchMode;
        showMode = rememberedShowMode;
        showSlots = rememberedShowSlots;
    }

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
        return chromeWidth(DEFAULT_COLUMNS) + preferredContentRightInset();
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + SEARCH_ROW + CONTENT_PADDING + 6 * ROW;
    }

    @Override
    public int minWidth() {
        return chromeWidth(MIN_COLUMNS) + preferredContentRightInset();
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + SEARCH_ROW + CONTENT_PADDING + 4 * ROW;
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    @Override
    public int outsideHitWidth() {
        if (!scrollerOutside()) {
            return 0;
        }
        return Math.max(0, railLeft() - 2 + RAIL_SPRITE_WIDTH - (x + width));
    }

    @Override
    public int preferredContentRightInset() {
        return visible && !scrollerOutside() ? INSIDE_GUTTER : 0;
    }

    @Override
    public int contentTop() {
        return super.contentTop() + SEARCH_ROW;
    }

    @Override
    public int contentHeight() {
        return Math.max(0, super.contentHeight() - SEARCH_ROW);
    }

    public void tick() {
        updateSubscription();
        layoutSearch();
        layoutScrollbar();
    }

    public void setProviders(List<Entry> entries) {
        if (subscribedContainerId < 0) {
            return;
        }
        providers.clear();
        providers.addAll(entries);
        clampScroll();
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
        sendSubscription(menu.containerId, true);
    }

    @Override
    public void layoutSlots() {
        updateSubscription();
        layoutSearch();
        layoutScrollbar();
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
            sendSubscription(menu.containerId, true);
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
            sendSubscription(subscribedContainerId, false);
        }
        if (subscribedMenu != null) {
            PatternProviderClientHandler.endSubscription(subscribedMenu);
        }
        subscribedContainerId = -1;
        subscribedMenu = null;
        providers.clear();
        scrollRows = 0;
        scrollbar.setCurrentScroll(0);
    }

    private static MESTMenu currentMenu() {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof MESTMenu menu ? menu : null;
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        hoveredPattern = ItemStack.EMPTY;
        hoveredProviderTooltip = List.of();
        ensureSearch(font);
        layoutSearch();
        layoutScrollbar();
        if (searchField != null) {
            searchField.render(g, mouseX, mouseY, partialTicks);
        }
        searchModeButton.render(g, mouseX, mouseY, partialTicks);
        showModeButton.render(g, mouseX, mouseY, partialTicks);
        hideSlotsButton.render(g, mouseX, mouseY, partialTicks);

        int maxRows = visibleRows();
        drawWell(g, maxRows);
        if (filteredProviders().isEmpty()) {
            hideProviderActionButtons();
            g.drawString(font, Component.translatable("gui.mesplicedterminal.pattern_access.empty"),
                    gridLeft() + 4, contentTop() + 2, COLOR_TITLE_TEXT, false);
            drawScrollerRail(g);
            g.flush();
            drawScrollerTrack(g);
            scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
            return;
        }

        List<PatternAccessRowLayout.Row> rows = layoutRows(maxRows);
        var groupSizes = groupSizes();
        hideProviderActionButtons();
        for (PatternAccessRowLayout.Row row : rows) {
            var provider = provider(row.providerIndex());
            if (row.isHeader()) {
                renderProviderHeader(g, font, provider, row.visibleRow(), groupSizes.getOrDefault(provider.group(), 1),
                        mouseX, mouseY);
            } else {
                renderProviderSlotRow(g, font, provider, row.slotRow(), row.visibleRow(), mouseX, mouseY);
            }
        }
        layoutProviderActionButtons(g, font, rows, mouseX, mouseY, partialTicks);
        drawScrollerRail(g);
        g.flush();
        drawScrollerTrack(g);
        scrollbar.drawForegroundLayer(g, new Rect2i(0, 0, 0, 0), new Point(mouseX, mouseY));
    }

    private void drawWell(GuiGraphics g, int maxRows) {
        int x = contentLeft();
        int y = contentTop() - 1;
        int w = gridWidth() + 2;
        int h = Math.max(ROW, maxRows * ROW) + 2;
        g.fill(x, y, x + w, y + h, WELL_FILL);
        g.fill(x, y, x + w, y + 1, COLOR_LIGHT);
        g.fill(x, y + h - 1, x + w, y + h, COLOR_LIGHT);
        g.fill(x, y, x + 1, y + h, COLOR_LIGHT);
        g.fill(x + w - 1, y, x + w, y + h, COLOR_LIGHT);
    }

    @Override
    public void renderForegroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        CompactPatButton hoveredButton = hoveredToolbarButton(mouseX, mouseY);
        if (hoveredButton != null && !hoveredButton.getTooltipMessage().isEmpty()) {
            g.renderComponentTooltip(font, hoveredButton.getTooltipMessage(), mouseX, mouseY);
            return;
        }
        for (Button openUi : openUiButtons.values()) {
            if (openUi.visible && openUi.isMouseOver(mouseX, mouseY)) {
                g.renderComponentTooltip(font,
                        List.of(Component.translatable("extendedae_plus.tooltip.provider.open_ui")),
                        mouseX, mouseY);
                return;
            }
        }
        for (HighlightButtonSmall highlight : highlightButtons.values()) {
            if (highlight.visible && highlight.isMouseOver(mouseX, mouseY)
                    && !highlight.getTooltipMessage().isEmpty()) {
                g.renderComponentTooltip(font, highlight.getTooltipMessage(), mouseX, mouseY);
                return;
            }
        }
        if (!hoveredPattern.isEmpty()) {
            g.renderTooltip(font, hoveredPattern, mouseX, mouseY);
        } else if (!hoveredProviderTooltip.isEmpty()) {
            g.renderComponentTooltip(font, hoveredProviderTooltip, mouseX, mouseY);
        }
    }

    private void renderProviderHeader(GuiGraphics g, Font font, Entry provider,
            int row, int groupSize, int mouseX, int mouseY) {
        int x = gridLeft();
        int y = contentTop() + row * ROW;
        int headerWidth = gridWidth();
        g.fill(x + 1, y, x + headerWidth - 1, y + ROW, HEADER_FILL);
        AEItemKey icon = provider.group().icon();
        if (icon != null) {
            g.pose().pushPose();
            g.pose().translate(x + 2, y + 5, 0);
            g.pose().scale(0.5F, 0.5F, 1.0F);
            g.renderItem(icon.getReadOnlyStack(), 0, 0);
            g.pose().popPose();
        }
        Component displayName = groupSize > 1
                ? Component.empty().append(provider.group().name()).append(Component.literal(" (" + groupSize + ")"))
                : provider.group().name();
        int textWidth = Math.max(24, headerWidth - 16);
        FormattedCharSequence text = Language.getInstance().getVisualOrder(font.substrByWidth(displayName, textWidth));
        g.drawString(font, text, x + 12, y + 6, COLOR_TITLE_TEXT, false);
        if (contains(mouseX, mouseY, x, y, headerWidth, ROW)) {
            hoveredProviderTooltip = provider.group().tooltip().isEmpty()
                    ? List.of(provider.group().name())
                    : provider.group().tooltip();
        }
    }

    private void renderProviderSlotRow(GuiGraphics g, Font font, Entry provider,
            int slotRow, int visibleRow, int mouseX, int mouseY) {
        int y = contentTop() + visibleRow * ROW;
        int columns = columns();
        for (int col = 0; col < columns; col++) {
            int providerSlot = slotRow * columns + col;
            if (providerSlot >= provider.inventorySize()) {
                break;
            }
            int slotX = gridLeft() + col * SLOT;
            int iconX = slotX + 1;
            drawSlotCell(g, slotX, y, col, columns);
            ItemStack pattern = provider.slots().get(providerSlot);
            boolean hovering = contains(mouseX, mouseY, iconX, y + 1, 16, 16);
            if (pattern != null && !pattern.isEmpty()) {
                ItemStack display = displayPatternStack(pattern);
                if (display != pattern) {
                    display = display.copy();
                    display.setCount(1);
                }
                g.renderItem(display, iconX, y + 1);
                g.renderItemDecorations(font, display, iconX, y + 1);
                String amountText = patternAmountText(pattern);
                if (!amountText.isEmpty()) {
                    StackSizeRenderer.renderSizeLabel(g, font, iconX, y + 1, amountText, false);
                }
                if (!search.isBlank()) {
                    if (matchesSearch(pattern)) {
                        if (ModList.get().isLoaded("extendedae_plus")) {
                            PlusPatternAccess.drawSlotRainbowHighlight(g, iconX, y + 1);
                        } else {
                            g.fill(iconX - 1, y, iconX + 17, y + 18, MATCH_HIGHLIGHT);
                        }
                    } else {
                        g.fill(iconX, y + 1, iconX + 16, y + 17, UNMATCHED_DIM);
                    }
                }
                if (hovering) {
                    hoveredPattern = pattern;
                }
            }
            if (hovering) {
                int ix = iconX;
                int iy = y + 1;
                g.hLine(ix, ix + 16, iy - 1, SLOT_HOVER_BORDER);
                g.hLine(ix - 1, ix + 16, iy + 16, SLOT_HOVER_BORDER);
                g.vLine(ix - 1, iy - 2, iy + 16, SLOT_HOVER_BORDER);
                g.vLine(ix + 16, iy - 2, iy + 16, SLOT_HOVER_BORDER);
                g.fillGradient(RenderType.guiOverlay(), ix, iy, ix + 16, iy + 16, SLOT_HOVER_FILL, SLOT_HOVER_FILL, 0);
            }
        }
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

    private ItemStack displayPatternStack(ItemStack pattern) {
        if (pattern.getItem() instanceof appeng.crafting.pattern.EncodedPatternItem encodedPattern) {
            ItemStack out = encodedPattern.getOutput(pattern);
            if (!out.isEmpty()) {
                return out;
            }
        }
        return pattern;
    }

    private String patternAmountText(ItemStack pattern) {
        if (ModList.get().isLoaded("extendedae_plus")) {
            return PlusPatternAccess.patternOutputText(pattern);
        }
        var details = PatternDetailsHelper.decodePattern(pattern, Minecraft.getInstance().level);
        if (details == null || details.getOutputs().isEmpty()) {
            return "";
        }
        long amount = details.getOutputs().getFirst().amount();
        return amount > 1 ? String.valueOf(amount) : "";
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || (!contains(mx, my) && !inScroller(mx, my))) {
            return false;
        }
        layoutScrollbar();
        if (maxScrollRows() <= 0) {
            return false;
        }
        scrollbar.setCurrentScroll(scrollbar.getCurrentScroll() - (int) Math.signum(scrollY));
        scrollRows = scrollbar.getCurrentScroll();
        return true;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || !inScroller(mx, my)) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDown(new Point((int) mx, (int) my), 0);
        scrollbarDragging = consumed;
        scrollRows = scrollbar.getCurrentScroll();
        return consumed;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbarDragging) {
            return false;
        }
        boolean consumed = scrollbar.onMouseDrag(new Point((int) mx, (int) my), 0);
        scrollRows = scrollbar.getCurrentScroll();
        return consumed;
    }

    @Override
    public void scrollbarReleased() {
        if (scrollbarDragging) {
            scrollbar.onMouseUp(Point.ZERO, 0);
        }
        scrollbarDragging = false;
        scrollRows = scrollbar.getCurrentScroll();
    }

    @Override
    public boolean scrollbarDragging() {
        return scrollbarDragging;
    }

    @Override
    public boolean inTitleBarControls(double mx, double my) {
        return inPinButton(mx, my)
                || (searchField != null && searchField.visible && searchField.isMouseOver(mx, my))
                || (searchModeButton.visible && searchModeButton.isMouseOver(mx, my))
                || (showModeButton.visible && showModeButton.isMouseOver(mx, my))
                || (hideSlotsButton.visible && hideSlotsButton.isMouseOver(mx, my));
    }

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
        if (inScroller(mx, my)) {
            return false;
        }
        if (searchModeButton.mouseClicked(mx, my, button)
                || showModeButton.mouseClicked(mx, my, button)
                || hideSlotsButton.mouseClicked(mx, my, button)) {
            return true;
        }
        for (HighlightButtonSmall highlight : highlightButtons.values()) {
            if (highlight.visible && highlight.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        for (Button openUi : openUiButtons.values()) {
            if (openUi.visible && openUi.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (searchField != null && searchField.mouseClicked(mx, my, button)) {
            setSearchFocused(true);
            return true;
        }
        if (searchField != null) {
            searchField.setFocused(false);
        }
        if (button != 0 && button != 1) {
            return true;
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
                    Screen.hasShiftDown()
                            ? PatternProviderActionPacket.Action.QUICK_MOVE_TO_PLAYER
                            : PatternProviderActionPacket.Action.PICKUP_OR_SET_DOWN));
            return true;
        }
        return my >= y + TITLE_BAR_HEIGHT;
    }

    public boolean dropHovered(double mx, double my, boolean wholeStack) {
        SlotHit hit = slotAt(mx, my);
        if (hit == null || !slotFilled(hit)) {
            return false;
        }
        sendSlotAction(hit, wholeStack
                ? PatternProviderActionPacket.Action.DROP_STACK
                : PatternProviderActionPacket.Action.DROP);
        return true;
    }

    public void shiftHoverExtract(double mx, double my) {
        if (!Screen.hasShiftDown()) {
            lastShiftExtract = null;
            return;
        }
        var menu = currentMenu();
        if (menu == null || !menu.getCarried().isEmpty()) {
            return;
        }
        SlotHit hit = slotAt(mx, my);
        if (hit == null || !slotFilled(hit) || hit.equals(lastShiftExtract)) {
            return;
        }
        lastShiftExtract = hit;
        sendSlotAction(hit, PatternProviderActionPacket.Action.QUICK_MOVE_TO_PLAYER);
    }

    private boolean slotFilled(SlotHit hit) {
        for (Entry entry : providers) {
            if (entry.providerId() != hit.providerId() || hit.providerSlot() >= entry.inventorySize()) {
                continue;
            }
            ItemStack stack = entry.slots().get(hit.providerSlot());
            return stack != null && !stack.isEmpty();
        }
        return false;
    }

    private void sendSlotAction(SlotHit hit, PatternProviderActionPacket.Action action) {
        var menu = currentMenu();
        if (menu == null || menu.containerId != subscribedContainerId) {
            updateSubscription();
            return;
        }
        PacketDistributor.sendToServer(new PatternProviderActionPacket(
                menu.containerId, hit.epoch(), hit.providerId(), hit.revision(), hit.providerSlot(), action));
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
        if (Minecraft.getInstance().screen instanceof com.lhy.mest.client.MESTScreen screen) {
            if (focused) {
                screen.setFocused(searchField);
            } else if (screen.getFocused() == searchField) {
                screen.setFocused(null);
            }
        }
    }

    public ITooltip hoveredTooltip(int mouseX, int mouseY) {
        return hoveredToolbarButton(mouseX, mouseY);
    }

    private List<PatternAccessRowLayout.Row> layoutRows(int maxRows) {
        return PatternAccessRowLayout.visibleRowsFromSpecs(groupedSpecs(), scrollRows, maxRows);
    }

    private List<ProviderRows> groupedSpecs() {
        var filtered = filteredProviders();
        var byGroup = new LinkedHashMap<PatternContainerGroup, List<Integer>>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparing((Integer i) -> filtered.get(i).group().name().getString().toLowerCase(Locale.ROOT))
                .thenComparingLong(i -> filtered.get(i).sortOrder())
                .thenComparingLong(i -> filtered.get(i).providerId()));
        for (int i : order) {
            byGroup.computeIfAbsent(filtered.get(i).group(), unused -> new ArrayList<>()).add(i);
        }
        int columns = columns();
        var specs = new ArrayList<ProviderRows>();
        for (var indexes : byGroup.values()) {
            boolean header = true;
            for (int filteredIndex : indexes) {
                Entry entry = filtered.get(filteredIndex);
                int original = providers.indexOf(entry);
                int slotRows = showSlots ? (entry.inventorySize() + columns - 1) / columns : 0;
                specs.add(new ProviderRows(original, slotRows, header));
                header = false;
            }
        }
        return specs;
    }

    private LinkedHashMap<PatternContainerGroup, Integer> groupSizes() {
        var sizes = new LinkedHashMap<PatternContainerGroup, Integer>();
        for (Entry entry : filteredProviders()) {
            sizes.merge(entry.group(), 1, Integer::sum);
        }
        return sizes;
    }

    private List<Entry> filteredProviders() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        var result = new ArrayList<Entry>();
        for (Entry entry : providers) {
            if (showMode == ShowPatternProviders.NOT_FULL && providerFull(entry)) {
                continue;
            }
            if (!needle.isEmpty()
                    && !PinyinSearch.contains(entry.group().name().getString(), needle)
                    && !matchesAnyPattern(entry, needle)) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private static boolean providerFull(Entry entry) {
        for (int i = 0; i < entry.inventorySize(); i++) {
            ItemStack stack = entry.slots().get(i);
            if (stack == null || stack.isEmpty()) {
                return false;
            }
        }
        return entry.inventorySize() > 0;
    }

    private boolean matchesAnyPattern(Entry entry, String needle) {
        for (ItemStack stack : entry.slots().values()) {
            if (stack != null && matchesSearch(stack, needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSearch(ItemStack pattern) {
        return matchesSearch(pattern, search.trim().toLowerCase(Locale.ROOT));
    }

    private boolean matchesSearch(ItemStack pattern, String needle) {
        if (needle.isEmpty() || pattern == null || pattern.isEmpty()) {
            return false;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, Minecraft.getInstance().level);
        if (details == null) {
            return containsIgnoreCase(pattern.getHoverName(), needle)
                    || containsIgnoreCase(displayPatternStack(pattern).getHoverName(), needle);
        }
        if (searchMode.isOut()) {
            for (var output : details.getOutputs()) {
                if (containsIgnoreCase(output.what().getDisplayName(), needle)) {
                    return true;
                }
            }
        }
        if (searchMode.isIn()) {
            for (var input : details.getInputs()) {
                for (var stack : input.getPossibleInputs()) {
                    if (containsIgnoreCase(stack.what().getDisplayName(), needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(Component name, String needle) {
        return PinyinSearch.contains(name.getString(), needle);
    }

    private Entry provider(int index) {
        return providers.get(index);
    }

    private SlotHit slotAt(double mx, double my) {
        int columns = columns();
        for (PatternAccessRowLayout.Row row : layoutRows(visibleRows())) {
            if (row.isHeader()) {
                continue;
            }
            var provider = provider(row.providerIndex());
            int y = contentTop() + row.visibleRow() * ROW;
            for (int col = 0; col < columns; col++) {
                int providerSlot = row.slotRow() * columns + col;
                if (providerSlot >= provider.inventorySize()) {
                    break;
                }
                int x = gridLeft() + col * SLOT;
                if (contains(mx, my, x + 1, y + 1, 16, 16)) {
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
        for (ProviderRows spec : groupedSpecs()) {
            if (spec.header()) {
                rows++;
            }
            rows += spec.slotRows();
        }
        return rows;
    }

    private int columns() {
        return Math.max(MIN_COLUMNS, contentWidth() / SLOT);
    }

    private int gridLeft() {
        return contentLeft() + 1;
    }

    private int gridWidth() {
        return columns() * SLOT;
    }

    private static int chromeWidth(int columns) {
        return 2 * CONTENT_PADDING + columns * SLOT;
    }

    private int maxScrollRows() {
        return Math.max(0, totalRows() - visibleRows());
    }

    private void clampScroll() {
        layoutScrollbar();
        scrollRows = scrollbar.getCurrentScroll();
    }

    private void layoutScrollbar() {
        int max = maxScrollRows();
        scrollbar.setRange(0, max, Math.max(1, visibleRows() / 6));
        scrollbar.setHeight(Math.max(1, visibleRows() * ROW - 2));
        scrollbar.setPosition(new Point(trackLeft(), contentTop() + 1));
        scrollbar.setCurrentScroll(Math.min(scrollRows, max));
        scrollRows = scrollbar.getCurrentScroll();
    }

    private boolean scrollerOutside() {
        return visible && rightmostInWindow;
    }

    private int railLeft() {
        if (scrollerOutside()) {
            return x + width - RAIL_OVERLAP + RAIL_SHIFT_X;
        }
        return trackLeft() - SCROLLBAR_INSET;
    }

    private int trackLeft() {
        if (scrollerOutside()) {
            return railLeft() + SCROLLBAR_INSET;
        }
        return gridLeft() + gridWidth() + INSIDE_TRACK_GAP;
    }

    private void drawScrollerRail(GuiGraphics g) {
        if (!scrollerOutside() || !drawOutsideRail) {
            return;
        }
        g.blitSprite(RAIL_SPRITE, railLeft() - 2, joinedRailY, RAIL_SPRITE_WIDTH, joinedRailH);
    }

    private void drawScrollerTrack(GuiGraphics g) {
        com.lhy.mest.client.dock.Scrollbar.drawTerminalTrack(
                g, trackLeft(), contentTop() + 1, Math.max(1, visibleRows() * ROW - 2));
    }

    private void ensureSearch(Font font) {
        if (searchField != null) {
            return;
        }
        searchField = new AETextField(style, font, 0, 0, SEARCH_WIDTH, SEARCH_HEIGHT);
        searchField.setBordered(false);
        searchField.setMaxLength(64);
        searchField.setResponder(value -> search = value);
        searchField.setPlaceholder(GuiText.SearchPlaceholder.text());
        if (Minecraft.getInstance().screen instanceof com.lhy.mest.client.MESTScreen screen) {
            screen.attachPatternSearchField(searchField);
        }
    }

    public void rebindSearchField(com.lhy.mest.client.MESTScreen screen) {
        if (searchField != null) {
            screen.attachPatternSearchField(searchField);
        }
    }

    public AETextField searchField() {
        return searchField;
    }

    private void layoutSearch() {
        boolean show = visible && drawsTitleBar();
        int searchY = y + TITLE_BAR_HEIGHT + 1;
        int searchX = contentLeft();
        if (searchField != null) {
            searchField.setVisible(show);
            if (show) {
                searchField.resize(SEARCH_WIDTH, SEARCH_HEIGHT);
                searchField.move(new Point(searchX, searchY));
            }
        }
        int buttonY = y + TITLE_BAR_HEIGHT + 1;
        int buttonX = searchX + SEARCH_WIDTH + 1;
        placeToolbarButton(searchModeButton, buttonX, buttonY, show);
        placeToolbarButton(showModeButton, buttonX + MODE_BUTTON + 1, buttonY, show);
        placeToolbarButton(hideSlotsButton, buttonX + 2 * (MODE_BUTTON + 1), buttonY, show);
    }

    private static void placeToolbarButton(CompactPatButton button, int x, int y, boolean show) {
        button.visible = show;
        button.active = show;
        if (show) {
            button.setX(x);
            button.setY(y);
        }
    }

    private void cycleSearchMode() {
        searchMode = searchMode.next();
        persistButtons();
    }

    private void cycleShowMode() {
        ShowPatternProviders[] values = ShowPatternProviders.values();
        showMode = values[(showMode.ordinal() + 1) % values.length];
        persistButtons();
        var menu = currentMenu();
        if (menu != null && visible) {
            sendSubscription(menu.containerId, true);
        }
    }

    private void toggleShowSlots() {
        showSlots = !showSlots;
        persistButtons();
    }

    private void persistButtons() {
        rememberedSearchMode = searchMode;
        rememberedShowMode = showMode;
        rememberedShowSlots = showSlots;
        if (Minecraft.getInstance().screen instanceof com.lhy.mest.client.MESTScreen screen) {
            screen.saveUiPreferences();
        }
    }

    public static void readPreferences(JsonObject object) {
        if (object.has("patternAccessSearchMode")) {
            try {
                rememberedSearchMode = SearchMode.valueOf(object.get("patternAccessSearchMode").getAsString());
            } catch (RuntimeException ignored) {
            }
        }
        if (object.has("patternAccessShowMode")) {
            try {
                rememberedShowMode = ShowPatternProviders.valueOf(object.get("patternAccessShowMode").getAsString());
            } catch (RuntimeException ignored) {
            }
        }
        if (object.has("patternAccessShowSlots")) {
            rememberedShowSlots = object.get("patternAccessShowSlots").getAsBoolean();
        }
    }

    public static void writePreferences(JsonObject object) {
        object.addProperty("patternAccessSearchMode", rememberedSearchMode.name());
        object.addProperty("patternAccessShowMode", rememberedShowMode.name());
        object.addProperty("patternAccessShowSlots", rememberedShowSlots);
    }

    private void sendSubscription(int containerId, boolean subscribe) {
        PacketDistributor.sendToServer(new PatternProviderListPacket.Request(
                containerId, subscribe, (byte) showMode.ordinal()));
    }

    private boolean inScroller(double mx, double my) {
        layoutScrollbar();
        Rect2i bounds = scrollbar.getBounds();
        if (mx >= bounds.getX() && mx < bounds.getX() + bounds.getWidth()
                && my >= bounds.getY() && my < bounds.getY() + bounds.getHeight()) {
            return true;
        }
        if (!scrollerOutside()) {
            return false;
        }
        int left = railLeft() - 2;
        return mx >= left && mx < left + RAIL_SPRITE_WIDTH && my >= y && my < y + height;
    }

    public Entry firstDisplayedProvider() {
        List<PatternAccessRowLayout.Row> rows = layoutRows(visibleRows());
        for (PatternAccessRowLayout.Row row : rows) {
            Entry entry = provider(row.providerIndex());
            if (!providerFull(entry)) {
                return entry;
            }
        }
        return rows.isEmpty() ? null : provider(rows.getFirst().providerIndex());
    }

    private Blitter searchModeIcon() {
        return switch (searchMode) {
            case IN -> EPPIcon.SEARCH_INPUT;
            case OUT -> EPPIcon.SEARCH_OUTPUT;
            case IN_OUT -> EPPIcon.SEARCH_IO;
        };
    }

    private List<Component> searchModeTooltip() {
        return List.of(
                Component.translatable("gui.mesplicedterminal.pattern_access.search_mode"),
                searchMode.label().copy().withStyle(ChatFormatting.GRAY));
    }

    private Blitter showModeIcon() {
        Icon icon = switch (showMode) {
            case VISIBLE -> Icon.PATTERN_TERMINAL_VISIBLE;
            case NOT_FULL -> Icon.PATTERN_TERMINAL_NOT_FULL;
            case ALL -> Icon.PATTERN_TERMINAL_ALL;
        };
        return icon.getBlitter();
    }

    private List<Component> showModeTooltip() {
        Component detail = switch (showMode) {
            case VISIBLE -> ButtonToolTips.ShowVisibleProviders.text();
            case NOT_FULL -> ButtonToolTips.ShowNonFullProviders.text();
            case ALL -> ButtonToolTips.ShowAllProviders.text();
        };
        return List.of(
                ButtonToolTips.InterfaceTerminalDisplayMode.text(),
                detail.copy().withStyle(ChatFormatting.GRAY));
    }

    private static Blitter openUiIcon() {
        return Blitter.texture(ResourceLocation.fromNamespaceAndPath(
                        MESplicedterminal.MODID, "textures/guis/pattern_cache_states.png"), 256, 256)
                .src(48, 0, 16, 16);
    }

    private static List<Component> openUiTooltip() {
        return List.of(Component.translatable("extendedae_plus.tooltip.provider.open_ui"));
    }

    private Blitter hideSlotsIcon() {
        return (showSlots ? Icon.PATTERN_ACCESS_HIDE : Icon.PATTERN_ACCESS_SHOW).getBlitter();
    }

    private List<Component> hideSlotsTooltip() {
        return List.of(Component.translatable(showSlots
                ? "gui.expatternprovider.hide_slots"
                : "gui.expatternprovider.show_slots"));
    }

    private void hideProviderActionButtons() {
        for (HighlightButtonSmall button : highlightButtons.values()) {
            button.visible = false;
        }
        for (Button button : openUiButtons.values()) {
            button.visible = false;
        }
    }

    private void layoutProviderActionButtons(GuiGraphics g, Font font, List<PatternAccessRowLayout.Row> rows,
            int mouseX, int mouseY, float partialTicks) {
        var player = Minecraft.getInstance().player;
        for (PatternAccessRowLayout.Row row : rows) {
            if (!row.isHeader()) {
                continue;
            }
            Entry provider = provider(row.providerIndex());
            PatternProviderLoc loc = provider.loc();
            int headerY = contentTop() + row.visibleRow() * ROW;
            int uiX = gridLeft() + gridWidth() - 16;
            Button openUi = openUiButtons.computeIfAbsent(provider.providerId(), id -> new CompactPatButton(
                    ignored -> openProviderUi(id),
                    PatternAccessPanel::openUiIcon,
                    PatternAccessPanel::openUiTooltip));
            openUi.visible = loc != null;
            openUi.active = openUi.visible;
            if (openUi.visible) {
                openUi.setX(uiX);
                openUi.setY(headerY + 3);
                openUi.render(g, mouseX, mouseY, partialTicks);
            }

            HighlightButtonSmall highlight = highlightButtons.computeIfAbsent(
                    provider.providerId(), id -> {
                        var button = new HighlightButtonSmall();
                        button.setMessage(Component.translatable(
                                "gui.extendedae.ex_pattern_access_terminal.tooltip.03"));
                        return button;
                    });
            highlight.visible = loc != null;
            if (loc != null) {
                highlight.setTarget(loc.pos(), loc.face(), loc.dimension());
                if (player != null && player.level().dimension().equals(loc.dimension())) {
                    highlight.setMultiplier(Math.sqrt(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(loc.pos()))));
                } else {
                    highlight.setMultiplier(1);
                }
            }
            int pinX = gridLeft() + gridWidth() + 1;
            int pinY = headerY + 4;
            for (PatternAccessRowLayout.Row candidate : rows) {
                if (candidate.providerIndex() == row.providerIndex() && !candidate.isHeader()) {
                    pinY = contentTop() + candidate.visibleRow() * ROW + 4;
                    break;
                }
            }
            highlight.setX(pinX);
            highlight.setY(pinY);
            highlight.setSuccessJob(() -> {
                var viewer = Minecraft.getInstance().player;
                if (viewer == null || loc == null) {
                    return;
                }
                viewer.displayClientMessage(MessageUtil.createEnhancedHighlightMessage(
                        viewer, loc.pos(), loc.dimension(), "chat.ex_pattern_access_terminal.pos"), false);
            });
            if (highlight.visible) {
                highlight.render(g, mouseX, mouseY, partialTicks);
            }
        }
    }

    private void openProviderUi(long providerId) {
        var menu = currentMenu();
        if (menu == null) {
            return;
        }
        for (Entry entry : providers) {
            if (entry.providerId() == providerId) {
                PacketDistributor.sendToServer(new PatternProviderActionPacket(
                        menu.containerId,
                        entry.epoch(),
                        entry.providerId(),
                        entry.revision(),
                        0,
                        PatternProviderActionPacket.Action.OPEN_PROVIDER_UI));
                return;
            }
        }
    }

    private CompactPatButton hoveredToolbarButton(int mouseX, int mouseY) {
        if (searchModeButton.visible && searchModeButton.isMouseOver(mouseX, mouseY)) {
            return searchModeButton;
        }
        if (showModeButton.visible && showModeButton.isMouseOver(mouseX, mouseY)) {
            return showModeButton;
        }
        if (hideSlotsButton.visible && hideSlotsButton.isMouseOver(mouseX, mouseY)) {
            return hideSlotsButton;
        }
        return null;
    }

    private static boolean contains(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private enum SearchMode {
        OUT, IN, IN_OUT;

        boolean isOut() {
            return this != IN;
        }

        boolean isIn() {
            return this != OUT;
        }

        SearchMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        Component label() {
            return Component.translatable("gui.mesplicedterminal.pattern_access.search_mode."
                    + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final class CompactPatButton extends Button implements ITooltip {
        private final java.util.function.Supplier<Blitter> icon;
        private final java.util.function.Supplier<List<Component>> tooltip;

        private CompactPatButton(
                OnPress onPress,
                java.util.function.Supplier<Blitter> icon,
                java.util.function.Supplier<List<Component>> tooltip) {
            super(0, 0, MODE_BUTTON, MODE_BUTTON, Component.empty(), onPress, Button.DEFAULT_NARRATION);
            this.icon = icon;
            this.tooltip = tooltip;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible) {
                return;
            }
            int yOffset = isHovered() ? 1 : 0;
            Blitter background = isHovered()
                    ? EPPIcon.TERMINAL_BUTTON_HOVER
                    : (isFocused() ? EPPIcon.TERMINAL_BUTTON_FOCUS : EPPIcon.TERMINAL_BUTTON);
            background.dest(getX(), getY() + yOffset, MODE_BUTTON, MODE_BUTTON).zOffset(2).blit(graphics);
            icon.get().dest(getX(), getY() + yOffset, MODE_BUTTON, MODE_BUTTON).zOffset(3).blit(graphics);
        }

        @Override
        public List<Component> getTooltipMessage() {
            return tooltip.get();
        }

        @Override
        public Rect2i getTooltipArea() {
            return new Rect2i(getX(), getY(), width, height);
        }

        @Override
        public boolean isTooltipAreaVisible() {
            return visible;
        }
    }

    private record SlotHit(long epoch, long providerId, long revision, int providerSlot) {
    }
}
