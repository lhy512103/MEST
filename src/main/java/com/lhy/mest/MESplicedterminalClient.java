package com.lhy.mest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.lhy.mest.client.MestClient;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MESplicedterminal.MODID, dist = Dist.CLIENT)
public class MESplicedterminalClient {
    public MESplicedterminalClient(ModContainer container, net.neoforged.bus.api.IEventBus modEventBus) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Register client-only handlers (menu screens, key mappings, ...).
        MestClient.init(modEventBus);
    }
}
