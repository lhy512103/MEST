package com.lhy.mest.client;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import appeng.init.client.InitScreens;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.PatternProviderClientBridge;
import com.lhy.mest.network.ProviderPickerClientBridge;
import com.lhy.mest.terminal.MESTMenu;
import com.lhy.mest.terminal.MestMagnetMenu;

/**
 * Client-side setup: registers menu screens. AE2 resolves ScreenStyle JSON paths against its own
 * namespace, so we can reuse AE2's wireless terminal style directly without shipping a copy.
 */
public final class MestClient {
    private MestClient() {}

    public static void init(IEventBus modEventBus) {
        PatternProviderClientBridge.install(PatternProviderClientHandler::handle);
        ProviderPickerClientBridge.install(ProviderPickerClientHandler::handle);
        modEventBus.addListener(MestClient::onRegisterScreens);
        modEventBus.addListener(MestClient::onRegisterGuiLayers);
        modEventBus.addListener(MestClient::onRegisterKeyMappings);
        modEventBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(MestClientModules::collect));
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MestKeybindings.TOOLKIT_BAR_LEFT);
        event.register(MestKeybindings.TOOLKIT_BAR_RIGHT);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar"),
                ToolkitBarHud::render);
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(event, MESTMenu.TYPE, MESTScreen::new, "/screens/terminals/wireless_terminal.json");
        InitScreens.register(event, MestMagnetMenu.TYPE, MestMagnetScreen::new, "/screens/wtlib/magnet.json");
    }
}
