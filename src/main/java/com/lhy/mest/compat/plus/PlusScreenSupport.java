package com.lhy.mest.compat.plus;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import com.lhy.mest.network.ProviderPickerListPacket;

/** Safe facade: checking availability must never resolve optional screen or packet classes. */
public final class PlusScreenSupport {
    private PlusScreenSupport() {
    }

    public static boolean loaded() {
        return ModList.get().isLoaded("extendedae_plus");
    }

    public static Screen presetPicker(Screen parent, ProviderPickerListPacket packet) {
        return loaded() ? OptionalScreens.presetPicker(parent, packet) : parent;
    }

    public static void cancelPending() {
        if (loaded()) {
            OptionalScreens.cancelPending();
        }
    }

    public static boolean fillSearchFromHoveredIngredient(com.lhy.mest.client.panel.MEListPanel panel,
            int keyCode, int scanCode) {
        return loaded() && ModList.get().isLoaded("jei")
                && OptionalScreens.fillSearch(panel, keyCode, scanCode);
    }

    /** The JVM does not load this holder until a guarded method actually calls it. */
    private static final class OptionalScreens {
        private static Screen presetPicker(Screen parent, ProviderPickerListPacket packet) {
            return new com.extendedae_plus.client.screen.ProviderSelectScreen(
                    parent, packet.ids(), packet.names(), packet.emptySlots());
        }

        private static void cancelPending() {
            PacketDistributor.sendToServer(com.extendedae_plus.network.CancelPendingPatternC2SPacket.INSTANCE);
        }

        private static boolean fillSearch(com.lhy.mest.client.panel.MEListPanel panel, int keyCode, int scanCode) {
            return PlusJeiHotkeys.fillSearchFromHoveredIngredient(panel, keyCode, scanCode);
        }
    }
}
