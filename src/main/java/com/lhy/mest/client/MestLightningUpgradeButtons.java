package com.lhy.mest.client;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.upgrades.IUpgradeInventory;

import com.lhy.mest.client.dock.PanelSideBar;
import com.moakiee.ae2lt.client.frequency.FrequencyBindingClient;
import com.moakiee.ae2lt.client.widgets.TextureToggleButton;
import com.moakiee.ae2lt.item.OverloadedFrequencyCardItem;
import com.moakiee.ae2lt.network.ToggleFrequencyCardAutoConnectPacket;

final class MestLightningUpgradeButtons {
    private TextureToggleButton frequencyButton;
    private TextureToggleButton frequencyAutoConnectButton;

    void install(MESTScreen screen, PanelSideBar sideBar) {
        frequencyButton = FrequencyBindingClient.createCardToolbarButton();
        frequencyButton.visible = false;
        sideBar.add(frequencyButton);
        // AE2LT 2.1.3 removed this factory; retain the terminal's existing toggle via its packet API.
        frequencyAutoConnectButton = new TextureToggleButton(TextureToggleButton.ButtonType.MODE,
                state -> PacketDistributor.sendToServer(ToggleFrequencyCardAutoConnectPacket.forTerminalCard()));
        frequencyAutoConnectButton.setTooltipOff(List.of(Component.translatable("ae2lt.gui.button.auto_connect_off")));
        frequencyAutoConnectButton.setTooltipOn(List.of(Component.translatable("ae2lt.gui.button.auto_connect_on")));
        frequencyAutoConnectButton.visible = false;
        sideBar.add(frequencyAutoConnectButton);
    }

    void update(MESTScreen screen) {
        if (frequencyButton == null || frequencyAutoConnectButton == null) {
            return;
        }
        ItemStack card = findFrequencyCard(screen);
        boolean hasCard = !card.isEmpty();
        frequencyButton.visible = hasCard;
        frequencyAutoConnectButton.visible = hasCard;
        if (hasCard) {
            frequencyAutoConnectButton.setState(OverloadedFrequencyCardItem.getData(card).autoConnect());
        }
    }

    private static ItemStack findFrequencyCard(MESTScreen screen) {
        IUpgradeInventory upgrades = screen.getHost().getUpgrades();
        for (int slot = 0; slot < upgrades.size(); slot++) {
            ItemStack stack = upgrades.getStackInSlot(slot);
            if (stack.getItem() instanceof OverloadedFrequencyCardItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
