package com.lhy.mest.client;

import net.minecraft.world.item.ItemStack;

import appeng.api.upgrades.IUpgradeInventory;

import com.lhy.mest.client.dock.PanelSideBar;
import com.moakiee.ae2lt.client.FrequencyBindingClient;
import com.moakiee.ae2lt.client.TextureToggleButton;
import com.moakiee.ae2lt.item.OverloadedFrequencyCardItem;

final class MestLightningUpgradeButtons {
    private TextureToggleButton frequencyButton;
    private TextureToggleButton frequencyAutoConnectButton;

    void install(MESTScreen screen, PanelSideBar sideBar) {
        frequencyButton = FrequencyBindingClient.createCardToolbarButton();
        frequencyButton.visible = false;
        sideBar.add(frequencyButton);
        frequencyAutoConnectButton = FrequencyBindingClient.createCardAutoConnectToolbarButton();
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
