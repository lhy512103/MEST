package com.lhy.mest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.EmptyingAction;
import appeng.api.client.AEKeyRendering;
import appeng.api.config.ActionItems;
import appeng.api.config.Settings;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.me.common.RepoSlot;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.KeyTypeSelectionButton;
import appeng.client.gui.widgets.OpenGuideButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.Tooltip;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.TabButton;
import appeng.core.AEConfig;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.GuiText;
import appeng.core.localization.Tooltips;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.bidirectional.ConfigValuePacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.menu.me.crafting.CraftingStatusMenu;
import appeng.helpers.InventoryAction;
import appeng.items.storage.ViewCellItem;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.util.Platform;

import de.mari_023.ae2wtlib.api.AE2wtlibAPI;
import de.mari_023.ae2wtlib.api.TextConstants;
import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.registration.WTDefinition;
import de.mari_023.ae2wtlib.wct.ArmorSlot;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.PatternProviderClientHandler.Entry;
import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.client.dock.ExtraChrome;
import com.lhy.mest.client.dock.ExtraSlotColumn;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.PanelSideBar;
import com.lhy.mest.client.dock.ScrollingUpgradeColumn;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.panel.CraftingPanel;
import com.lhy.mest.client.panel.CraftingTerminalPanel;
import com.lhy.mest.client.panel.InventoryPanel;
import com.lhy.mest.client.panel.MEListPanel;
import com.lhy.mest.client.panel.PatternAccessPanel;
import com.lhy.mest.client.panel.PatternCachePanel;
import com.lhy.mest.client.panel.PatternEncodingPanel;
import com.lhy.mest.client.panel.ProviderSelectPanel;
import com.lhy.mest.client.panel.TrashPanel;
import com.lhy.mest.client.panel.WirelessSettingsPanel;
import com.lhy.mest.compat.plus.PlusJeiHotkeys;

import net.neoforged.fml.ModList;
import com.lhy.mest.integration.MestRecipeTransferContext;
import com.extendedae_plus.client.screen.ProviderSelectScreen;
import com.extendedae_plus.network.CancelPendingPatternC2SPacket;
import com.lhy.mest.network.PatternProviderActionPacket;
import com.lhy.mest.network.ProviderPickerListPacket;
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
    private final List<SettingToggleButton<?>> meSettingButtons = new ArrayList<>();
    private final PanelSideBar meSideBar = new PanelSideBar();
    private final PanelSideBar moreSettingsBar = new PanelSideBar();
    private ExtraChrome upgradeColumn;
    private ExtraSlotColumn viewCellColumn;
    private final MestAddonUpgradeButtons addonUpgradeButtons = new MestAddonUpgradeButtons();
    private ToolbarIconButton viewCellsToggleBtn;
    private boolean viewCellsVisible = true;
    private ToolbarIconButton moreSettingsToggleBtn;
    private boolean moreSettingsVisible = false;
    private final List<String> moreSettingsButtonIds = new ArrayList<>();
    private int moreSettingsPressIndex = -1;
    private double moreSettingsPressY;
    private boolean moreSettingsDragging;
    private static final String MORE_LOCK = "lock_layout";
    private static final String MORE_UNDO = "undo_layout";
    private static final String MORE_EDIT = "edit_layout";
    private static final int MORE_SETTINGS_SHIFT = 3;
    private ToolbarIconButton lockLayoutBtn;
    private ToolbarIconButton undoLayoutBtn;
    private AETextField searchField;
    private TabButton craftingStatusBtn;
    private final List<ItemStack> currentViewCells = new ArrayList<>();
    private final Set<AEKey> craftableKeys = new HashSet<>();
    /**
     * True only while drawing slot icons inside the per-panel / extra-column render pass. Distinguishes that
     * pass (draw the icon) from the vanilla per-slot loop (skip those icons, since the chrome pass already
     * drew them).
     */
    private boolean drawingPanelSlots = false;
    private boolean hoverFrameReady;
    private int hoverCacheX = Integer.MIN_VALUE;
    private int hoverCacheY = Integer.MIN_VALUE;
    private ModulePanel hoverTop;
    private boolean hoverFloating;
    private ExtraChrome hoverExtra;
    private boolean hoverToolbar;
    private MEListPanel meListPanel;
    private CraftingPanel craftingPanel;
    private CraftingTerminalPanel craftingTerminalPanel;
    private PatternEncodingPanel patternEncodingPanel;
    private PatternAccessPanel patternAccessPanel;
    private PatternCachePanel patternCachePanel;
    private InventoryPanel inventoryPanel;
    private ProviderSelectPanel providerSelectPanel;
    private WirelessSettingsPanel wirelessSettingsPanel;
    private TrashPanel trashPanel;
    private boolean keepPendingOnRemove;

    public MEListPanel meListPanel() {
        return meListPanel;
    }

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

        this.searchField = widgets.addTextField("search");
        this.meListPanel.attachSearch(searchField);

        this.craftingStatusBtn = new TabButton(Icon.CRAFT_HAMMER,
                GuiText.CraftingStatus.text(), btn -> showCraftingStatus());
        this.meListPanel.attachCraftingStatus(craftingStatusBtn);

        var cm = getMenu().getConfigManager();
        addMeSideButton(new OpenGuideButton(btn -> openHelp()));
        addMeSideButton(new SettingToggleButton<>(Settings.SORT_BY,
                cm.getSetting(Settings.SORT_BY), Platform::isSortOrderAvailable, this::toggleServerSetting));
        addMeSideButton(new SettingToggleButton<>(Settings.VIEW_MODE,
                cm.getSetting(Settings.VIEW_MODE), this::toggleServerSetting));
        if (getMenu().canConfigureTypeFilter()) {
            addMeSideButton(KeyTypeSelectionButton.create(
                    this, getMenu().getHost(), GuiText.ConfigureVisibleTypes.text()));
        }
        addMeSideButton(new SettingToggleButton<>(Settings.SORT_DIRECTION,
                cm.getSetting(Settings.SORT_DIRECTION), this::toggleServerSetting));
        addMeSideButton(new ActionButton(ActionItems.TERMINAL_SETTINGS, this::showSettings));
        addMeSideButton(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        viewCellsToggleBtn = addMeSideButton(new ViewCellsToggleButton(
                () -> viewCellsVisible,
                b -> toggleViewCellsPanel()));
        moreSettingsToggleBtn = addMeSideButton(new ToolbarIconButton(
                Icon.COG,
                moreSettingsMessage(),
                b -> toggleMoreSettings()));
        addonUpgradeButtons.install(this, meSideBar);
        if (getMenu().isWUT()) {
            addMeSideButton(new CycleTerminalToolbarButton());
        }
    }

    private ToolbarIconButton addMeSideButton(ToolbarIconButton button) {
        addMeSideButton((Button) button);
        return button;
    }

    private void addMeSideButton(Button button) {
        meSideBar.add(button);
        if (button instanceof SettingToggleButton<?> settingButton
                && settingButton.getSetting() != Settings.TERMINAL_STYLE) {
            meSettingButtons.add(settingButton);
        }
    }

    private void showCraftingStatus() {
        ServerboundPacket message = SwitchGuisPacket.openSubMenu(CraftingStatusMenu.TYPE);
        PacketDistributor.sendToServer(message);
    }

    private void showSettings() {
        switchToScreen(new MestTerminalSettingsScreen(this));
    }

    public void switchToWirelessTerminalSettings() {
        if (wirelessSettingsPanel == null) {
            return;
        }
        dock.revealModule(wirelessSettingsPanel);
        wirelessSettingsPanel.reloadFromStack();
    }

    public void openTrash() {
        if (trashPanel == null) {
            return;
        }
        dock.revealModule(trashPanel);
        getMenu().openTrashMenu();
    }

    public void closeTrash() {
        getMenu().closeTrash();
        if (trashPanel != null && dock.isEffectivelyVisible(trashPanel)) {
            dock.hideModule(trashPanel);
        }
    }

    public void dismissWirelessSettings() {
        if (wirelessSettingsPanel == null) {
            return;
        }
        var policy = dock.policyFor(wirelessSettingsPanel);
        if (!policy.floating() || policy.pinned()) {
            return;
        }
        if (!dock.isEffectivelyVisible(wirelessSettingsPanel)) {
            return;
        }
        dock.hideModule(wirelessSettingsPanel);
    }

    private void syncUtilityHost() {
        if (craftingTerminalPanel == null || meListPanel == null) {
            return;
        }
        boolean onMeList = !dock.isEffectivelyVisible(craftingTerminalPanel)
                && dock.isEffectivelyVisible(meListPanel);
        craftingTerminalPanel.setHostUtilitiesOnMeList(onMeList);
    }

    private boolean isCraftingModuleVisible() {
        return craftingPanel != null && dock.isEffectivelyVisible(craftingPanel)
                || craftingTerminalPanel != null && dock.isEffectivelyVisible(craftingTerminalPanel);
    }

    private Component viewCellsMessage() {
        return viewCellsMessage(viewCellsVisible);
    }

    private static Component viewCellsMessage(boolean visible) {
        return Component.translatable(visible
                ? "gui.mesplicedterminal.view_cells.visible"
                : "gui.mesplicedterminal.view_cells.hidden");
    }

    private void toggleViewCellsPanel() {
        viewCellsVisible = !viewCellsVisible;
        dock.setViewCellsVisible(viewCellsVisible);
        if (viewCellsToggleBtn != null) {
            viewCellsToggleBtn.setMessage(viewCellsMessage());
        }
        attachExtraSlotColumns();
    }

    private Component moreSettingsMessage() {
        return Component.translatable(moreSettingsVisible
                ? "gui.mesplicedterminal.more_settings.hide"
                : "gui.mesplicedterminal.more_settings.show");
    }

    private void toggleMoreSettings() {
        moreSettingsVisible = !moreSettingsVisible;
        dock.setMoreSettingsVisible(moreSettingsVisible);
        if (moreSettingsToggleBtn != null) {
            moreSettingsToggleBtn.setMessage(moreSettingsMessage());
        }
        attachMeSideBar();
    }

    private void toggleTerminalStyle(SettingToggleButton<appeng.api.config.TerminalStyle> btn, boolean backwards) {
        appeng.api.config.TerminalStyle next = btn.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        btn.set(next);
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
        // We do not use AE2's fixed vertical toolbar; it is pinned to leftPos/topPos (0,0)
        // and would hang off the screen. The same buttons auto-attach to the spliced group.
        return false;
    }

    @Override
    protected <P extends AEBaseScreen<MESTMenu>> void onReturnFromSubScreen(
            appeng.client.gui.AESubScreen<MESTMenu, P> subScreen) {
        if (subScreen instanceof MestTerminalSettingsScreen && meListPanel != null) {
            meListPanel.tick(hasShiftDown());
        }
    }

    @Override
    protected void init() {
        // Keep the base dialog rect negligible (0x0 at 0,0) so JEI/EMI don't treat the whole screen as
        // occluded by the GUI (they assume [leftPos,topPos,imageWidth,imageHeight] is always occupied).
        // Slot coordinates are absolute screen coords set directly by panels, so they're unaffected.
        this.imageWidth = 0;
        this.imageHeight = 0;

        boolean firstOpen = dock.isEmpty();
        if (firstOpen) {
            List<ModulePanel> panels = new ArrayList<>();
            panels.add(meListPanel);
            craftingPanel = new CraftingPanel(getMenu());
            panels.add(craftingPanel);
            craftingTerminalPanel = new CraftingTerminalPanel(getMenu(), this);
            meListPanel.setUtilitySource(craftingTerminalPanel);
            panels.add(craftingTerminalPanel);
            patternEncodingPanel = new PatternEncodingPanel(getMenu());
            panels.add(patternEncodingPanel);
            patternAccessPanel = new PatternAccessPanel(style);
            panels.add(patternAccessPanel);
            patternCachePanel = new PatternCachePanel(getMenu());
            panels.add(patternCachePanel);
            inventoryPanel = new InventoryPanel(getMenu());
            panels.add(inventoryPanel);
            wirelessSettingsPanel = new WirelessSettingsPanel(getMenu(), style);
            panels.add(wirelessSettingsPanel);
            trashPanel = new TrashPanel(getMenu(), this);
            panels.add(trashPanel);
            if (ModList.get().isLoaded("extendedae_plus")) {
                providerSelectPanel = new ProviderSelectPanel(style);
                panels.add(providerSelectPanel);
            }
            dock.init(panels, this.width, this.height);
            viewCellsVisible = dock.viewCellsVisible();
            moreSettingsVisible = dock.moreSettingsVisible();
            if (viewCellsToggleBtn != null) {
                viewCellsToggleBtn.setMessage(viewCellsMessage());
            }
            if (moreSettingsToggleBtn != null) {
                moreSettingsToggleBtn.setMessage(moreSettingsMessage());
            }
            patternAccessPanel.applyRememberedButtons();
            if (providerSelectPanel != null) {
                getMenu().requestProviderList();
            }
        } else {
            dock.updateViewport(this.width, this.height);
        }

        super.init();

        this.leftPos = 0;
        this.topPos = 0;

        if (searchField != null) {
            removeWidget(searchField);
        }

        // The screen is registered with AE2's stock wireless_terminal style, which inherits text labels
        // (player inventory title, dialog title, item-count) anchored to a fixed GUI layout that doesn't
        // apply to our full-screen floating-panel UI. Hide them all so they don't bleed through.
        setTextHidden("player_inventory_title", true);
        setTextHidden("dialog_title", true);
        setTextHidden("entriesShown", true);

        syncUtilityHost();
        dock.layoutAll();
        dock.applyPendingCenter();
        if (trashPanel != null && dock.isEffectivelyVisible(trashPanel) && !getMenu().isTrashOpen()) {
            getMenu().openTrashMenu();
        }
        dock.relayoutAllSlots();
        if (patternAccessPanel != null) {
            patternAccessPanel.rebindSearchField(this);
        }
        if (providerSelectPanel != null) {
            providerSelectPanel.rebindSearchField(this);
        }
        if (upgradeColumn == null && getMenu().getHost() instanceof IUpgradeableObject upgradeable) {
            List<Slot> upgradeSlots = new ArrayList<>();
            upgradeSlots.addAll(getMenu().getSlots(AE2wtlibSlotSemantics.SINGULARITY));
            upgradeSlots.addAll(getMenu().getSlots(SlotSemantics.UPGRADE));
            upgradeColumn = new ScrollingUpgradeColumn(upgradeSlots, upgradeable);
        }
        if (viewCellColumn == null) {
            viewCellColumn = new ExtraSlotColumn(
                    getMenu().getSlots(SlotSemantics.VIEW_CELL),
                    () -> List.of(GuiText.TerminalViewCellsTooltip.text()));
        }
        attachMeSideBar();
        attachExtraSlotColumns();
        syncExternalModGuiMetrics();
        refreshRecipeTransferAvailability();
        selectVisibleRecipeTargetIfNeeded();
        if (firstOpen && meListPanel != null && searchField != null
                && AEConfig.instance().isAutoFocusSearch()
                && !AEConfig.instance().isUseExternalSearch()) {
            meListPanel.setSearchFocused(true);
        }
    }

    private void selectVisibleRecipeTargetIfNeeded() {
        var selected = MestRecipeTransferContext.targetFor(getMenu());
        if (selected == MestRecipeTransferContext.Target.PATTERN_ENCODING
                && !dock.isEffectivelyVisible(patternEncodingPanel)
                && isCraftingModuleVisible()) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.CRAFTING);
        } else if (selected == MestRecipeTransferContext.Target.CRAFTING
                && !isCraftingModuleVisible()
                && dock.isEffectivelyVisible(patternEncodingPanel)) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.PATTERN_ENCODING);
        }
    }

    private Component layoutLockMessage() {
        return Component.translatable(dock.isLayoutLocked()
                ? "gui.mesplicedterminal.unlock_layout"
                : "gui.mesplicedterminal.lock_layout");
    }

    private void refreshLayoutActions() {
        if (lockLayoutBtn != null) {
            lockLayoutBtn.setIcon(dock.isLayoutLocked() ? Icon.LOCKED : Icon.UNLOCKED);
            lockLayoutBtn.setMessage(layoutLockMessage());
        }
        if (undoLayoutBtn != null) {
            undoLayoutBtn.active = dock.canUndoLayout();
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        refreshLayoutActions();
        syncUtilityHost();
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel)) {
            meListPanel.tick(hasShiftDown());
            updateViewCellFilter();
        }
        if (patternEncodingPanel != null && dock.isEffectivelyVisible(patternEncodingPanel)) {
            patternEncodingPanel.tick();
        }
        if (craftingTerminalPanel != null && (dock.isEffectivelyVisible(craftingTerminalPanel)
                || craftingTerminalPanel.hostUtilitiesOnMeList())) {
            craftingTerminalPanel.tick();
        }
        if (wirelessSettingsPanel != null && dock.isEffectivelyVisible(wirelessSettingsPanel)) {
            wirelessSettingsPanel.reloadFromStack();
        }
        if (patternAccessPanel != null && dock.isEffectivelyVisible(patternAccessPanel)) {
            patternAccessPanel.tick();
        }
        if (patternCachePanel != null && dock.isEffectivelyVisible(patternCachePanel)) {
            patternCachePanel.layoutSlots();
        }
        attachMeSideBar();
        attachExtraSlotColumns();
        syncExternalModGuiMetrics();
        if (ModList.get().isLoaded("clientsort")) {
            com.lhy.mest.compat.ClientSortCompat.syncButtons(this);
        }
        refreshRecipeTransferAvailability();
    }

    private void refreshRecipeTransferAvailability() {
        MestRecipeTransferContext.updateAvailability(
                getMenu(),
                patternEncodingPanel != null && dock.isEffectivelyVisible(patternEncodingPanel),
                isCraftingModuleVisible());
    }

    private void attachMeSideBar() {
        DockRect group = dock.anchoredGroupBounds();
        if (group == null) {
            meSideBar.hide();
            moreSettingsBar.hide();
            return;
        }
        addonUpgradeButtons.update(this);
        rebuildMoreSettingsModules();
        meSideBar.layoutAgainst(group.x(), group.y(), true);
        for (Button button : moreSettingsBar.buttons()) {
            button.visible = true;
        }
        int moreLeft = (meSideBar.isVisible() ? meSideBar.bounds().getX() : group.x()) - MORE_SETTINGS_SHIFT;
        moreSettingsBar.layoutAgainst(moreLeft, group.y(), moreSettingsVisible);
    }

    private void rebuildMoreSettingsModules() {
        if (moreSettingsDragging) {
            return;
        }
        List<String> available = new ArrayList<>();
        available.add(MORE_LOCK);
        available.add(MORE_UNDO);
        available.add(MORE_EDIT);
        for (ModulePanel panel : dock.panels()) {
            if (panel != null && dock.policyFor(panel).showTerminalButton()) {
                available.add(panel.id());
            }
        }
        List<String> resolved = new ArrayList<>();
        for (String id : dock.moreSettingsOrder()) {
            if (available.contains(id) && !resolved.contains(id)) {
                resolved.add(id);
            }
        }
        for (String id : available) {
            if (!resolved.contains(id)) {
                resolved.add(id);
            }
        }
        if (resolved.equals(moreSettingsButtonIds) && !moreSettingsBar.buttons().isEmpty()) {
            return;
        }
        moreSettingsButtonIds.clear();
        moreSettingsButtonIds.addAll(resolved);
        moreSettingsBar.clearFrom(0);
        for (String id : resolved) {
            moreSettingsBar.add(createMoreSettingsButton(id));
        }
    }

    private Button createMoreSettingsButton(String id) {
        return switch (id) {
            case MORE_LOCK -> {
                lockLayoutBtn = new ToolbarIconButton(
                        dock.isLayoutLocked() ? Icon.LOCKED : Icon.UNLOCKED,
                        layoutLockMessage(),
                        b -> dock.toggleLayoutLocked());
                yield lockLayoutBtn;
            }
            case MORE_UNDO -> {
                undoLayoutBtn = new ToolbarIconButton(
                        Icon.BACK,
                        Component.translatable("gui.mesplicedterminal.undo_layout"),
                        b -> dock.undoLayout());
                undoLayoutBtn.active = dock.canUndoLayout();
                yield undoLayoutBtn;
            }
            case MORE_EDIT -> new ToolbarIconButton(
                    Icon.TERMINAL_STYLE_SMALL,
                    Component.translatable("gui.mesplicedterminal.edit_layout"),
                    b -> Minecraft.getInstance().setScreen(new MESTLayoutEditorScreen(this, dock)));
            default -> {
                ModulePanel panel = panelById(id);
                yield panel == null
                        ? new ToolbarIconButton(Icon.COG, Component.literal(id), b -> { })
                        : new ModuleToggleButton(panel);
            }
        };
    }

    private ModulePanel panelById(String id) {
        for (ModulePanel panel : dock.panels()) {
            if (panel != null && id.equals(panel.id())) {
                return panel;
            }
        }
        return null;
    }

    private void attachExtraSlotColumns() {
        DockRect group = dock.anchoredGroupBounds();
        if (group == null) {
            if (upgradeColumn != null) {
                upgradeColumn.hide();
            }
            if (viewCellColumn != null) {
                viewCellColumn.hide();
            }
            return;
        }
        int attachX = extraColumnAttachX(group);
        if (rightEdgeIsPatternCache(group)) {
            attachX += 2;
        } else {
            attachX -= 2;
        }
        int attachY = group.y();
        if (upgradeColumn instanceof ScrollingUpgradeColumn scrolling) {
            scrolling.setMaxRows(Math.max(2, (group.height() / 2 - 10) / 18));
        }
        if (upgradeColumn != null && upgradeColumn.hasSlots()) {
            upgradeColumn.layoutAgainst(attachX, attachY, true);
            attachX = upgradeColumn.nextColumnX();
        } else if (upgradeColumn != null) {
            upgradeColumn.hide();
        }
        if (viewCellColumn != null && viewCellColumn.hasSlots() && viewCellsVisible) {
            viewCellColumn.layoutAgainst(attachX, attachY, true);
        } else if (viewCellColumn != null) {
            viewCellColumn.hide();
        }
    }

    /**
     * Sit extra chrome to the right of hanging ME/pattern-access rails, same as the wireless
     * terminal well. Do not tuck into a right-edge panel that keeps an in-panel scroller.
     */
    private int extraColumnAttachX(DockRect group) {
        int hanging = 0;
        int rightEdgeInset = 0;
        for (ModulePanel panel : dock.panels()) {
            if (!dock.isEffectivelyVisible(panel) || dock.policyFor(panel).floating()) {
                continue;
            }
            if (panel.x >= group.right() || panel.x + panel.width <= group.x()
                    || panel.y >= group.bottom() || panel.y + panel.height <= group.y()) {
                continue;
            }
            if (panel.x + panel.width < group.right() - 2) {
                continue;
            }
            hanging = Math.max(hanging, panel.outsideHitWidth());
            rightEdgeInset = Math.max(rightEdgeInset, panel.contentRightInset);
        }
        if (hanging > 0) {
            return group.right() + hanging;
        }
        if (rightEdgeInset > 0) {
            return group.right() + 2;
        }
        return group.right() - 4;
    }

    private boolean rightEdgeIsPatternCache(DockRect group) {
        for (ModulePanel panel : dock.panels()) {
            if (!dock.isEffectivelyVisible(panel) || dock.policyFor(panel).floating()) {
                continue;
            }
            if (!"pattern_cache".equals(panel.id())) {
                continue;
            }
            if (panel.x + panel.width >= group.right() - 2
                    && panel.y < group.bottom()
                    && panel.y + panel.height > group.y()) {
                return true;
            }
        }
        return false;
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
            dock.renderBackground(
                    g, this.font, mouseX, mouseY, partialTicks, this::drawPanelSlot, this::renderAnchoredChrome);
        } finally {
            drawingPanelSlots = false;
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
        drawingPanelSlots = true;
        try {
            dock.renderForeground(g, this.font, mouseX, mouseY, 0, this::drawPanelSlot);
        } finally {
            drawingPanelSlots = false;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        hoverFrameReady = false;
        super.render(g, mouseX, mouseY, partialTicks);
        g.pose().pushPose();
        try {
            g.pose().translate(0.0F, 0.0F, 4500.0F);
            renderAttachedChromeTooltips(g, mouseX, mouseY);
        } finally {
            g.pose().popPose();
        }
        ItemStack carried = getMenu().getCarried();
        if (!carried.isEmpty()) {
            g.pose().pushPose();
            try {
                // Vanilla draws the carried stack at the default pose after slots, so extra chrome
                // (z≈layer*400) covers it. Redraw above that chrome but below the GUI far plane —
                // z=4500 clips the 3D item model and the icon looks cut off.
                g.pose().translate(0.0F, 0.0F, dock.cursorItemZ());
                g.renderItem(carried, mouseX - 8, mouseY - 8);
                g.renderItemDecorations(this.font, carried, mouseX - 8, mouseY - 8);
            } finally {
                g.pose().popPose();
            }
        }
    }

    private void renderAttachedChromeTooltips(GuiGraphics g, int mouseX, int mouseY) {
        ModulePanel top = dock.topPanelAt(mouseX, mouseY);
        if (top != null && dock.isFloatingWindow(top)) {
            return;
        }
        ExtraChrome extra = extraColumnAt(mouseX, mouseY);
        if (extra != null) {
            List<Component> lines = extra.chromeTooltipAt(mouseX, mouseY);
            if (!lines.isEmpty()) {
                drawTooltipWithHeader(g, mouseX, mouseY, lines);
                return;
            }
        }
        ITooltip moreSettings = moreSettingsBar.hoveredTooltip(mouseX, mouseY);
        if (moreSettings != null && moreSettings.isTooltipAreaVisible()
                && !moreSettings.getTooltipMessage().isEmpty()) {
            drawAeWidgetTooltip(g, mouseX, mouseY, moreSettings);
            return;
        }
        ITooltip sidebar = meSideBar.hoveredTooltip(mouseX, mouseY);
        if (sidebar != null && sidebar.isTooltipAreaVisible() && !sidebar.getTooltipMessage().isEmpty()) {
            drawAeWidgetTooltip(g, mouseX, mouseY, sidebar);
            return;
        }
        if (meListPanel != null) {
            ITooltip craftingStatus = meListPanel.hoveredCraftingStatusTooltip(mouseX, mouseY);
            if (craftingStatus != null && !craftingStatus.getTooltipMessage().isEmpty()) {
                drawAeWidgetTooltip(g, mouseX, mouseY, craftingStatus);
                return;
            }
        }
        if (patternEncodingPanel != null) {
            ITooltip encoding = patternEncodingPanel.hoveredTooltip(mouseX, mouseY);
            if (encoding != null && encoding.isTooltipAreaVisible() && !encoding.getTooltipMessage().isEmpty()) {
                drawAeWidgetTooltip(g, mouseX, mouseY, encoding);
            }
        }
    }

    /** Same path as {@code AEBaseScreen.renderTooltips}: split {@code title\\nbody} then header/body colors. */
    private void drawAeWidgetTooltip(GuiGraphics g, int mouseX, int mouseY, ITooltip tooltip) {
        drawTooltipWithHeader(g, mouseX, mouseY, new Tooltip(tooltip.getTooltipMessage()).getContent());
    }

    /**
     * Left toolbar and extra slot columns belong to the anchored terminal group, so they paint
     * with that group and stay under floating windows.
     */
    private void renderAnchoredChrome(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (meSideBar.isVisible()) {
            meSideBar.renderBackground(g);
            for (Button button : meSideBar.buttons()) {
                button.render(g, mouseX, mouseY, partialTicks);
            }
        }
        if (moreSettingsBar.isVisible()) {
            g.pose().pushPose();
            try {
                g.pose().translate(0.0F, 0.0F, 80.0F);
                moreSettingsBar.renderBackground(g);
                for (Button button : moreSettingsBar.buttons()) {
                    button.render(g, mouseX, mouseY, partialTicks);
                }
            } finally {
                g.pose().popPose();
            }
        }
        drawingPanelSlots = true;
        try {
            if (upgradeColumn != null && upgradeColumn.isVisible()) {
                upgradeColumn.renderBackground(g);
                upgradeColumn.renderSlots(g, this::drawPanelSlot);
            }
            if (viewCellColumn != null && viewCellColumn.isVisible()) {
                viewCellColumn.renderBackground(g);
                viewCellColumn.renderSlots(g, this::drawPanelSlot);
            }
        } finally {
            drawingPanelSlots = false;
        }
    }

    /**
     * ClientSort (and similar injectors) place buttons at {@code leftPos + imageWidth}.
     * Slots are already in screen space, so left/top stay 0 and imageWidth is the
     * player-inventory's right edge.
     */
    private void syncExternalModGuiMetrics() {
        leftPos = 0;
        topPos = 0;
        if (inventoryPanel != null && dock.isEffectivelyVisible(inventoryPanel)
                && inventoryPanel.width > 0 && inventoryPanel.height > 0) {
            imageWidth = Math.max(1, inventoryPanel.x + inventoryPanel.width);
            imageHeight = Math.max(1, inventoryPanel.y + inventoryPanel.height);
            return;
        }
        imageWidth = 1;
        imageHeight = 1;
    }

    /**
     * Tight core rectangle (ME list, else first visible panel) so JEI/EMI treat the
     * terminal as a small GUI and stair-step around the rest via exclusion zones.
     */
    public Rect2i recipeViewerBounds() {
        ModulePanel core = null;
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel)
                && meListPanel.width > 0 && meListPanel.height > 0) {
            core = meListPanel;
        } else {
            for (ModulePanel panel : dock.panels()) {
                if (panel != null && dock.isEffectivelyVisible(panel)
                        && !dock.policyFor(panel).floating()
                        && panel.width > 0 && panel.height > 0) {
                    core = panel;
                    break;
                }
            }
        }
        if (core != null) {
            return new Rect2i(Math.max(0, core.x), Math.max(0, core.y),
                    Math.max(1, core.width), Math.max(1, core.height));
        }
        return new Rect2i(0, 0, 1, 1);
    }

    /**
     * Full union of the spliced terminal plus attached chrome. Kept for callers that
     * need the occupied envelope rather than the JEI core rectangle.
     */
    public Rect2i recipeViewerEnvelope() {
        DockRect group = dock.anchoredGroupBounds();
        if (group == null) {
            for (DockRect bounds : dock.exclusionBounds()) {
                group = group == null ? bounds : group.union(bounds);
            }
        }
        if (group == null) {
            for (ModulePanel panel : dock.panels()) {
                if (panel == null || !panel.visible || panel.width <= 0 || panel.height <= 0) {
                    continue;
                }
                DockRect part = new DockRect(panel.x, panel.y, panel.width, panel.height);
                group = group == null ? part : group.union(part);
            }
        }
        if (group == null) {
            return new Rect2i(0, 0, 0, 0);
        }
        int left = group.x();
        int top = group.y();
        int right = group.right();
        int bottom = group.bottom();
        if (moreSettingsBar.isVisible()) {
            Rect2i rail = moreSettingsBar.bounds();
            left = Math.min(left, rail.getX() - 2);
            top = Math.min(top, rail.getY() - 1);
            bottom = Math.max(bottom, rail.getY() + rail.getHeight() + 3);
        }
        if (meSideBar.isVisible()) {
            Rect2i rail = meSideBar.bounds();
            left = Math.min(left, rail.getX() - 2);
            top = Math.min(top, rail.getY() - 1);
            bottom = Math.max(bottom, rail.getY() + rail.getHeight() + 3);
        }
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel)) {
            right = Math.max(right, meListPanel.x + meListPanel.width + meListPanel.outsideHitWidth());
            top = Math.min(top, meListPanel.y - meListPanel.outsideHitTop());
        }
        if (patternEncodingPanel != null && dock.isEffectivelyVisible(patternEncodingPanel)) {
            right = Math.max(right, patternEncodingPanel.x + patternEncodingPanel.width
                    + patternEncodingPanel.outsideHitWidth());
        }
        if (patternAccessPanel != null && dock.isEffectivelyVisible(patternAccessPanel)) {
            right = Math.max(right, patternAccessPanel.x + patternAccessPanel.width
                    + patternAccessPanel.outsideHitWidth());
        }
        if (upgradeColumn != null && upgradeColumn.isVisible()) {
            Rect2i extra = upgradeColumn.bounds();
            right = Math.max(right, extra.getX() + extra.getWidth());
            bottom = Math.max(bottom, extra.getY() + extra.getHeight());
        }
        if (viewCellColumn != null && viewCellColumn.isVisible()) {
            Rect2i extra = viewCellColumn.bounds();
            right = Math.max(right, extra.getX() + extra.getWidth());
            bottom = Math.max(bottom, extra.getY() + extra.getHeight());
        }
        return new Rect2i(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }
    @Override
    public List<Rect2i> getExclusionZones() {
        List<Rect2i> zones = new ArrayList<>();
        for (ModulePanel panel : dock.panels()) {
            if (panel == null || !dock.isEffectivelyVisible(panel)
                    || panel.width <= 0 || panel.height <= 0) {
                continue;
            }
            zones.add(new Rect2i(panel.x, panel.y, panel.width, panel.height));
        }
        if (moreSettingsBar.isVisible()) {
            Rect2i rail = moreSettingsBar.bounds();
            zones.add(new Rect2i(rail.getX() - 2, rail.getY() - 1, rail.getWidth() + 1, rail.getHeight() + 4));
        }
        if (meSideBar.isVisible()) {
            Rect2i rail = meSideBar.bounds();
            zones.add(new Rect2i(rail.getX() - 2, rail.getY() - 1, rail.getWidth() + 1, rail.getHeight() + 4));
        }
        if (meListPanel != null && dock.isEffectivelyVisible(meListPanel)) {
            if (meListPanel.outsideHitWidth() > 0) {
                zones.add(new Rect2i(
                        meListPanel.x + meListPanel.width,
                        meListPanel.y - 1,
                        meListPanel.outsideHitWidth(),
                        meListPanel.height + 1));
            }
            if (meListPanel.outsideHitTop() > 0) {
                zones.add(new Rect2i(
                        meListPanel.x + meListPanel.width + meListPanel.outsideHitWidth() - 24,
                        meListPanel.y - meListPanel.outsideHitTop(),
                        20,
                        meListPanel.outsideHitTop() + 20));
            }
        }
        if (patternEncodingPanel != null && dock.isEffectivelyVisible(patternEncodingPanel)
                && patternEncodingPanel.outsideHitWidth() > 0) {
            zones.add(new Rect2i(
                    patternEncodingPanel.x + patternEncodingPanel.width,
                    patternEncodingPanel.y,
                    patternEncodingPanel.outsideHitWidth(),
                    patternEncodingPanel.height));
        }
        if (patternAccessPanel != null && dock.isEffectivelyVisible(patternAccessPanel)
                && patternAccessPanel.outsideHitWidth() > 0) {
            zones.add(new Rect2i(
                    patternAccessPanel.x + patternAccessPanel.width,
                    patternAccessPanel.y - 1,
                    patternAccessPanel.outsideHitWidth(),
                    patternAccessPanel.height + 1));
        }
        addExtraColumnExclusion(zones, upgradeColumn);
        addExtraColumnExclusion(zones, viewCellColumn);
        return zones;
    }

    private static void addExtraColumnExclusion(List<Rect2i> zones, ExtraChrome column) {
        if (column != null && column.isVisible()) {
            Rect2i extra = column.bounds();
            zones.add(new Rect2i(extra.getX(), extra.getY(), extra.getWidth(), extra.getHeight()));
        }
    }

    /** Shared AE2 icon mapping for a module panel; also used by the layout editor sidebar. */
    public static void blitPanelIcon(GuiGraphics graphics, ModulePanel panel, int x, int y) {
        if ("trash".equals(panel.id())) {
            de.mari_023.ae2wtlib.api.gui.Icon.TRASH.getBlitter().dest(x, y).blit(graphics);
            return;
        }
        Icon icon = iconForPanel(panel);
        icon.getBlitter()
                .dest(x + (16 - icon.width) / 2, y + (16 - icon.height) / 2)
                .blit(graphics);
    }

    public static Icon iconForPanel(ModulePanel panel) {
        return switch (panel.id()) {
            case "me_list" -> Icon.VIEW_MODE_ALL;
            case "crafting" -> Icon.CRAFT_HAMMER;
            case "crafting_terminal" -> Icon.CRAFT_HAMMER;
            case "pattern_encoding" -> Icon.TAB_CRAFTING;
            case "pattern_access" -> Icon.PATTERN_ACCESS_SHOW;
            case "pattern_cache" -> Icon.BACKGROUND_ENCODED_PATTERN;
            case "provider_select" -> Icon.ARROW_UP;
            case "wireless_settings" -> Icon.COG;
            case "trash" -> Icon.BACKGROUND_TRASH;
            case "inventory" -> Icon.S_STORAGE;
            default -> Icon.COG;
        };
    }

    /**
     * View-cell show/hide uses the WCWT icon pair already copied into
     * {@code pattern_cache_states.png} (u=144 shown, u=160 hidden, v=32).
     */
    private static final class ViewCellsToggleButton extends ToolbarIconButton {
        private static final ResourceLocation STATES = ResourceLocation.fromNamespaceAndPath(
                MESplicedterminal.MODID, "textures/guis/pattern_cache_states.png");
        private static final int ICON_U_VISIBLE = 144;
        private static final int ICON_U_HIDDEN = 160;
        private static final int ICON_V = 32;
        private static final int ICON_SIZE = 16;

        private final BooleanSupplier viewCellsVisible;

        ViewCellsToggleButton(BooleanSupplier viewCellsVisible, OnPress onPress) {
            super(Icon.BACKGROUND_VIEW_CELL, Component.empty(), onPress);
            this.viewCellsVisible = viewCellsVisible;
            setMessage(viewCellsMessage(viewCellsVisible.getAsBoolean()));
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            if (!visible) {
                return;
            }
            int yOffset = isHovered() ? 1 : 0;
            Icon bgIcon = isHovered()
                    ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                    : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
            bgIcon.getBlitter()
                    .dest(getX() - 1, getY() + yOffset, 18, 20)
                    .zOffset(2)
                    .blit(guiGraphics);
            int iconU = viewCellsVisible.getAsBoolean() ? ICON_U_VISIBLE : ICON_U_HIDDEN;
            Blitter.texture(STATES, 256, 256)
                    .src(iconU, ICON_V, ICON_SIZE, ICON_SIZE)
                    .dest(getX(), getY() + 1 + yOffset)
                    .zOffset(3)
                    .blit(guiGraphics);
        }
    }

    private static class ToolbarIconButton extends IconButton {
        private Icon icon;

        ToolbarIconButton(Icon icon, Component tooltip, OnPress onPress) {
            super(onPress);
            this.icon = icon;
            setMessage(tooltip);
        }

        void setIcon(Icon icon) {
            this.icon = icon;
        }

        @Override
        protected Icon getIcon() {
            return icon;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            if (!visible) {
                return;
            }
            int yOffset = isHovered() ? 1 : 0;
            if (!isDisableBackground()) {
                Icon bgIcon = isHovered()
                        ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                        : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
                bgIcon.getBlitter()
                        .dest(getX() - 1, getY() + yOffset, 18, 20)
                        .zOffset(2)
                        .blit(guiGraphics);
            }
            int ix = getX() + (16 - icon.width) / 2;
            int iy = getY() + 1 + yOffset + (16 - icon.height) / 2;
            var blitter = icon.getBlitter();
            if (!active) {
                blitter.opacity(0.5f);
            }
            blitter.dest(ix, iy).zOffset(3).blit(guiGraphics);
        }
    }

    private final class CycleTerminalToolbarButton extends ToolbarIconButton {
        CycleTerminalToolbarButton() {
            super(Icon.CRAFT_HAMMER, TextConstants.TERMINAL_EMPTY, b -> { });
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            WTDefinition terminal = WTDefinition.ofOrNull(getHost().getItemStack());
            setMessage(terminal == null ? TextConstants.TERMINAL_EMPTY : TextConstants.currentTerminal(terminal));
            if (!visible) {
                return;
            }
            int yOffset = isHovered() ? 1 : 0;
            Icon bgIcon = isHovered()
                    ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                    : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
            bgIcon.getBlitter()
                    .dest(getX() - 1, getY() + yOffset, 18, 20)
                    .zOffset(2)
                    .blit(guiGraphics);
            de.mari_023.ae2wtlib.api.gui.Icon icon = terminal == null
                    ? de.mari_023.ae2wtlib.api.gui.Icon.CRAFTING
                    : terminal.icon();
            icon.getBlitter()
                    .dest(getX(), getY() + 1 + yOffset)
                    .zOffset(3)
                    .blit(guiGraphics);
        }
    }

    private final class ModuleToggleButton extends ToolbarIconButton {
        private final ModulePanel panel;

        ModuleToggleButton(ModulePanel panel) {
            super(iconForPanel(panel), moduleToggleMessage(panel, dock.isEffectivelyVisible(panel)),
                    button -> toggleModule(panel));
            this.panel = panel;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            boolean shown = dock.isEffectivelyVisible(panel);
            setMessage(moduleToggleMessage(panel, shown));
            if (!visible) {
                return;
            }
            int yOffset = isHovered() ? 1 : 0;
            Icon bgIcon = isHovered()
                    ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                    : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
            bgIcon.getBlitter()
                    .dest(getX() - 1, getY() + yOffset, 18, 20)
                    .zOffset(2)
                    .blit(guiGraphics);
            int ix = getX();
            int iy = getY() + 1 + yOffset;
            if ("trash".equals(panel.id())) {
                var blitter = de.mari_023.ae2wtlib.api.gui.Icon.TRASH.getBlitter();
                if (!shown) {
                    blitter.opacity(0.4f);
                }
                blitter.dest(ix, iy).zOffset(3).blit(guiGraphics);
                return;
            }
            Icon icon = getIcon();
            var blitter = icon.getBlitter();
            if (!shown) {
                blitter.opacity(0.4f);
            }
            blitter.dest(ix + (16 - icon.width) / 2, iy + (16 - icon.height) / 2).zOffset(3).blit(guiGraphics);
        }
    }

    private void toggleModule(ModulePanel panel) {
        var policy = dock.policyFor(panel);
        dock.setModulePolicy(panel, policy.withVisible(!policy.visible()));
        dock.save();
        attachMeSideBar();
        attachExtraSlotColumns();
    }

    private static Component moduleToggleMessage(ModulePanel panel, boolean visible) {
        return Component.translatable(visible
                        ? "gui.mesplicedterminal.module_toggle.hide"
                        : "gui.mesplicedterminal.module_toggle.show")
                .append(": ")
                .append(panel.title());
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
        if (!drawingPanelSlots && (s instanceof RepoSlot || extraColumnOwns(s) || dock.ownsSlot(s))) {
            // Vanilla per-slot loop: chrome pass already drew this slot. Skip.
            return;
        }
        if (s instanceof RepoSlot repoSlot) {
            if (meListPanel != null) {
                meListPanel.renderRepoSlot(g, this.font, repoSlot);
            }
            return;
        }
        if (s.x <= -1000 || s.y <= -1000) {
            return;
        }
        if (s instanceof ArmorSlot armorSlot && armorSlot.getItem().isEmpty() && armorSlot.isSlotEnabled()) {
            armorSlot.icon().getBlitter()
                    .dest(armorSlot.x, armorSlot.y)
                    .opacity(armorSlot.getOpacityOfIcon())
                    .blit(g);
        }
        super.renderSlot(g, s);
        if (getMenu().isPatternEncodingInputSlot(s)) {
            GenericStack stack = GenericStack.fromItemStack(s.getItem());
            var repo = getMenu().getClientRepo();
            if (stack != null && repo != null && isCraftable(stack.what())) {
                StackSizeRenderer.renderSizeLabel(g, font, ModulePanel.slotScreenX(s) - 11,
                        ModulePanel.slotScreenY(s) - 11, "+", false);
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
        ensureHoverFrame(mx, my);
        if (hoverTop == null || !hoverFloating) {
            if (hoverExtra != null) {
                return hoverExtra.ownsSlot(slot) && super.isHovering(slot, mx, my);
            }
            if (hoverToolbar) {
                return false;
            }
        }
        if (hoverTop != null) {
            return dock.panelForSlot(slot) == hoverTop && super.isHovering(slot, mx, my);
        }
        return super.isHovering(slot, mx, my);
    }

    @Override
    protected void renderSlotHighlight(GuiGraphics g, Slot slot, int mouseX, int mouseY, float partialTick) {
        if (extraColumnOwns(slot)) {
            g.pose().pushPose();
            try {
                g.pose().translate(0.0F, 0.0F, dock.anchoredChromeZ() + 200.0F);
                super.renderSlotHighlight(g, slot, mouseX, mouseY, partialTick);
            } finally {
                g.pose().popPose();
            }
            return;
        }
        ModulePanel panel = dock.panelForSlot(slot);
        if (panel == null) {
            super.renderSlotHighlight(g, slot, mouseX, mouseY, partialTick);
            return;
        }
        g.pose().pushPose();
        try {
            g.pose().translate(0.0F, 0.0F, dock.slotHighlightZ(panel));
            super.renderSlotHighlight(g, slot, mouseX, mouseY, partialTick);
        } finally {
            g.pose().popPose();
        }
    }

    private Slot panelSlotAt(ModulePanel panel, double mouseX, double mouseY) {
        if (panel == null || dock.topLeafAt(mouseX, mouseY) != panel) {
            return null;
        }
        for (Slot slot : getMenu().slots) {
            if (panel.ownsSlot(slot) && slot.isActive()
                    && mouseX >= ModulePanel.slotScreenX(slot) && mouseX < ModulePanel.slotScreenX(slot) + 16
                    && mouseY >= ModulePanel.slotScreenY(slot) && mouseY < ModulePanel.slotScreenY(slot) + 16) {
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
            if (button == 0 && scrollbarPressedAt(mx, my)) {
                return true;
            }
            if (mouseClickedSideBar(mx, my, button)) {
                return true;
            }
            if (mouseClickedExtraChrome(mx, my, button)) {
                return true;
            }
            boolean handled = super.mouseClicked(mx, my, button);
            return handled || extraColumnAt(mx, my) != null
                    || meSideBar.isMouseOver(mx, my)
                    || moreSettingsBar.isMouseOver(mx, my);
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

        if (target != providerSelectPanel) {
            dismissProviderPicker(true);
        }
        if (target != wirelessSettingsPanel) {
            dismissWirelessSettings();
        }

        if (target == craftingPanel || target == craftingTerminalPanel) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.CRAFTING);
        } else if (target == patternEncodingPanel) {
            MestRecipeTransferContext.select(
                    getMenu(), MestRecipeTransferContext.Target.PATTERN_ENCODING);
        }

        if (button == 0 && scrollbarPressedAt(mx, my)) {
            return true;
        }
        if (target != null && !target.inResizeHandle(mx, my) && target.mouseClicked(mx, my, button)) {
            return true;
        }
        if (target != meListPanel && meListPanel != null) {
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

        if (dock.mouseClicked(mx, my, button)) {
            return true;
        }

        boolean handled = super.mouseClicked(mx, my, button);
        // Empty content in the topmost panel still blocks panels and widgets below it.
        return handled || dock.topPanelAt(mx, my) != null;
    }

    @Override
    protected boolean hasClickedOutside(double mx, double my, int guiLeft, int guiTop, int button) {
        if (dock.topPanelAt(mx, my) != null || isMouseOverToolbarWidget(mx, my)) {
            return false;
        }
        return super.hasClickedOutside(mx, my, guiLeft, guiTop, button);
    }

    private void ensureHoverFrame(double mx, double my) {
        int ix = (int) mx;
        int iy = (int) my;
        if (hoverFrameReady && ix == hoverCacheX && iy == hoverCacheY) {
            return;
        }
        hoverCacheX = ix;
        hoverCacheY = iy;
        hoverTop = dock.topPanelAt(mx, my);
        hoverFloating = dock.isFloatingWindow(hoverTop);
        hoverExtra = extraColumnAt(mx, my);
        hoverToolbar = (hoverTop == null || !hoverFloating)
                && (meSideBar.isMouseOver(mx, my)
                        || moreSettingsBar.isMouseOver(mx, my)
                        || hoverExtra != null);
        hoverFrameReady = true;
    }

    private boolean isMouseOverToolbarWidget(double mx, double my) {
        ensureHoverFrame(mx, my);
        return hoverToolbar;
    }

    private boolean mouseClickedSideBar(double mx, double my, int button) {
        if (button == 0 && moreSettingsBar.isVisible()) {
            List<Button> buttons = moreSettingsBar.buttons();
            for (int index = 0; index < buttons.size(); index++) {
                Button widget = buttons.get(index);
                if (widget.visible && widget.isMouseOver(mx, my)) {
                    moreSettingsPressIndex = index;
                    moreSettingsPressY = my;
                    moreSettingsDragging = false;
                    return true;
                }
            }
        }
        for (Button widget : meSideBar.buttons()) {
            if (!widget.visible || !widget.isMouseOver(mx, my)) {
                continue;
            }
            if (widget instanceof CycleTerminalToolbarButton) {
                storeState();
                AE2wtlibAPI.cycleTerminal(button == 1);
                return true;
            }
            if (widget.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    private void reorderMoreSettings(double mouseY) {
        List<Button> buttons = moreSettingsBar.buttons();
        if (moreSettingsPressIndex < 0 || moreSettingsPressIndex >= buttons.size()) {
            return;
        }
        int target = moreSettingsPressIndex;
        for (int index = 0; index < buttons.size(); index++) {
            Button widget = buttons.get(index);
            if (!widget.visible) {
                continue;
            }
            int mid = widget.getY() + widget.getHeight() / 2;
            if (mouseY < mid) {
                target = index;
                break;
            }
            target = index;
        }
        if (target == moreSettingsPressIndex) {
            return;
        }
        Button moved = buttons.remove(moreSettingsPressIndex);
        String id = moreSettingsButtonIds.remove(moreSettingsPressIndex);
        buttons.add(target, moved);
        moreSettingsButtonIds.add(target, id);
        moreSettingsPressIndex = target;
        attachMeSideBar();
    }

    private boolean mouseReleasedSideBar(double mx, double my, int button) {
        boolean handled = false;
        for (Button widget : moreSettingsBar.buttons()) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        for (Button widget : meSideBar.buttons()) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        return handled;
    }

    private boolean scrollbarPressedAt(double mx, double my) {
        ModulePanel target = dock.topLeafAt(mx, my);
        if (target != null && target.scrollbarPressed(mx, my)) {
            return true;
        }
        for (ModulePanel panel : dock.panels()) {
            if (panel != target && panel.scrollbarPressed(mx, my)) {
                return true;
            }
        }
        return false;
    }

    private ExtraChrome extraColumnAt(double mx, double my) {
        List<ExtraChrome> columns = extraColumns();
        for (int i = columns.size() - 1; i >= 0; i--) {
            ExtraChrome column = columns.get(i);
            if (column.contains(mx, my)) {
                return column;
            }
        }
        return null;
    }

    private boolean extraColumnOwns(Slot slot) {
        for (ExtraChrome column : extraColumns()) {
            if (column.ownsSlot(slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean mouseClickedExtraChrome(double mx, double my, int button) {
        ExtraChrome column = extraColumnAt(mx, my);
        return column != null && column.mouseClicked(mx, my, button);
    }

    private List<ExtraChrome> extraColumns() {
        List<ExtraChrome> columns = new ArrayList<>(2);
        if (upgradeColumn != null) {
            columns.add(upgradeColumn);
        }
        if (viewCellColumn != null) {
            columns.add(viewCellColumn);
        }
        return columns;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (button == 0 && moreSettingsPressIndex >= 0) {
            if (!moreSettingsDragging && Math.abs(my - moreSettingsPressY) > 4) {
                moreSettingsDragging = true;
            }
            if (moreSettingsDragging) {
                reorderMoreSettings(my);
                return true;
            }
        }
        for (ExtraChrome column : extraColumns()) {
            if (column.mouseDragged(mx, my)) {
                return true;
            }
        }
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
        if (button == 0 && moreSettingsPressIndex >= 0) {
            boolean dragged = moreSettingsDragging;
            int index = moreSettingsPressIndex;
            moreSettingsPressIndex = -1;
            moreSettingsDragging = false;
            if (dragged) {
                dock.setMoreSettingsOrder(List.copyOf(moreSettingsButtonIds));
                attachMeSideBar();
                return true;
            }
            List<Button> buttons = moreSettingsBar.buttons();
            if (index >= 0 && index < buttons.size()) {
                Button widget = buttons.get(index);
                widget.mouseClicked(mx, my, button);
                widget.mouseReleased(mx, my, button);
            }
            return true;
        }
        boolean scrollbarDrag = false;
        for (ModulePanel panel : dock.panels()) {
            scrollbarDrag |= panel.scrollbarDragging();
            panel.scrollbarReleased();
        }
        boolean extraDrag = false;
        for (ExtraChrome column : extraColumns()) {
            extraDrag |= column.mouseReleased(mx, my, button);
        }
        mouseReleasedSideBar(mx, my, button);
        if (scrollbarDrag || extraDrag) {
            return true;
        }
        if (dock.mouseReleased(mx, my, button)) {
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        ExtraChrome extra = extraColumnAt(mx, my);
        if (extra != null && extra.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        if (patternAccessPanel != null && patternAccessPanel.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        if (patternCachePanel != null && patternCachePanel.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        if (providerSelectPanel != null && providerSelectPanel.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
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
        if (target != null && target != patternAccessPanel && target != patternCachePanel
                && target != providerSelectPanel
                && target.mouseScrolled(mx, my, scrollY)) {
            return true;
        }
        return dock.topPanelAt(mx, my) != null || super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    public void updatePatternProviders(List<Entry> entries) {
        if (patternAccessPanel != null) {
            patternAccessPanel.setProviders(entries);
        }
    }

    public void applyProviderPickerList(ProviderPickerListPacket packet) {
        if (providerSelectPanel == null) {
            return;
        }
        boolean floating = dock.policyFor(providerSelectPanel).floating();
        if (packet.applyPreset() && !floating) {
            keepPendingOnRemove = true;
            minecraft.setScreen(new ProviderSelectScreen(
                    this, packet.ids(), packet.names(), packet.emptySlots()));
            return;
        }
        boolean autoUploaded = providerSelectPanel.applyList(packet);
        if (packet.applyPreset() && floating && !autoUploaded) {
            dock.revealModule(providerSelectPanel);
        } else if (autoUploaded) {
            dismissProviderPicker(false);
        }
    }

    public void dismissProviderPicker(boolean cancelPending) {
        if (providerSelectPanel == null) {
            return;
        }
        var policy = dock.policyFor(providerSelectPanel);
        if (!policy.floating() || policy.pinned()) {
            return;
        }
        if (!dock.isEffectivelyVisible(providerSelectPanel)) {
            return;
        }
        dock.hideModule(providerSelectPanel);
        if (cancelPending && ModList.get().isLoaded("extendedae_plus")) {
            PacketDistributor.sendToServer(CancelPendingPatternC2SPacket.INSTANCE);
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
        if (clickType == ClickType.QUICK_MOVE
                && patternAccessReceivesShiftClick()
                && slot != null
                && PatternDetailsHelper.isEncodedPattern(slot.getItem())) {
            Entry target = patternAccessPanel.firstDisplayedProvider();
            if (target != null) {
                PacketDistributor.sendToServer(new PatternProviderActionPacket(
                        getMenu().containerId,
                        target.epoch(),
                        target.providerId(),
                        target.revision(),
                        slot.index,
                        PatternProviderActionPacket.Action.INSERT_INTO_PROVIDER));
                return;
            }
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    private boolean patternAccessReceivesShiftClick() {
        return patternAccessPanel != null && patternAccessPanel.visible
                && (meListPanel == null || !meListPanel.visible);
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
        g.pose().pushPose();
        try {
            g.pose().translate(0.0F, 0.0F, 4500.0F);
            renderTooltipAtOverlay(g, x, y);
        } finally {
            g.pose().popPose();
        }
    }

    private void renderTooltipAtOverlay(GuiGraphics g, int x, int y) {
        ModulePanel target = dock.topLeafAt(x, y);
        if (target == providerSelectPanel && providerSelectPanel != null) {
            ITooltip picker = providerSelectPanel.hoveredTooltip(x, y);
            if (picker != null && picker.isTooltipAreaVisible() && !picker.getTooltipMessage().isEmpty()) {
                drawAeWidgetTooltip(g, x, y, picker);
                return;
            }
        }
        if (target == patternEncodingPanel && patternEncodingPanel != null) {
            ITooltip encoding = patternEncodingPanel.hoveredTooltip(x, y);
            if (encoding != null && encoding.isTooltipAreaVisible() && !encoding.getTooltipMessage().isEmpty()) {
                drawAeWidgetTooltip(g, x, y, encoding);
                return;
            }
        }
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

    public void attachPatternSearchField(AETextField field) {
        if (field != null && !children().contains(field)) {
            addWidget(field);
        }
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (meListPanel != null && meListPanel.searchCharTyped(character, modifiers)) {
            return true;
        }
        if (patternAccessPanel != null && getFocused() == patternAccessPanel.searchField()) {
            return super.charTyped(character, modifiers);
        }
        if (patternAccessPanel != null && patternAccessPanel.searchCharTyped(character, modifiers)) {
            return true;
        }
        if (providerSelectPanel != null && getFocused() == providerSelectPanel.searchField()) {
            return super.charTyped(character, modifiers);
        }
        if (providerSelectPanel != null && getFocused() == providerSelectPanel.mappingField()) {
            return super.charTyped(character, modifiers);
        }
        if (providerSelectPanel != null && providerSelectPanel.searchCharTyped(character, modifiers)) {
            return true;
        }
        return super.charTyped(character, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean meSearch = meListPanel != null && meListPanel.isSearchFocused();
        boolean patSearch = patternAccessPanel != null
                && (getFocused() == patternAccessPanel.searchField() || patternAccessPanel.isSearchFocused());
        boolean pickerSearch = providerSelectPanel != null
                && (getFocused() == providerSelectPanel.searchField()
                || getFocused() == providerSelectPanel.mappingField()
                || providerSelectPanel.isSearchFocused());
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (meSearch || patSearch || pickerSearch)) {
            if (meSearch) {
                meListPanel.setSearchFocused(false);
            }
            if (patSearch) {
                patternAccessPanel.setSearchFocused(false);
            }
            if (pickerSearch) {
                providerSelectPanel.setSearchFocused(false);
            }
            setFocused(null);
        } else if (meSearch) {
            if (meListPanel.searchKeyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                meListPanel.setSearchFocused(false);
                return true;
            }
            return true;
        } else if (patSearch) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                patternAccessPanel.setSearchFocused(false);
                setFocused(null);
                return true;
            }
            if (getFocused() == patternAccessPanel.searchField()) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (patternAccessPanel.searchKeyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        } else if (pickerSearch) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                if (providerSelectPanel.searchKeyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
                providerSelectPanel.setSearchFocused(false);
                setFocused(null);
                return true;
            }
            if (getFocused() == providerSelectPanel.searchField()
                    || getFocused() == providerSelectPanel.mappingField()) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (providerSelectPanel.searchKeyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return true;
        }
        if (ModList.get().isLoaded("extendedae_plus")
                && ModList.get().isLoaded("jei")
                && PlusJeiHotkeys.fillSearchFromHoveredIngredient(meListPanel, keyCode, scanCode)) {
            return true;
        }
        if (patternAccessPanel != null
                && minecraft.options.keyDrop.matches(keyCode, scanCode)
                && patternAccessPanel.dropHovered(
                        minecraft.mouseHandler.xpos()
                                * this.width / Math.max(1, minecraft.getWindow().getScreenWidth()),
                        minecraft.mouseHandler.ypos()
                                * this.height / Math.max(1, minecraft.getWindow().getScreenHeight()),
                        hasControlDown())) {
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (!handled) {
            return checkForTerminalKeys(keyCode, scanCode);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        super.mouseMoved(mx, my);
        if (patternAccessPanel != null) {
            patternAccessPanel.shiftHoverExtract(mx, my);
        }
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

    public void saveUiPreferences() {
        dock.saveUiPreferences();
    }

    @Override
    public void storeState() {
        dock.save();
        dock.saveUiPreferences();
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
        if (meListPanel != null) {
            meListPanel.rememberSearch();
        }
        clearRecipeTransferContext();
        super.onClose();
    }

    @Override
    public void removed() {
        closePatternAccessSubscription();
        if (ModList.get().isLoaded("extendedae_plus") && !keepPendingOnRemove) {
            PacketDistributor.sendToServer(CancelPendingPatternC2SPacket.INSTANCE);
        }
        keepPendingOnRemove = false;
        dock.save();
        dock.saveUiPreferences();
        super.removed();
    }
}
