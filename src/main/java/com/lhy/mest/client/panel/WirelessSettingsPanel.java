package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AECheckbox;
import appeng.menu.locator.ItemMenuHostLocator;

import de.mari_023.ae2wtlib.AE2wtlibAdditionalComponents;
import de.mari_023.ae2wtlib.api.AE2wtlibComponents;
import de.mari_023.ae2wtlib.api.TextConstants;
import de.mari_023.ae2wtlib.api.terminal.WUTHandler;
import de.mari_023.ae2wtlib.networking.TerminalSettingsPacket;
import de.mari_023.ae2wtlib.wct.magnet_card.MagnetHandler;
import de.mari_023.ae2wtlib.wct.magnet_card.MagnetMode;

import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Wireless-terminal settings as a dock module. Saves through wtlib's
 * {@link TerminalSettingsPacket} and {@link MagnetHandler}.
 */
public class WirelessSettingsPanel extends ModulePanel {
    private static final int ROW = 16;
    private static final int HEADER = 12;
    private static final int CHECK_H = 14;
    private static final int MIN_W = 176;

    private final MESTMenu menu;
    private final ScreenStyle style;
    private final DockManager dock;
    private final AECheckbox pickBlock;
    private final AECheckbox craftIfMissing;
    private final AECheckbox restock;
    private final AECheckbox pullItems;
    private final AECheckbox magnet;
    private final AECheckbox pickupToME;
    private final AECheckbox toolkitBar;
    private final List<AbstractWidget> widgets = new ArrayList<>();

    public WirelessSettingsPanel(MESTMenu menu, ScreenStyle style, DockManager dock) {
        this.menu = menu;
        this.style = style;
        this.dock = dock;
        pickBlock = checkbox(TextConstants.PICK_BLOCK);
        craftIfMissing = checkbox(TextConstants.CRAFT_IF_MISSING);
        restock = checkbox(TextConstants.RESTOCK);
        pullItems = checkbox(Component.translatable("gui.mesplicedterminal.wireless_settings.pull_items"));
        magnet = checkbox(TextConstants.MAGNET);
        pickupToME = checkbox(TextConstants.PICKUP_TO_ME);
        toolkitBar = checkbox(
                Component.translatable("gui.mesplicedterminal.wireless_settings.toolkit_bar"));
        pickBlock.setChangeListener(this::onPickBlockChanged);
        craftIfMissing.setChangeListener(this::save);
        restock.setChangeListener(this::save);
        pullItems.setChangeListener(this::savePullItems);
        magnet.setChangeListener(this::save);
        pickupToME.setChangeListener(this::save);
        toolkitBar.setChangeListener(this::save);
        widgets.add(pickBlock);
        widgets.add(craftIfMissing);
        widgets.add(restock);
        widgets.add(pullItems);
        widgets.add(magnet);
        widgets.add(pickupToME);
        widgets.add(toolkitBar);
        reloadFromStack();
    }

    private AECheckbox checkbox(Component label) {
        return new AECheckbox(0, 0, MIN_W - 2 * CONTENT_PADDING, CHECK_H, style, label);
    }

    @Override
    public String id() {
        return "wireless_settings";
    }

    @Override
    public Component title() {
        return TextConstants.TERMINAL_SETTINGS;
    }

    @Override
    public int defaultWidth() {
        return MIN_W;
    }

    @Override
    public int defaultHeight() {
        return minHeight();
    }

    @Override
    public int minWidth() {
        return MIN_W;
    }

    @Override
    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + neededContentHeight();
    }

    @Override
    public boolean expandsVertically() {
        return true;
    }

    private static int neededContentHeight() {
        return ROW * 4 + HEADER + ROW * 3 + 4;
    }

    public void reloadFromStack() {
        ItemStack stack = menu.getMestHost().getItemStack();
        pickBlock.setSelected(stack.getOrDefault(AE2wtlibComponents.PICK_BLOCK, false));
        craftIfMissing.setSelected(stack.getOrDefault(AE2wtlibComponents.CRAFT_IF_MISSING, false));
        craftIfMissing.active = pickBlock.isSelected();
        restock.setSelected(stack.getOrDefault(AE2wtlibComponents.RESTOCK, false));
        pullItems.setSelected(dock.pullItemsRecipeButton());
        MagnetMode mode = stack.getOrDefault(AE2wtlibAdditionalComponents.MAGNET_SETTINGS, MagnetMode.OFF);
        magnet.setSelected(mode.magnet());
        pickupToME.setSelected(mode.pickupToME());
        boolean hasCard = MagnetHandler.getMagnetMode(stack) != MagnetMode.NO_CARD
                && MagnetHandler.getMagnetMode(stack) != MagnetMode.INVALID;
        magnet.active = hasCard;
        pickupToME.active = hasCard;
        toolkitBar.setSelected(stack.getOrDefault(ModComponents.TOOLKIT_BAR.get(), false));
    }

    private void onPickBlockChanged() {
        craftIfMissing.active = pickBlock.isSelected();
        save();
    }

    private void savePullItems() {
        dock.setPullItemsRecipeButton(pullItems.isSelected());
    }

    private void save() {
        ItemStack stack = menu.getMestHost().getItemStack();
        stack.set(AE2wtlibComponents.PICK_BLOCK, pickBlock.isSelected());
        stack.set(AE2wtlibComponents.CRAFT_IF_MISSING, craftIfMissing.isSelected());
        stack.set(AE2wtlibComponents.RESTOCK, restock.isSelected());
        MagnetMode mode = stack.getOrDefault(AE2wtlibAdditionalComponents.MAGNET_SETTINGS, MagnetMode.OFF);
        MagnetMode next = mode.set(magnet.isSelected(), pickupToME.isSelected());
        MagnetHandler.saveMagnetMode(stack, next);
        stack.set(AE2wtlibAdditionalComponents.MAGNET_SETTINGS, next);
        stack.set(ModComponents.TOOLKIT_BAR.get(), toolkitBar.isSelected());

        ItemMenuHostLocator locator = menu.getMestHost().getLocator();
        if (locator == null) {
            return;
        }
        PacketDistributor.sendToServer(new TerminalSettingsPacket(
                locator,
                pickBlock.isSelected(),
                restock.isSelected(),
                magnet.isSelected(),
                pickupToME.isSelected(),
                craftIfMissing.isSelected()));
        if (menu.getPlayer() instanceof ServerPlayer serverPlayer) {
            WUTHandler.updateClientTerminal(serverPlayer, locator, stack);
        }
    }

    @Override
    public void layoutSlots() {
        boolean show = visible;
        int left = contentLeft();
        int top = contentTop();
        int width = Math.max(80, contentWidth());
        int y = top;
        place(pickBlock, show, left, y, width);
        y += ROW;
        place(craftIfMissing, show, left, y, width);
        y += ROW;
        place(restock, show, left, y, width);
        y += ROW;
        place(pullItems, show, left, y, width);
        y += ROW + HEADER;
        place(magnet, show, left, y, width);
        y += ROW;
        place(pickupToME, show, left, y, width);
        y += ROW;
        place(toolkitBar, show, left, y, width);
    }

    private static void place(AECheckbox box, boolean show, int x, int y, int width) {
        box.visible = show;
        box.setX(x);
        box.setY(y);
        box.setWidth(width);
        box.setHeight(CHECK_H);
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        layoutSlots();
        int color = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        int left = contentLeft();
        int top = contentTop();
        g.drawString(font, Component.translatable("gui.mesplicedterminal.wireless_settings.magnet"),
                left, top + ROW * 4 + 1, color, false);
        for (AbstractWidget widget : widgets) {
            widget.render(g, mouseX, mouseY, partialTicks);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) {
            return false;
        }
        for (AbstractWidget widget : widgets) {
            if (widget.visible && widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }
}
