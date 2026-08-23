package com.lhy.mest.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.init.client.InitScreens;

import com.lhy.mest.network.PatternProviderClientBridge;
import com.lhy.mest.network.ProviderPickerClientBridge;
import com.lhy.mest.terminal.MESTMenu;

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
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(event, MESTMenu.TYPE, MESTScreen::new, "/screens/terminals/wireless_terminal.json");
    }
}
