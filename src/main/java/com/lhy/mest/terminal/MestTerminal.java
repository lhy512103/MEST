package com.lhy.mest.terminal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import appeng.api.features.GridLinkables;
import appeng.api.upgrades.Upgrades;
import appeng.core.localization.GuiText;
import appeng.items.materials.EnergyCardItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.items.tools.powered.powersink.PoweredItemCapabilities;

import de.mari_023.ae2wtlib.api.AE2wtlibAPI;
import de.mari_023.ae2wtlib.api.gui.Icon;
import de.mari_023.ae2wtlib.api.registration.AddTerminalEvent;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.compat.MestAddonUpgrades;
import com.lhy.mest.item.ItemMEST;
import com.lhy.mest.registry.ModItems;

/**
 * Wires the ME Spliced Terminal into the AE2WTLib terminal framework and AE2's power system.
 */
public final class MestTerminal {
    private MestTerminal() {}

    /** Internal terminal name used by AE2WTLib's registry/hotkey/universal-terminal systems. */
    public static final String TERMINAL_NAME = "spliced";
    private static final Icon SPLICED_TERMINAL_ICON = new Icon(
            0,
            0,
            16,
            16,
            new Icon.Texture(
                    ResourceLocation.fromNamespaceAndPath(
                            MESplicedterminal.MODID, "textures/guis/spliced_terminal_icon.png"),
                    16,
                    16));

    /**
     * Enqueue our terminal definition. This only adds a callback to AddTerminalEvent's handler list;
     * the callback runs later when AE2WTLib fires AddTerminalEvent.run() during the ITEM RegisterEvent.
     * By then the ItemMEST instance created by the deferred register already exists, which is all the
     * builder needs (it captures the item object, not its registry entry).
     */
    public static void registerTerminal() {
        AddTerminalEvent.register(event -> event
                .builder(TERMINAL_NAME, MESTMenuHost::new, MESTMenu.TYPE, ModItems.SPLICED_TERMINAL.get(), SPLICED_TERMINAL_ICON)
                .upgradeCount(3)
                .addTerminal());
    }

    /**
     * Runs before AE2wtlib_api's ITEM listener calls {@code AddTerminalEvent.run()}, so this
     * handler is last and {@link ItemMEST#upgradeInventorySize()} already sees every terminal.
     * Associations must be recorded <em>before</em> {@code UpgradeHelper.addUpgrades()} writes the
     * per-terminal {@code upgradeCount} (3), because {@link Upgrades#getMaxInstallable} keeps the
     * first match.
     */
    public static void onRegisterItems(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ITEM)) {
            return;
        }
        AddTerminalEvent.register(ignored -> registerEnergyCardCapacities());
    }

    private static void registerEnergyCardCapacities() {
        int count = ItemMEST.upgradeInventorySize();
        var item = ModItems.SPLICED_TERMINAL.get();
        String group = GuiText.WirelessTerminals.getTranslationKey();
        for (var card : BuiltInRegistries.ITEM) {
            if (card instanceof EnergyCardItem) {
                Upgrades.add(card, item, count, group);
            }
        }
        var magnet = BuiltInRegistries.ITEM.get(AE2wtlibAPI.id("magnet_card"));
        if (magnet != Items.AIR) {
            Upgrades.add(magnet, item, 1, group);
        }
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
            MestAddonUpgrades.register();
        });
    }
}
