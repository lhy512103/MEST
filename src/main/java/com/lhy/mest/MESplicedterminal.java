package com.lhy.mest;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

import com.lhy.mest.config.MestConfig;
import com.lhy.mest.network.MestPackets;
import com.lhy.mest.registry.ModAttachments;
import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.registry.ModItems;
import com.lhy.mest.registry.ModMenus;
import com.lhy.mest.registry.ModRecipes;
import com.lhy.mest.terminal.MestTerminal;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(MESplicedterminal.MODID)
public class MESplicedterminal {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "mesplicedterminal";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public MESplicedterminal(IEventBus modEventBus, ModContainer container) {
        // Register all deferred registries to the mod event bus.
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModItems.CREATIVE_TABS.register(modEventBus);
        ModComponents.DATA_COMPONENTS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModRecipes.SERIALIZERS.register(modEventBus);
        container.registerConfig(ModConfig.Type.COMMON, MestConfig.SPEC);

        // Hook into AE2/AE2WTLib lifecycle (terminal registration, capabilities, ...).
        // AddTerminalEvent.register only enqueues a callback; it must run before AE2WTLib fires
        // AddTerminalEvent.run() during the ITEM RegisterEvent, so the mod constructor is the safe place.
        MestTerminal.registerTerminal();
        modEventBus.addListener(MestTerminal::onRegisterCapabilities);
        modEventBus.addListener(MestTerminal::onCommonSetup);
        modEventBus.addListener(MestPackets::register);

    }
}
