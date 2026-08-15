package com.lhy.mest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.EmptyingAction;
import appeng.api.client.AEKeyRendering;
import appeng.api.config.Settings;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.common.RepoSlot;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.AEConfig;
import appeng.core.AppEng;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import appeng.core.network.bidirectional.ConfigValuePacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.items.storage.ViewCellItem;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.util.Platform;

import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import com.lhy.mest.client.PatternProviderClientHandler.Entry;
import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.panel.CraftingPanel;
import com.lhy.mest.client.panel.InventoryPanel;
import com.lhy.mest.client.panel.MEListPanel;
import com.lhy.mest.client.panel.PatternAccessPanel;
import com.lhy.mest.client.panel.PatternEncodingPanel;
import com.lhy.mest.client.panel.SlotGridPanel;
import com.lhy.mest.integration.MestRecipeTransferContext;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Client screen for the ME Spliced Terminal.
 *
 * <p>Unlike a stock AE2 terminal (fixed JSON-driven layout), this screen extends {@link AEBaseScreen}
 * directly and hosts a set of free-floating, draggable, resizable {@link ModulePanel}s managed by a
 * {@link DockManager}. The dialog is pinned to the whole screen ({@code leftPos=topPos=0}), so menu
 * slot coordinates are absolute screen coordinates and panels can position their slots anywhere.
 */
public class MESTScreen extends AEBaseScreen<MESTMenu> implements IUniversalTerminalCapable {
    private final DockManager dock = new DockManager();
    private final List<Button> panelButtons = new ArrayList<>();
    private final List<SettingToggleButton<?>> meSettingButtons = new ArrayList<>();
    private final List<ItemStack> currentViewCells = new ArrayList<>();
    private final Set<AEKey> craftableKeys = new HashSet<>();
    /**
     * True only while drawing slot icons inside the per-panel render pass. Distinguishes that pass (draw the
     * icon) from the vanilla per-slot loop (skip panel-owned icons, since the panel pass already drew them).
     */
    private boolean drawingPanelSlots = false;
    private MEListPanel meListPanel;
    private CraftingPanel craftingPanel;
    private PatternEncodingPanel patternEncodingPanel;
    private PatternAccessPanel patternAccessPanel;
    private int toolbarX;
    private int toolbarY;
    private int toolbarHeight;
    private int dockStructureVersion = -1;

    public MESTScreen(MESTMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        menu.getProcessingOutputSlots()[0].setIcon(Icon.BACKGROUND_PRIMARY_OUTPUT);
        // Create the ME list panel (and thus register the client repo) in the constructor, matching
        // AE2's MEStorageScreen. Doing this in init() would be too late: the server's first full
        // inventory update can arrive before init() runs and would be dropped (null client repo).
        this.meListPanel = new MEListPanel(getMenu());
        this.meListPanel.repo().setUpdateViewListener(this::refreshCraftableKeys);
        getMenu().setGui(this::onMenuReceivedClientUpdate);
        MestRecipeTransferContext.beginMenu(menu);

        // ME list control buttons (sort order / view mode / sort direction). These reuse AE2's icon
        // buttons but are repositioned each frame to sit on the ME panel's title bar, so they travel
        // with the floating panel instead of docking to a fixed global toolbar.
        var cm = getMenu().getConfigManager();
        meSettingButtons.add(new SettingToggleButton<>(Settings.SORT_BY,
                cm.getSetting(Settings.SORT_BY), Platform::isSortOrderAvailable, this::toggleServerSetting));
        meSettingButtons.add(new SettingToggleButton<>(Settings.VIEW_MODE,
                cm.getSetting(Settings.VIEW_MODE), this::toggleServerSetting));
        meSettingButtons.add(new SettingToggleButton<>(Settings.SORT_DIRECTION,
                cm.getSetting(Settings.SORT_DIRECTION), this::toggleServerSetting));
        for (SettingToggleButton<?> button : meSettingButtons) {
            // IconButton's stock background is rendered at 18x20 around a 16x16 widget. The ME
            // title bar is only 18px tall and already supplies the button surface, so drawing that
            // background would bleed into the panel content (and would not match the hit box).
            button.setDisableBackground(true);
            // AbstractWidget exposes a safe mutable height. Cover the full title-bar height so the
            // bottom two pixels do not unexpectedly start a panel drag instead of pressing the
            // visible icon.
            button.setHeight(ModulePanel.TITLE_BAR_HEIGHT);
        }
    }

    private <SE extends Enum<SE>> void toggleServerSetting(SettingToggleButton<SE> btn, boolean backwards) {
        SE next = btn.getNextValue(backwards);
        PacketDistributor.sendToServer(new ConfigValuePacket(btn.getSetting(), next));
        btn.set(next);
        if (meListPanel != null) {
            meListPanel.repo().updateView();
        }
    }

    public void onMenuReceivedClientUpdate() {
        for (SettingToggleButton<?> button : meSettingButtons) {
            syncSettingButton(button);
        }
        if (meListPanel != null) {
            meListPanel.repo().updateView();
        }
    }

    private <SE extends Enum<SE>> void syncSettingButton(SettingToggleButton<SE> button) {
        button.set(getMenu().getConfigManager().getSetting(button.getSetting()));
    }

    @Override
    protected boolean shouldAddToolbar() {
        // We do not use AE2's fixed vertical toolbar; controls live on the floating panels instead.
        return false;
    }

    @Override
    protected void init() {
        // Keep the base dialog rect negligible (0x0 at 0,0) so JEI/EMI don't treat the whole screen as
        // occluded by the GUI (they assume [leftPos,topPos,imageWidth,imageHeight] is always occupied).
        // Slot coordinates are absolute screen coords set directly by panels, so they're unaffected.
        this.imageWidth = 0;
        this.imageHeight = 0;

        super.init();
        // super.init() clears registered widgets, but this bookkeeping list survives screen resize.
        panelButtons.clear();

        this.leftPos = 0;
        this.topPos = 0;

        // The screen is registered with AE2's stock wireless_terminal style, which inherits text labels
        // (player inventory title, dialog title, item-count) anchored to a fixed GUI layout that doesn't
        // apply to our full-screen floating-panel UI. Hide them all so they don't bleed through.
        setTextHidden("player_inventory_title", true);
        setTextHidden("dialog_title", true);
        setTextHidden("entriesShown", true);

        if (dock.isEmpty()) {
            List<ModulePanel> panels = new ArrayList<>();
            panels.add(meListPanel);
            craftingPanel = new CraftingPanel(getMenu());
            panels.add(craftingPanel);
            patternEncodingPanel = new PatternEncodingPanel(getMenu());
            panels.add(patternEncodingPanel);
            patternAccessPanel = new PatternAccessPanel();
            panels.add(patternAccessPanel);
            panels.add(new InventoryPanel(getMenu()));
            var upgrades = new SlotGridPanel("upgrades",
                    Component.translatable("gui.mesplicedterminal.upgrades"),
                    getMenu().getSlots(SlotSemantics.UPGRADE), 2);
            if (upgrades.hasSlots()) {
                panels.add(upgrades);
            }
            var viewCells = new SlotGridPanel("view_cells",
                    Component.translatable("gui.mesplicedterminal.view_cells"),
                    getMenu().getSlots(SlotSemantics.VIEW_CELL), 5);
            if (viewCells.hasSlots()) {
                panels.add(viewCells);
            }
            dock.init(panels, this.width, this.height);
        } else {
            dock.updateViewport(this.width, this.height);
        }

        dock.layoutAll();
        selectVisibleRecipeTargetIfNeeded();
        // Register the ME control buttons as renderable widgets; positioned each frame in layout pass.
        for (SettingToggleButton<?> b : meSettingButtons) {
            addRenderableWidget(b);
        }
        repositionMeButtons();
        buildToolbar();
        dockStructureVersion = dock.structureVersion();
    }

    /** Place the ME list control buttons on the ME panel's title bar (right-aligned), or hide them. */
    private void repositionMeButtons() {
        boolean panelVisible = meListPanel != null && dock.isEffectivelyVisible(meListPanel);
        // With the oversized IconButton background disabled, the 16px icon fits inside the
        // AE2-style 18px title header at y + 1 (or y + 2 while hovered).
        int bx = meListPanel != null ? meListPanel.x + meListPanel.width - 18 : 0;
        int by = meListPanel != null ? meListPanel.y : 0;
        for (SettingToggleButton<?> b : meSettingButtons) {
            b.setX(bx);
            b.setY(by);
            boolean show = panelVisible && dock.isAreaUnobscured(
                    meListPanel,
                    new DockRect(bx, by, b.getWidth(), b.getHeight()));
            b.visible = show;
            b.active = show;
            bx -= 18;
        }
    }

    /** AE2-style vertical icon toolbar: module visibility and workspace controls. */
    private void buildToolbar() {
        toolbarX = Math.max(4, this.width - 20);
        toolbarY = 6;
        int by = toolbarY;
        for (ModulePanel p : dock.panels()) {
            var btn = new PanelToolbarButton(p, b -> {
                dock.toggleVisible(p);
                selectVisibleRecipeTargetIfNeeded();
                rebuildToolbar();
            });
            btn.setX(toolbarX);
            btn.setY(by);
            addRenderableWidget(btn);
            panelButtons.add(btn);
            by += 22;
        }
        var lock = new ToolbarIconButton(
                dock.isLayoutLocked() ? Icon.LOCKED : Icon.UNLOCKED,
                Component.translatable(dock.isLayoutLocked()
                        ? "gui.mesplicedterminal.unlock_layout"
                        : "gui.mesplicedterminal.lock_layout"),
                b -> {
                    dock.toggleLayoutLocked();
                    rebuildToolbar();
                });
        lock.setX(toolbarX);
        lock.setY(by);
        addRenderableWidget(lock);
        panelButtons.add(lock);
        by += 22;

        var undo = new ToolbarIconButton(
                Icon.BACK,
                Component.translatable("gui.mesplicedterminal.undo_layout"),
                b -> {
                    dock.undoLayout();
                    rebuildToolbar();
                });
        undo.active = dock.canUndoLayout();
        undo.setX(toolbarX);
        undo.setY(by);
        addRenderableWidget(undo);
        panelButtons.add(undo);
        by += 22;

        var compact = new ToolbarIconButton(
                Icon.TERMINAL_STYLE_SMALL,
                Component.translatable("gui.mesplicedterminal.compact_layout"),
                b -> {
                    dock.applyCompactPreset();
                    rebuildToolbar();
                });
        compact.setX(toolbarX);
        compact.setY(by);
        addRenderableWidget(compact);
        panelButtons.add(compact);
        by += 22;

        var reset = new ToolbarIconButton(Icon.SCHEDULING_ROUND_ROBIN,
                Component.translatable("gui.mesplicedterminal.reset_layout"), b -> {
            dock.resetLayout();
            rebuildToolbar();
        });
        reset.setX(toolbarX);
        reset.setY(by);
        addRenderableWidget(reset);
        panelButtons.add(reset);
        by += 22;

        var editor = new ToolbarIconButton(
                Icon.TERMINAL_STYLE_SMALL,
                Component.translatable("gui.mesplicedterminal.edit_layout"),
                b -> Minecraft.getInstance().setScreen(new MESTLayoutEditorScreen(this, dock)));
        editor.setX(toolbarX);
        editor.setY(by);
        addRenderableWidget(editor);
        panelButtons.add(editor);
        by += 22;
        toolbarHeight = by - toolbarY - 2;
    }

    private void selectVisibleRecipeTargetIfNeeded() {
        var selected = MestRecipeTransferContext.targetFor(getMenu());
        if (selected == MestRecipeTransferContext.Target.PATTERN_ENCODING
                && !dock.isEffectivelyVisible(patternEncodingPanel)
                && dock.isEffectivelyVisible(craftingPanel)) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.CRAFTING);
        } else if (selected == MestRecipeTransferContext.Target.CRAFTING
                && !dock.isEffectivelyVisible(craftingPanel)
                && dock.isEffectivelyVisible(patternEncodingPanel)) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.PATTERN_ENCODING);
        }
    }

    private void rebuildToolbar() {
        // Remove only our own panel buttons (leave AE2's vertical toolbar intact).
        for (Button b : panelButtons) {
            removeWidget(b);
        }
        panelButtons.clear();
        buildToolbar();
        dockStructureVersion = dock.structureVersion();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        if (dockStructureVersion != dock.structureVersion()) {
            rebuildToolbar();
        }
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel)) {
            meListPanel.tick(hasShiftDown());
            updateViewCellFilter();
        }
        if (patternEncodingPanel != null && dock.isEffectivelyVisible(patternEncodingPanel)) {
            patternEncodingPanel.tick();
        }
        if (patternAccessPanel != null && dock.isEffectivelyVisible(patternAccessPanel)) {
            patternAccessPanel.tick();
        }
        // Keep the ME control buttons glued to the (possibly moved/resized) ME panel.
        repositionMeButtons();
    }

    private void updateViewCellFilter() {
        var viewCells = getMenu().getViewCells();
        if (!currentViewCells.equals(viewCells)) {
            currentViewCells.clear();
            currentViewCells.addAll(viewCells);
            meListPanel.repo().setPartitionList(ViewCellItem.createFilter(AEKeyFilter.none(), viewCells));
        }
    }

    @Override
    public void drawBG(GuiGraphics g, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        // No global background (floating panels only). Draw each panel's frame → background content → owned
        // slot icons back-to-front, so a top panel's frame paints over a lower panel's slot icons. Slot icons
        // are drawn here (in the panel pass) instead of in the vanilla per-slot loop to get z-order right.
        drawingPanelSlots = true;
        try {
            dock.renderBackground(g, this.font, mouseX, mouseY, partialTicks, this::drawPanelSlot);
        } finally {
            drawingPanelSlots = false;
        }
        if (!panelButtons.isEmpty()) {
            g.blitSprite(AppEng.makeId("vertical_buttons_bg"), toolbarX - 3, toolbarY - 3,
                    1, 21, toolbarHeight + 6);
        }
    }

    /**
     * Per-slot draw delegate passed to panels. Reuses AE2's existing RepoSlot / AppEngSlot rendering (via
     * {@link #renderSlot}, which honors {@link #drawingPanelSlots}) so no slot rendering logic is duplicated.
     */
    private void drawPanelSlot(GuiGraphics g, Slot s) {
        renderSlot(g, s);
    }

    @Override
    public void drawFG(GuiGraphics g, int offsetX, int offsetY, int mouseX, int mouseY) {
        dock.renderForeground(g, this.font, mouseX, mouseY, 0);
    }

    /**
     * Report each visible floating panel as a JEI/EMI exclusion zone so the recipe viewer keeps its
     * panels clear of our panels. The base dialog rect is 0x0 (see {@link #init()}), so it contributes
     * no occlusion on its own.
     */
    @Override
    public List<Rect2i> getExclusionZones() {
        List<Rect2i> zones = new ArrayList<>();
        for (DockRect bounds : dock.exclusionBounds()) {
            zones.add(new Rect2i(bounds.x(), bounds.y(), bounds.width(), bounds.height()));
        }
        if (!panelButtons.isEmpty()) {
            zones.add(new Rect2i(toolbarX - 3, toolbarY - 3, 21, toolbarHeight + 6));
        }
        return zones;
    }

    /** Shared AE2 icon mapping for a module panel; also used by the layout editor sidebar. */
    public static Icon iconForPanel(ModulePanel panel) {
        return switch (panel.id()) {
            case "me_list" -> Icon.BACKGROUND_WIRELESS_TERM;
            case "crafting" -> Icon.CRAFT_HAMMER;
            case "pattern_encoding" -> Icon.BACKGROUND_ENCODED_PATTERN;
            case "pattern_access" -> Icon.PATTERN_ACCESS_SHOW;
            case "inventory" -> Icon.BACKGROUND_STORAGE_CELL;
            case "upgrades" -> Icon.BACKGROUND_UPGRADE;
            case "view_cells" -> Icon.BACKGROUND_VIEW_CELL;
            default -> Icon.COG;
        };
    }

    private static class ToolbarIconButton extends IconButton {
        private final Icon icon;

        ToolbarIconButton(Icon icon, Component tooltip, OnPress onPress) {
            super(onPress);
            this.icon = icon;
            setMessage(tooltip);
        }

        @Override
        protected Icon getIcon() {
            return icon;
        }
    }

    private static final class PanelToolbarButton extends ToolbarIconButton {
        PanelToolbarButton(ModulePanel panel, OnPress onPress) {
            super(iconForPanel(panel), panel.title(), onPress);
        }
    }

    /**
     * Delegate RepoSlot rendering to the ME list panel; everything else uses the base behaviour.
     *
     * <p>This is invoked in two places: (1) the per-panel {@code renderSlots} pass (via {@link #drawPanelSlot}),
     * where {@link #suppressVanillaSlotDraw} is true and we want the icon drawn; and (2) the vanilla per-slot
     * loop, where panel-owned slots have <em>already</em> been drawn in the panel pass — so we must no-op for
     * any panel-owned slot to avoid drawing it twice (which would re-introduce cross-panel icon bleed, since
     * the vanilla loop iterates in flat index order, not z-order).
     */
    @Override
    public void renderSlot(GuiGraphics g, Slot s) {
        if (!drawingPanelSlots && dock.panelForSlot(s) != null) {
            // Vanilla per-slot loop: panel already drew this slot in its own pass. Skip.
            return;
        }
        if (s instanceof RepoSlot repoSlot) {
            if (meListPanel != null) {
                meListPanel.renderRepoSlot(g, this.font, repoSlot);
            }
            return;
        }
        super.renderSlot(g, s);
        if (getMenu().isPatternEncodingInputSlot(s)) {
            GenericStack stack = GenericStack.fromItemStack(s.getItem());
            var repo = getMenu().getClientRepo();
            if (stack != null && repo != null && isCraftable(stack.what())) {
                StackSizeRenderer.renderSizeLabel(g, font, s.x - 11, s.y - 11, "+", false);
            }
        }
    }

    /**
     * Gate the vanilla hover highlight + hoveredSlot + findSlot (click hit-test) by panel z-order: a slot is
     * only "hovered" if it belongs to the topmost panel under the cursor. This single override kills cross-panel
     * highlight bleed and makes clicked/hovered slots respect z-order. It also makes JEI/EMI's
     * {@code getStackUnderMouse} (which uses hoveredSlot) z-order-correct.
     */
    @Override
    protected boolean isHovering(Slot slot, double mx, double my) {
        ModulePanel top = dock.topPanelAt(mx, my);
        if (top != null) {
            // Only the top panel's slots are hoverable. panelForSlot is backed by the dock's
            // per-projection slot cache, so this per-slot-per-frame call stays cheap.
            return dock.panelForSlot(slot) == top && super.isHovering(slot, mx, my);
        }
        return super.isHovering(slot, mx, my);
    }

    private Slot panelSlotAt(ModulePanel panel, double mouseX, double mouseY) {
        if (panel == null || dock.topLeafAt(mouseX, mouseY) != panel) {
            return null;
        }
        for (Slot slot : getMenu().slots) {
            if (panel.ownsSlot(slot) && slot.isActive()
                    && mouseX >= slot.x && mouseX < slot.x + 16
                    && mouseY >= slot.y && mouseY < slot.y + 16) {
                return slot;
            }
        }
        return null;
    }

    private boolean isCraftable(AEKey key) {
        return craftableKeys.contains(key);
    }

    private void refreshCraftableKeys() {
        craftableKeys.clear();
        for (GridInventoryEntry entry : meListPanel.repo().getAllEntries()) {
            if (entry.isCraftable()) {
                craftableKeys.add(entry.getWhat());
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // The global toolbar is not owned by any floating root. Do not focus an arbitrary panel
        // underneath it when the toolbar happens to overlap the workspace.
        if (isMouseOverToolbarWidget(mx, my)) {
            if (meListPanel != null) {
                meListPanel.setSearchFocused(false);
            }
            return super.mouseClicked(mx, my, button);
        }

        // Raise the pointed root before dispatching to panel-owned controls. A finally block commits
        // the raise only if dock chrome did not take ownership and merge it into a drag/resize.
        dock.beginPanelInteraction(mx, my);
        try {
            return mouseClickedInWorkspace(mx, my, button);
        } finally {
            dock.commitPanelInteraction();
        }
    }

    private boolean mouseClickedInWorkspace(double mx, double my, int button) {
        ModulePanel target = dock.topLeafAt(mx, my);

        // ME header controls render above panel chrome, but still belong to the ME root and therefore
        // participate in its pre-dispatch focus transaction.
        if (isMouseOverMeSettingButton(mx, my)) {
            if (meListPanel != null) {
                meListPanel.setSearchFocused(false);
            }
            return super.mouseClicked(mx, my, button);
        }

        if (target == craftingPanel) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.CRAFTING);
        } else if (target == patternEncodingPanel) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.PATTERN_ENCODING);
        }

        // Search focus and custom controls are scoped to the topmost leaf only.
        if (target == meListPanel) {
            if (button == 0 && meListPanel.inSearchField(mx, my)) {
                meListPanel.setSearchFocused(true);
                // Put the insertion point where the user clicked instead of always appending to the
                // previous query. The panel accounts for horizontal clipping and code-point
                // boundaries itself.
                meListPanel.placeSearchCursor(mx, this.font);
                return true;
            }
            meListPanel.setSearchFocused(false);
        } else if (meListPanel != null) {
            meListPanel.setSearchFocused(false);
        }

        // AE2 handles the pick-item mouse binding specially so craftable entries open the crafting
        // amount screen even in survival mode, where vanilla's creative clone handling would not run.
        if (target == meListPanel && Minecraft.getInstance().options.keyPickItem.matchesMouse(button)) {
            Slot slot = meListPanel.repoSlotAt(mx, my);
            if (slot instanceof RepoSlot repoSlot && repoSlot.isCraftable()) {
                handleGridInventoryEntryMouseClick(repoSlot.getEntry(), button, ClickType.CLONE);
                return true;
            }
        }
        if (target == patternEncodingPanel
                && Minecraft.getInstance().options.keyPickItem.matchesMouse(button)) {
            Slot slot = panelSlotAt(patternEncodingPanel, mx, my);
            if (slot != null
                    && patternEncodingPanel.ownsSlot(slot)
                    && getMenu().canModifyAmountForPatternSlot(slot)) {
                GenericStack currentStack = GenericStack.fromItemStack(slot.getItem());
                if (currentStack != null) {
                    switchToScreen(new MestSetProcessingPatternAmountScreen(
                            this,
                            currentStack,
                            newStack -> PacketDistributor.sendToServer(new InventoryActionPacket(
                                    InventoryAction.SET_FILTER,
                                    slot.index,
                                    GenericStack.wrapInItemStack(newStack)))));
                    return true;
                }
            }
        }

        // Scrollbar drag takes priority over panel drag so the thumb stays grabbed. Only the
        // topmost leaf under the cursor may claim its scrollbar (virtual, default no-op).
        if (button == 0 && target != null && target.scrollbarPressed(mx, my)) {
            return true;
        }
        if (target == craftingPanel && craftingPanel.mouseClicked(mx, my, button)) {
            return true;
        }
        if (dock.mouseClicked(mx, my, button)) {
            return true;
        }
        if (target == patternEncodingPanel && patternEncodingPanel.mouseClicked(mx, my, button)) {
            return true;
        }
        if (target == patternAccessPanel && patternAccessPanel.mouseClicked(mx, my, button)) {
            return true;
        }

        boolean handled = super.mouseClicked(mx, my, button);
        // Empty content in the topmost panel still blocks panels and widgets below it.
        return handled || dock.topPanelAt(mx, my) != null;
    }

    private boolean isMouseOverToolbarWidget(double mx, double my) {
        for (Button button : panelButtons) {
            if (button.visible && button.isMouseOver(mx, my)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMouseOverMeSettingButton(double mx, double my) {
        for (SettingToggleButton<?> button : meSettingButtons) {
            if (button.visible && button.isMouseOver(mx, my)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        for (ModulePanel panel : dock.panels()) {
            if (panel.scrollbarDragged(mx, my)) {
                return true;
            }
        }
        if (dock.mouseDragged(mx, my, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean wasDraggingScrollbar = false;
        for (ModulePanel panel : dock.panels()) {
            wasDraggingScrollbar |= panel.scrollbarDragging();
            panel.scrollbarReleased();
        }
        if (wasDraggingScrollbar) {
            return true;
        }
        if (dock.mouseReleased(mx, my, button)) {
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        ModulePanel target = dock.topLeafAt(mx, my);
        // Shift+scroll over an ME entry rolls single items in/out of the network instead of
        // scrolling the grid; this screen-level interaction runs before the panel's own scroll.
        if (target == meListPanel && meListPanel.inGrid(mx, my) && scrollY != 0
                && hasShiftDown() && meListPanel.repoSlotAt(mx, my) instanceof RepoSlot repoSlot) {
            GridInventoryEntry entry = repoSlot.getEntry();
            long serial = entry != null ? entry.getSerial() : -1;
            InventoryAction action = toInventoryAction(MEListInteractionPolicy.scrollAction(scrollY));
            for (int i = 0; i < MEListInteractionPolicy.scrollSteps(scrollY); i++) {
                getMenu().handleInteraction(serial, action);
            }
            return true;
        }
        if (target != null && target.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        return dock.topPanelAt(mx, my) != null || super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    public void updatePatternProviders(List<Entry> entries) {
        if (patternAccessPanel != null) {
            patternAccessPanel.setProviders(entries);
        }
    }

    /**
     * Route clicks on virtual ME slots to the network interaction handler instead of the vanilla
     * slot-click path (RepoSlots are read-only client slots backed by the ME network).
     */
    @Override
    protected void slotClicked(Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (slot instanceof RepoSlot repoSlot) {
            handleGridInventoryEntryMouseClick(repoSlot.getEntry(), mouseButton, clickType);
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    private void handleGridInventoryEntryMouseClick(GridInventoryEntry entry, int mouseButton, ClickType clickType) {
        var menu = getMenu();

        // Preserve AE2's container strategy path for fluids and other keys backed by a portable
        // container. It takes precedence over ordinary item extraction/insertion.
        if (mouseButton == 0 && entry != null && ContainerItemStrategies.isKeySupported(entry.getWhat())) {
            InventoryAction action = toInventoryAction(MEListInteractionPolicy.fillContainerAction(
                    clickType == ClickType.QUICK_MOVE, menu.getCarried().isEmpty()));
            menu.handleInteraction(entry.getSerial(), action);
            return;
        }

        if (mouseButton == 1 && !menu.getCarried().isEmpty()) {
            var emptyingAction = ContainerItemStrategies.getEmptyingAction(menu.getCarried());
            if (emptyingAction != null && menu.isKeyVisible(emptyingAction.what())) {
                menu.handleInteraction(-1,
                        toInventoryAction(MEListInteractionPolicy.emptyContainerAction(
                                clickType == ClickType.QUICK_MOVE)));
                return;
            }
        }

        if (entry == null) {
            // Putting a held item down into the network (click on empty virtual slot).
            if (clickType == ClickType.PICKUP && !menu.getCarried().isEmpty()) {
                menu.handleInteraction(-1, mouseButton == 1
                        ? InventoryAction.SPLIT_OR_PLACE_SINGLE
                        : InventoryAction.PICKUP_OR_SET_DOWN);
            }
            return;
        }

        long serial = entry.getSerial();

        if (InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) {
            menu.handleInteraction(serial, InventoryAction.MOVE_REGION);
            return;
        }

        InventoryAction action = null;
        switch (clickType) {
            case PICKUP -> {
                action = mouseButton == 1
                        ? InventoryAction.SPLIT_OR_PLACE_SINGLE
                        : InventoryAction.PICKUP_OR_SET_DOWN;
                if (action == InventoryAction.PICKUP_OR_SET_DOWN
                        && MEListInteractionPolicy.shouldCraftOnClick(
                                meListPanel.isViewOnlyCraftable(), entry.getStoredAmount(), entry.isCraftable())
                        && menu.getCarried().isEmpty()) {
                    menu.handleInteraction(serial, InventoryAction.AUTO_CRAFT);
                    return;
                }
            }
            case QUICK_MOVE -> action = mouseButton == 1
                    ? InventoryAction.PICKUP_SINGLE
                    : InventoryAction.SHIFT_CLICK;
            case CLONE -> {
                if (entry.isCraftable()) {
                    menu.handleInteraction(serial, InventoryAction.AUTO_CRAFT);
                    return;
                } else if (menu.getPlayer().getAbilities().instabuild) {
                    action = InventoryAction.CREATIVE_DUPLICATE;
                }
            }
            default -> {
            }
        }
        if (action != null) {
            menu.handleInteraction(serial, action);
        }
    }

    private static InventoryAction toInventoryAction(MEListInteractionPolicy.ContainerAction action) {
        return switch (action) {
            case FILL_ONE -> InventoryAction.FILL_ITEM;
            case FILL_ALL -> InventoryAction.FILL_ENTIRE_ITEM;
            case FILL_ALL_TO_PLAYER -> InventoryAction.FILL_ENTIRE_ITEM_MOVE_TO_PLAYER;
            case EMPTY_ONE -> InventoryAction.EMPTY_ITEM;
            case EMPTY_ALL -> InventoryAction.EMPTY_ENTIRE_ITEM;
        };
    }

    private static InventoryAction toInventoryAction(MEListInteractionPolicy.ScrollAction action) {
        return switch (action) {
            case INSERT_ONE -> InventoryAction.ROLL_DOWN;
            case EXTRACT_ONE -> InventoryAction.ROLL_UP;
        };
    }

    @Override
    protected EmptyingAction getEmptyingAction(Slot slot, ItemStack carried) {
        if (getMenu().isProcessingPatternSlot(slot)) {
            EmptyingAction action = ContainerItemStrategies.getEmptyingAction(carried);
            if (action != null) {
                return action;
            }
        }
        return super.getEmptyingAction(slot, carried);
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = super.getTooltipFromContainerItem(stack);
        if (hoveredSlot != null && getMenu().isPatternEncodingInputSlot(hoveredSlot)) {
            GenericStack genericStack = GenericStack.fromItemStack(hoveredSlot.getItem());
            var repo = getMenu().getClientRepo();
            if (genericStack != null && repo != null && isCraftable(genericStack.what())) {
                lines = new ArrayList<>(lines);
                lines.add(ButtonToolTips.Craftable.text().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return lines;
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int x, int y) {
        ModulePanel target = dock.topLeafAt(x, y);
        if (target == patternEncodingPanel && getMenu().getCarried().isEmpty()) {
            Slot slot = panelSlotAt(patternEncodingPanel, x, y);
            if (slot != null
                    && patternEncodingPanel.ownsSlot(slot)
                    && getMenu().canModifyAmountForPatternSlot(slot)) {
                GenericStack stack = GenericStack.fromItemStack(slot.getItem());
                if (stack != null) {
                    var lines = new ArrayList<>(getTooltipFromContainerItem(slot.getItem()));
                    lines.add(Tooltips.getAmountTooltip(ButtonToolTips.Amount, stack));
                    lines.add(Tooltips.getSetAmountTooltip());
                    drawTooltip(g, x, y, lines);
                    return;
                }
            }
        }

        if (target == meListPanel && !getMenu().getCarried().isEmpty()
                && meListPanel.repoSlotAt(x, y) instanceof RepoSlot) {
            ItemStack carried = getMenu().getCarried();
            EmptyingAction action = ContainerItemStrategies.getEmptyingAction(carried);
            if (action != null && getMenu().isKeyVisible(action.what())) {
                drawTooltip(g, x, y,
                        Tooltips.getEmptyingTooltip(ButtonToolTips.StoreAction, carried, action));
                return;
            }
        }

        // ME entry tooltip only when the ME list panel is the topmost panel at the cursor — otherwise a
        // panel stacked above the ME list would still surface the underlying ME item's tooltip (bug 5).
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel) && getMenu().getCarried().isEmpty()
                && target == meListPanel) {
            GridInventoryEntry entry = meListPanel.entryAt(x, y);
            if (entry != null) {
                List<Component> lines = AEKeyRendering.getTooltip(entry.getWhat());
                if (Tooltips.shouldShowAmountTooltip(entry.getWhat(), entry.getStoredAmount())) {
                    lines.add(Tooltips.getAmountTooltip(
                            ButtonToolTips.StoredAmount, entry.getWhat(), entry.getStoredAmount()));
                }
                if (entry.getRequestableAmount() > 0) {
                    String amount = entry.getWhat().formatAmount(entry.getRequestableAmount(), AmountFormat.FULL);
                    lines.add(ButtonToolTips.RequestableAmount.text(amount));
                }
                if (entry.isCraftable()
                        && !(meListPanel.isViewOnlyCraftable() || entry.getStoredAmount() <= 0)) {
                    lines.add(ButtonToolTips.Craftable.text().withStyle(ChatFormatting.DARK_GRAY));
                }
                if (Minecraft.getInstance().options.advancedItemTooltips) {
                    lines.add(ButtonToolTips.Serial.text(entry.getSerial()).withStyle(ChatFormatting.DARK_GRAY));
                }
                if (entry.getWhat() instanceof AEItemKey itemKey) {
                    var stack = itemKey.getReadOnlyStack();
                    g.renderTooltip(font, lines, stack.getTooltipImage(), stack, x, y);
                } else {
                    g.renderComponentTooltip(font, lines, x, y);
                }
                return;
            }
        }
        super.renderTooltip(g, x, y);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (meListPanel != null && meListPanel.isSearchFocused() && meListPanel.searchCharTyped(character)) {
            return true;
        }
        return super.charTyped(character, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Search field editing while focused. Keep this handling ahead of AE2/vanilla hotkeys: while
        // the query owns focus, navigation and clipboard shortcuts must never activate a terminal
        // action or close the screen.
        if (meListPanel != null && meListPanel.isSearchFocused()) {
            boolean selecting = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                    || (modifiers & GLFW.GLFW_MOD_SUPER) != 0;

            switch (keyCode) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    return meListPanel.searchBackspace();
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    return meListPanel.searchDeleteForward();
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    return meListPanel.searchMoveCursor(-1, selecting);
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    return meListPanel.searchMoveCursor(1, selecting);
                }
                case GLFW.GLFW_KEY_HOME -> {
                    return meListPanel.searchMoveHome(selecting);
                }
                case GLFW.GLFW_KEY_END -> {
                    return meListPanel.searchMoveEnd(selecting);
                }
                case GLFW.GLFW_KEY_A -> {
                    if (control) {
                        return meListPanel.searchSelectAll();
                    }
                }
                case GLFW.GLFW_KEY_C -> {
                    if (control) {
                        return meListPanel.searchCopySelection();
                    }
                }
                case GLFW.GLFW_KEY_X -> {
                    if (control) {
                        return meListPanel.searchCutSelection();
                    }
                }
                case GLFW.GLFW_KEY_V -> {
                    if (control) {
                        return meListPanel.searchPasteClipboard();
                    }
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_ESCAPE -> {
                    meListPanel.setSearchFocused(false);
                    return true;
                }
                default -> {
                    // Swallow all other key presses while the field is focused. This prevents
                    // function keys and terminal shortcuts from leaking through during editing.
                }
            }
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (!handled) {
            return checkForTerminalKeys(keyCode, scanCode);
        }
        return true;
    }

    @Override
    public WTMenuHost getHost() {
        return (WTMenuHost) getMenu().getHost();
    }

    @Override
    public boolean isHandlingRightClick() {
        // AEBaseScreen temporarily sets this flag while it forwards a right click to a button as a
        // left click. SettingToggleButton reads it to decide whether to cycle backwards.
        return super.isHandlingRightClick();
    }

    @Override
    public void storeState() {
        dock.save();
    }

    void closePatternAccessSubscription() {
        if (patternAccessPanel != null) {
            patternAccessPanel.closeSubscription();
        }
    }

    private void clearRecipeTransferContext() {
        MestRecipeTransferContext.clear(getMenu());
    }

    @Override
    public void onClose() {
        if (AEConfig.instance().isClearGridOnClose()) {
            getMenu().clearCraftingGrid();
            getMenu().clearPatternEncoding();
        }
        clearRecipeTransferContext();
        super.onClose();
    }

    @Override
    public void removed() {
        closePatternAccessSubscription();
        clearRecipeTransferContext();
        dock.save();
        super.removed();
    }
}
