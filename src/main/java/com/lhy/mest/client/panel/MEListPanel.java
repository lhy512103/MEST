package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.api.client.AEKeyRendering;
import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AmountFormat;
import appeng.api.util.IConfigManager;
import appeng.client.gui.me.common.Repo;
import appeng.client.gui.me.common.RepoSlot;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.widgets.IScrollSource;
import appeng.client.gui.widgets.ISortSource;
import appeng.menu.me.common.GridInventoryEntry;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.Scrollbar;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Floating panel that shows the ME network inventory. It reuses AE2's {@link Repo} (incremental
 * client-side snapshot + search/sort) and {@link RepoSlot}s, but lays them out in this panel's
 * resizable content area and self-manages scrolling, so it is fully decoupled from AE2's fixed
 * {@code MEStorageScreen} layout.
 *
 * <p>Slot <em>rendering</em> is delegated by the hosting screen (see the screen's {@code renderSlot}
 * override calling {@link #renderRepoSlot}), and slot <em>clicks</em> route through
 * {@link MESTMenu#handleInteraction} via the screen.
 */
public class MEListPanel extends ModulePanel implements ISortSource, IScrollSource {
    private static final int SLOT = 18;
    /** Deduplicates renderRepoSlot failure logging so a broken key renderer cannot spam every frame. */
    private static final Set<String> REPORTED_RENDER_FAILURES = new HashSet<>();

    private final MESTMenu menu;
    private final IConfigManager configSrc;
    private final Repo repo;
    private final List<RepoSlot> repoSlots = new ArrayList<>();

    private int cols = 9;
    private int rows = 4;
    private int scrollOffset; // current scroll, in rows

    private final Scrollbar scrollbar = new Scrollbar();

    private String searchText = "";
    private boolean searchFocused;
    /** Cursor/selection offsets are UTF-16 indices, matching Java's String API. */
    private int cursorPosition;
    private int selectionAnchor = -1;

    private static final int SETTING_BUTTON_WIDTH = 18;
    private static final int SETTING_BUTTON_COUNT = 3;
    private static final int TITLE_CONTROLS_INSET = SETTING_BUTTON_WIDTH * SETTING_BUTTON_COUNT + 4;

    public MEListPanel(MESTMenu menu) {
        this.menu = menu;
        this.configSrc = menu.getConfigManager();
        this.repo = new Repo(this, this);
        menu.setClientRepo(this.repo);
        this.repo.setRowSize(cols);
    }

    public Repo repo() {
        return repo;
    }

    @Override
    public String id() {
        return "me_list";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.ae2.WirelessTerminal");
    }

    @Override
    public int defaultWidth() {
        return 2 * CONTENT_PADDING + 9 * SLOT;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + SEARCH_HEIGHT + 4 * SLOT;
    }

    @Override
    protected int titleRightInset() {
        return TITLE_CONTROLS_INSET;
    }

    private static final int SEARCH_HEIGHT = 12;

    @Override
    public int minWidth() {
        return 2 * CONTENT_PADDING + 3 * SLOT + SCROLLBAR_WIDTH;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + SEARCH_HEIGHT + 2 * SLOT;
    }

    private static final int SCROLLBAR_WIDTH = 12;

    // --- Layout -----------------------------------------------------------

    @Override
    public void layoutSlots() {
        // RepoSlots are client-only. Keep stable instances while moving the panel and only rebuild
        // when the grid dimensions actually change.
        List<Slot> slots = menu.slots;
        if (!visible) {
            slots.removeAll(repoSlots);
            repoSlots.clear();
            return;
        }

        // Derive grid dimensions from the content area.
        int gridWidth = contentWidth() - SCROLLBAR_WIDTH;
        int gridHeight = contentHeight() - SEARCH_HEIGHT;
        this.cols = Math.max(1, gridWidth / SLOT);
        this.rows = Math.max(1, gridHeight / SLOT);
        this.repo.setRowSize(cols);

        int requiredSlots = rows * cols;
        if (repoSlots.size() != requiredSlots) {
            slots.removeAll(repoSlots);
            repoSlots.clear();
            for (int repoIndex = 0; repoIndex < requiredSlots; repoIndex++) {
                var repoSlot = new RepoSlot(this.repo, repoIndex, 0, 0);
                repoSlots.add(repoSlot);
                slots.add(repoSlot);
            }
        }

        int gridLeft = contentLeft();
        int gridTop = contentTop() + SEARCH_HEIGHT;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                RepoSlot slot = repoSlots.get(row * cols + col);
                slot.x = gridLeft + col * SLOT + 1;
                slot.y = gridTop + row * SLOT + 1;
            }
        }
        clampScroll();
    }

    @Override
    public void renderSlots(GuiGraphics g, PanelSlotRenderer renderer) {
        for (RepoSlot slot : repoSlots) {
            renderer.drawPanelSlot(g, slot);
        }
    }

    @Override
    public boolean ownsSlot(Slot slot) {
        for (RepoSlot repoSlot : repoSlots) {
            if (repoSlot == slot) {
                return true;
            }
        }
        return false;
    }

    private int totalRows() {
        return (repo.size() + cols - 1) / cols;
    }

    private int maxScroll() {
        return Math.max(0, totalRows() - rows);
    }

    private void clampScroll() {
        if (scrollOffset > maxScroll()) {
            scrollOffset = maxScroll();
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        scrollbar.setScroll(scrollOffset);
    }

    // --- Per-frame --------------------------------------------------------

    /** Called by the screen each frame before rendering. */
    public void tick(boolean paused) {
        // The repo only yields entries when enabled; AE2 gates this on grid connectivity.
        repo.setEnabled(menu.getLinkStatus().connected());
        repo.setPaused(paused);
        clampScroll();
    }

    // --- Rendering --------------------------------------------------------

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        // AE2's native terminal search-field texture and palette.
        int searchLeft = contentLeft();
        int searchTop = contentTop();
        int searchWidth = contentWidth();
        ModulePanel.drawTextField(g, searchLeft, searchTop, searchWidth, searchFocused);

        int textLeft = searchLeft + 3;
        int textTop = searchTop + 2;
        if (searchText.isEmpty() && !searchFocused) {
            g.drawString(font, Component.translatable("gui.ae2.search"), textLeft, textTop, 0xFFDEDFE3, false);
        } else {
            int availableWidth = Math.max(0, searchWidth - 6);
            SearchWindow window = searchWindow(font, availableWidth);
            String shown = searchText.substring(window.start(), window.end());

            // Draw the selection first so the text remains legible on top of the highlight.
            if (hasSearchSelection()) {
                int selectionStart = Math.max(window.start(), searchSelectionStart());
                int selectionEnd = Math.min(window.end(), searchSelectionEnd());
                if (selectionEnd > selectionStart) {
                    int highlightLeft = textLeft + font.width(
                            searchText.substring(window.start(), selectionStart));
                    int highlightRight = textLeft + font.width(
                            searchText.substring(window.start(), selectionEnd));
                    g.fill(highlightLeft, textTop - 1, Math.max(highlightLeft + 1, highlightRight),
                            textTop + 10, 0xFF4A6A8A);
                }
            }
            g.drawString(font, shown, textLeft, textTop, 0xFFFFFFFF, false);

            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cursor = Math.max(window.start(), Math.min(window.end(), cursorPosition));
                int cursorX = textLeft + font.width(searchText.substring(window.start(), cursor));
                g.fill(cursorX, textTop - 1, cursorX + 1, textTop + 9, 0xFFFFFFFF);
            }
        }

        // Slot cells (AE2 recessed slot art).
        int gridLeft = contentLeft();
        int gridTop = contentTop() + SEARCH_HEIGHT;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ModulePanel.drawSlot(g, gridLeft + col * SLOT, gridTop + row * SLOT);
            }
        }

        renderScrollbar(g);
    }

    private void renderScrollbar(GuiGraphics g) {
        int barLeft = contentLeft() + contentWidth() - SCROLLBAR_WIDTH + 2;
        int barTop = contentTop() + SEARCH_HEIGHT;
        int barWidth = SCROLLBAR_WIDTH - 4;
        int barHeight = rows * SLOT;
        scrollbar.render(g, barLeft, barTop, barWidth, barHeight, maxScroll());
    }

    /**
     * Render a single RepoSlot's content (item/fluid icon + amount label). Called by the host screen's
     * {@code renderSlot} override. Mirrors {@code MEStorageScreen.renderSlot}.
     */
    public void renderRepoSlot(GuiGraphics g, Font font, RepoSlot slot) {
        if (!menu.getLinkStatus().connected()) {
            return;
        }
        GridInventoryEntry entry = slot.getEntry();
        if (entry == null) {
            return;
        }
        try {
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, slot.x, slot.y, entry.getWhat());
        } catch (Exception e) {
            // Log once per key id instead of once per frame; a broken renderer would otherwise spam.
            String keyId = entry.getWhat().getId().toString();
            if (REPORTED_RENDER_FAILURES.add(keyId)) {
                com.lhy.mest.MESplicedterminal.LOGGER.warn(
                        "Failed to render ME entry {} in terminal grid", keyId, e);
            }
            return;
        }
        long storedAmount = entry.getStoredAmount();
        boolean craftable = entry.isCraftable();
        if (craftable && (isViewOnlyCraftable() || storedAmount <= 0)) {
            StackSizeRenderer.renderSizeLabel(g, font, slot.x, slot.y, "+");
        } else {
            String text = entry.getWhat().formatAmount(storedAmount, AmountFormat.SLOT);
            StackSizeRenderer.renderSizeLabel(g, font, slot.x, slot.y, text, false);
            if (craftable) {
                StackSizeRenderer.renderSizeLabel(g, font, slot.x - 11, slot.y - 11, "+", false);
            }
        }
    }

    /** Mirrors MEStorageScreen's craftable-only display and click mode. */
    public boolean isViewOnlyCraftable() {
        return getSortDisplay() == ViewItems.CRAFTABLE;
    }

    /** True if (mx,my) is inside the slot grid (used to claim scroll-wheel events). */
    public boolean inGrid(double mx, double my) {
        int gridLeft = contentLeft();
        int gridTop = contentTop() + SEARCH_HEIGHT;
        return mx >= gridLeft && mx < gridLeft + cols * SLOT
                && my >= gridTop && my < gridTop + rows * SLOT;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || scrollY == 0 || !inGrid(mx, my)) {
            return false;
        }
        scrollOffset -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    // --- Scrollbar drag interaction -------------------------------------

    private int scrollbarTrackX() {
        return contentLeft() + contentWidth() - SCROLLBAR_WIDTH + 2;
    }

    private int scrollbarTrackY() {
        return contentTop() + SEARCH_HEIGHT;
    }

    private int scrollbarTrackW() {
        return SCROLLBAR_WIDTH - 4;
    }

    private int scrollbarTrackH() {
        return rows * SLOT;
    }

    /** True if (mx,my) is over the scrollbar track. */
    public boolean inScrollbar(double mx, double my) {
        return mx >= scrollbarTrackX() && mx < scrollbarTrackX() + scrollbarTrackW()
                && my >= scrollbarTrackY() && my < scrollbarTrackY() + scrollbarTrackH();
    }

    /** Begin a scrollbar drag (or page-jump). Returns true if consumed. */
    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || !inScrollbar(mx, my)) {
            return false;
        }
        scrollbar.setScroll(scrollOffset);
        boolean consumed = scrollbar.mousePressed(mx, my,
                scrollbarTrackX(), scrollbarTrackY(), scrollbarTrackW(), scrollbarTrackH(),
                rows, maxScroll());
        if (consumed) {
            scrollOffset = scrollbar.scroll();
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
                maxScroll());
        scrollOffset = scrollbar.scroll();
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

    public void setSearch(String text) {
        String normalized = text == null ? "" : text;
        updateSearchText(normalized);
        cursorPosition = normalized.length();
        selectionAnchor = -1;
    }

    // --- Search field interaction ----------------------------------------

    public boolean inSearchField(double mx, double my) {
        int left = contentLeft();
        int top = contentTop();
        return mx >= left && mx < left + contentWidth() && my >= top && my < top + SEARCH_HEIGHT;
    }

    public void setSearchFocused(boolean focused) {
        this.searchFocused = focused;
        if (!focused) {
            selectionAnchor = -1;
        } else {
            cursorPosition = clampIndex(cursorPosition, searchText);
        }
    }

    public boolean isSearchFocused() {
        return searchFocused;
    }

    /** Handle a typed character while the search field is focused. Returns true if consumed. */
    public boolean searchCharTyped(char c) {
        if (!searchFocused || Character.isISOControl(c)) {
            return false;
        }
        replaceSelection(String.valueOf(c));
        return true;
    }

    /** Handle backspace while the search field is focused. Returns true if consumed. */
    public boolean searchBackspace() {
        if (!searchFocused) {
            return false;
        }
        if (hasSearchSelection()) {
            replaceSelection("");
            return true;
        }
        if (cursorPosition <= 0) {
            return true;
        }
        int start = previousCodePointIndex(searchText, cursorPosition);
        replaceRange(start, cursorPosition, "");
        return true;
    }

    /** Delete the code point to the right of the cursor (or the current selection). */
    public boolean searchDeleteForward() {
        if (!searchFocused) {
            return false;
        }
        if (hasSearchSelection()) {
            replaceSelection("");
            return true;
        }
        if (cursorPosition >= searchText.length()) {
            return true;
        }
        int end = nextCodePointIndex(searchText, cursorPosition);
        replaceRange(cursorPosition, end, "");
        return true;
    }

    /** Move the search cursor by one or more code points. */
    public boolean searchMoveCursor(int direction, boolean selecting) {
        if (!searchFocused) {
            return false;
        }
        if (!selecting && hasSearchSelection()) {
            cursorPosition = direction < 0 ? searchSelectionStart() : searchSelectionEnd();
            selectionAnchor = -1;
            return true;
        }
        int next = cursorPosition;
        if (direction < 0) {
            next = previousCodePointIndex(searchText, cursorPosition);
        } else if (direction > 0) {
            next = nextCodePointIndex(searchText, cursorPosition);
        }
        updateCursor(next, selecting);
        return true;
    }

    public boolean searchMoveHome(boolean selecting) {
        if (!searchFocused) {
            return false;
        }
        updateCursor(0, selecting);
        return true;
    }

    public boolean searchMoveEnd(boolean selecting) {
        if (!searchFocused) {
            return false;
        }
        updateCursor(searchText.length(), selecting);
        return true;
    }

    /** Select the complete query. */
    public boolean searchSelectAll() {
        if (!searchFocused) {
            return false;
        }
        selectionAnchor = 0;
        cursorPosition = searchText.length();
        return true;
    }

    /** Copy the current selection to Minecraft's clipboard. */
    public boolean searchCopySelection() {
        if (!searchFocused) {
            return false;
        }
        if (hasSearchSelection()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(
                    searchText.substring(searchSelectionStart(), searchSelectionEnd()));
        }
        return true;
    }

    /** Cut the current selection to Minecraft's clipboard. */
    public boolean searchCutSelection() {
        if (!searchFocused) {
            return false;
        }
        if (hasSearchSelection()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(
                    searchText.substring(searchSelectionStart(), searchSelectionEnd()));
            replaceSelection("");
        }
        return true;
    }

    /** Paste a printable clipboard string at the cursor, replacing the current selection. */
    public boolean searchPasteClipboard() {
        if (!searchFocused) {
            return false;
        }
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clipboard == null || clipboard.isEmpty()) {
            return true;
        }
        StringBuilder printable = new StringBuilder(clipboard.length());
        clipboard.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                printable.appendCodePoint(codePoint);
            }
        });
        if (printable.length() > 0) {
            replaceSelection(printable.toString());
        }
        return true;
    }

    /** True when a non-empty search selection exists. */
    public boolean hasSearchSelection() {
        return selectionAnchor >= 0 && selectionAnchor != cursorPosition;
    }

    public int searchCursorPosition() {
        return cursorPosition;
    }

    public int searchSelectionStart() {
        return hasSearchSelection() ? Math.min(selectionAnchor, cursorPosition) : cursorPosition;
    }

    public int searchSelectionEnd() {
        return hasSearchSelection() ? Math.max(selectionAnchor, cursorPosition) : cursorPosition;
    }

    /**
     * Place the cursor at the nearest text boundary to a mouse coordinate. The screen can call this
     * after focusing the field so a click edits at the clicked position rather than always appending.
     */
    public void placeSearchCursor(double mouseX, Font font) {
        if (!searchFocused) {
            return;
        }
        int availableWidth = Math.max(0, contentWidth() - 6);
        SearchWindow window = searchWindow(font, availableWidth);
        int localX = (int) Math.round(mouseX - (contentLeft() + 3));
        int best = window.start();
        int bestDistance = Integer.MAX_VALUE;
        for (int index = window.start(); index <= window.end();) {
            int textX = font.width(searchText.substring(window.start(), index));
            int distance = Math.abs(textX - localX);
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
            if (index == window.end()) {
                break;
            }
            index = nextCodePointIndex(searchText, index);
        }
        cursorPosition = best;
        selectionAnchor = -1;
    }

    private void updateSearchText(String text) {
        if (searchText.equals(text)) {
            return;
        }
        searchText = text;
        repo.setSearchString(text);
        repo.updateView();
        clampScroll();
    }

    private void replaceSelection(String replacement) {
        int start = searchSelectionStart();
        int end = searchSelectionEnd();
        replaceRange(start, end, replacement);
    }

    private void replaceRange(int start, int end, String replacement) {
        start = Math.max(0, Math.min(start, searchText.length()));
        end = Math.max(start, Math.min(end, searchText.length()));
        String next = searchText.substring(0, start) + replacement + searchText.substring(end);
        updateSearchText(next);
        cursorPosition = start + replacement.length();
        selectionAnchor = -1;
    }

    private void updateCursor(int position, boolean selecting) {
        int next = clampIndex(position, searchText);
        if (selecting) {
            if (selectionAnchor < 0) {
                selectionAnchor = cursorPosition;
            }
        } else {
            selectionAnchor = -1;
        }
        cursorPosition = next;
    }

    private static int clampIndex(int index, String text) {
        int result = Math.max(0, Math.min(index, text.length()));
        if (result > 0 && result < text.length()
                && Character.isLowSurrogate(text.charAt(result))
                && Character.isHighSurrogate(text.charAt(result - 1))) {
            result--;
        }
        return result;
    }

    private static int previousCodePointIndex(String text, int index) {
        int result = Math.max(0, Math.min(index, text.length()));
        if (result > 0) {
            result--;
            if (result > 0 && Character.isLowSurrogate(text.charAt(result))
                    && Character.isHighSurrogate(text.charAt(result - 1))) {
                result--;
            }
        }
        return result;
    }

    private static int nextCodePointIndex(String text, int index) {
        int result = Math.max(0, Math.min(index, text.length()));
        if (result < text.length()) {
            result += Character.charCount(text.codePointAt(result));
        }
        return result;
    }

    private SearchWindow searchWindow(Font font, int availableWidth) {
        int length = searchText.length();
        if (length == 0 || font.width(searchText) <= availableWidth) {
            return new SearchWindow(0, length);
        }

        int start = fitSuffixStart(font, length, availableWidth);
        int end = length;
        if (cursorPosition < start) {
            end = cursorPosition;
            start = fitSuffixStart(font, end, availableWidth);
            end = fitEnd(font, start, availableWidth);
        }
        return new SearchWindow(start, end);
    }

    private int fitSuffixStart(Font font, int end, int availableWidth) {
        int start = end;
        while (start > 0) {
            int previous = previousCodePointIndex(searchText, start);
            if (font.width(searchText.substring(previous, end)) > availableWidth) {
                break;
            }
            start = previous;
        }
        return start;
    }

    private int fitEnd(Font font, int start, int availableWidth) {
        int end = start;
        while (end < searchText.length()) {
            int next = nextCodePointIndex(searchText, end);
            if (font.width(searchText.substring(start, next)) > availableWidth) {
                break;
            }
            end = next;
        }
        return end;
    }

    private record SearchWindow(int start, int end) {
    }

    // --- Tooltip support --------------------------------------------------

    /** Returns the ME entry under the given mouse position, or null. */
    public GridInventoryEntry entryAt(double mx, double my) {
        if (!menu.getLinkStatus().connected()) {
            return null;
        }
        int gridLeft = contentLeft();
        int gridTop = contentTop() + SEARCH_HEIGHT;
        if (mx < gridLeft || my < gridTop) {
            return null;
        }
        int col = (int) ((mx - gridLeft) / SLOT);
        int row = (int) ((my - gridTop) / SLOT);
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return null;
        }
        int repoIndex = (row + scrollOffset) * cols + col;
        if (repoIndex < 0 || repoIndex >= repo.size()) {
            return null;
        }
        return repo.get(repoIndex);
    }

    /** Returns the actual virtual slot under the cursor using vanilla's 16x16 slot hit area. */
    public RepoSlot repoSlotAt(double mx, double my) {
        if (!visible) {
            return null;
        }
        for (RepoSlot slot : repoSlots) {
            if (mx >= slot.x && mx < slot.x + 16 && my >= slot.y && my < slot.y + 16) {
                return slot;
            }
        }
        return null;
    }

    // --- IScrollSource ----------------------------------------------------

    @Override
    public int getCurrentScroll() {
        return scrollOffset;
    }

    // --- ISortSource ------------------------------------------------------

    @Override
    public SortOrder getSortBy() {
        return configSrc.getSetting(Settings.SORT_BY);
    }

    @Override
    public SortDir getSortDir() {
        return configSrc.getSetting(Settings.SORT_DIRECTION);
    }

    @Override
    public ViewItems getSortDisplay() {
        return configSrc.getSetting(Settings.VIEW_MODE);
    }

    @Override
    public Set<AEKeyType> getSortKeyTypes() {
        // AEKeyTypes registration is frozen before any screen can open; Repo calls this on every
        // view update, so build the set once instead of reallocating an identical one each time.
        Set<AEKeyType> cached = sortKeyTypesCache;
        if (cached == null) {
            cached = Set.copyOf(new HashSet<>(AEKeyTypes.getAll()));
            sortKeyTypesCache = cached;
        }
        return cached;
    }

    private Set<AEKeyType> sortKeyTypesCache;
}
