package com.lhy.mest.terminal;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import appeng.api.features.GridLinkables;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.items.tools.powered.powersink.PoweredItemCapabilities;

import de.mari_023.ae2wtlib.api.gui.Icon;
import de.mari_023.ae2wtlib.api.registration.AddTerminalEvent;

import com.lhy.mest.registry.ModItems;

/**
 * Wires the ME Spliced Terminal into the AE2WTLib terminal framework and AE2's power system.
 */
public final class MestTerminal {
    private MestTerminal() {}

    /** Internal terminal name used by AE2WTLib's registry/hotkey/universal-terminal systems. */
    public static final String TERMINAL_NAME = "spliced";

    /**
     * Enqueue our terminal definition. This only adds a callback to AddTerminalEvent's handler list;
     * the callback runs later when AE2WTLib fires AddTerminalEvent.run() during the ITEM RegisterEvent.
     * By then the ItemMEST instance created by the deferred register already exists, which is all the
     * builder needs (it captures the item object, not its registry entry).
     */
    public static void registerTerminal() {
        AddTerminalEvent.register(event -> event
                .builder(TERMINAL_NAME, MESTMenuHost::new, MESTMenu.TYPE, ModItems.SPLICED_TERMINAL.get(), Icon.CRAFTING)
                .upgradeCount(3)
                .addTerminal());
    }

    /** AE2 powered items must expose an energy capability for the wireless battery to work. */
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        var item = ModItems.SPLICED_TERMINAL.get();
        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, ctx) -> new PoweredItemCapabilities(stack, item), item);
    }

    /**
     * Register the grid-linkable handler so the terminal can be bound to a wireless access point
     * (right-click the access point with the terminal in hand). Done in common setup, after all items
     * are registered. Uses AE2's standard wireless-terminal linking handler.
     */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var item = ModItems.SPLICED_TERMINAL.get();
            GridLinkables.register(item, WirelessTerminalItem.LINKABLE_HANDLER);
        });
    }
}
