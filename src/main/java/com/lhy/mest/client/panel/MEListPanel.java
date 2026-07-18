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
            String shown = font.plainSubstrByWidth(searchText, searchWidth - 8 - (searchFocused ? 5 : 0));
            // Show the tail of the text when it overflows.
            if (!shown.equals(searchText)) {
                shown = searchText.substring(searchText.length() - shown.length());
            }
            int textWidth = font.width(shown);
            g.drawString(font, shown, textLeft, textTop, 0xFFFFFFFF, false);
            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                g.fill(textLeft + textWidth, textTop - 1, textLeft + textWidth + 1, textTop + 9, 0xFFFFFFFF);
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
        scrollbar.render(g, barLeft, barTop, barWidth, barHeight, rows, totalRows(), maxScroll());
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
        } catch (Exception ignored) {
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

    public boolean mouseScrolled(double delta) {
        scrollOffset -= (int) Math.signum(delta);
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
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible || !inScrollbar(mx, my)) {
            return false;
        }
        scrollbar.setScroll(scrollOffset);
        boolean consumed = scrollbar.mousePressed(mx, my,
                scrollbarTrackX(), scrollbarTrackY(), scrollbarTrackW(), scrollbarTrackH(),
                rows, totalRows(), maxScroll());
        if (consumed) {
            scrollOffset = scrollbar.scroll();
        }
        return consumed;
    }

    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbar.isDragging()) {
            return false;
        }
        scrollbar.mouseDragged(my,
                scrollbarTrackY(), scrollbarTrackH(),
                rows, totalRows(), maxScroll());
        scrollOffset = scrollbar.scroll();
        return true;
    }

    public void scrollbarReleased() {
        scrollbar.mouseReleased();
    }

    public boolean scrollbarDragging() {
        return scrollbar.isDragging();
    }

    public void setSearch(String text) {
        this.searchText = text;
        repo.setSearchString(text);
        repo.updateView();
        clampScroll();
    }

    // --- Search field interaction ----------------------------------------

    public boolean inSearchField(double mx, double my) {
        int left = contentLeft();
        int top = contentTop();
        return mx >= left && mx < left + contentWidth() && my >= top && my < top + SEARCH_HEIGHT;
    }

    public void setSearchFocused(boolean focused) {
        this.searchFocused = focused;
    }

    public boolean isSearchFocused() {
        return searchFocused;
    }

    /** Handle a typed character while the search field is focused. Returns true if consumed. */
    public boolean searchCharTyped(char c) {
        if (!searchFocused) {
            return false;
        }
        setSearch(searchText + c);
        return true;
    }

    /** Handle backspace while the search field is focused. Returns true if consumed. */
    public boolean searchBackspace() {
        if (!searchFocused || searchText.isEmpty()) {
            return false;
        }
        setSearch(searchText.substring(0, searchText.length() - 1));
        return true;
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
        return repo.get(row * cols + col);
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
        return new HashSet<>(AEKeyTypes.getAll());
    }
}
