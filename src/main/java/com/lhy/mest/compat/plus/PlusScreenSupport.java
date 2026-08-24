package com.lhy.mest.compat.plus;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import com.extendedae_plus.client.screen.ProviderSelectScreen;
import com.extendedae_plus.network.CancelPendingPatternC2SPacket;
import com.lhy.mest.network.ProviderPickerListPacket;

/** Plus-only screen/packet helpers. Loaded only after {@code extendedae_plus} is confirmed present. */
public final class PlusScreenSupport {
    private PlusScreenSupport() {
    }

    public static boolean loaded() {
        return ModList.get().isLoaded("extendedae_plus");
    }

    public static Screen presetPicker(Screen parent, ProviderPickerListPacket packet) {
        return new ProviderSelectScreen(parent, packet.ids(), packet.names(), packet.emptySlots());
    }

    public static void cancelPending() {
        PacketDistributor.sendToServer(CancelPendingPatternC2SPacket.INSTANCE);
    }

    public static boolean fillSearchFromHoveredIngredient(com.lhy.mest.client.panel.MEListPanel panel,
            int keyCode, int scanCode) {
        return PlusJeiHotkeys.fillSearchFromHoveredIngredient(panel, keyCode, scanCode);
    }
}
