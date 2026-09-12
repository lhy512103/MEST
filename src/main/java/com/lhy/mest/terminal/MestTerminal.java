package com.lhy.mest.terminal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import appeng.api.features.GridLinkables;
import appeng.api.upgrades.Upgrades;
import appeng.core.localization.GuiText;
import appeng.items.materials.EnergyCardItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.items.tools.powered.powersink.PoweredItemCapabilities;
import appeng.menu.locator.MenuLocators;

import de.mari_023.ae2wtlib.api.AE2wtlibAPI;
import de.mari_023.ae2wtlib.api.gui.Icon;
import de.mari_023.ae2wtlib.api.registration.AddTerminalEvent;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.compat.MestAddonUpgrades;
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
     * Enqueue our terminal definition. AE2WTLib fires {@code AddTerminalEvent.run()} during its
     * own ITEM {@code RegisterEvent}, before this mod's DeferredItem is bound, so the builder
     * must receive the eager item instance rather than {@code DeferredHolder.get()}.
     */
    public static void registerTerminal() {
        AddTerminalEvent.register(event -> event
                .builder(TERMINAL_NAME, MESTMenuHost::new, MESTMenu.TYPE, ModItems.splicedTerminalItem(), SPLICED_TERMINAL_ICON)
                .upgradeCount(3)
                .addTerminal());
    }

    /** AE2 powered items must expose an energy capability for the wireless battery to work. */
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        var item = ModItems.splicedTerminalItem();
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
            var item = ModItems.splicedTerminalItem();
            GridLinkables.register(item, WirelessTerminalItem.LINKABLE_HANDLER);
            String group = GuiText.WirelessTerminals.getTranslationKey();
            for (var card : BuiltInRegistries.ITEM) {
                if (card instanceof EnergyCardItem) {
                    Upgrades.add(card, item, 3, group);
                }
            }
            var magnet = BuiltInRegistries.ITEM.get(AE2wtlibAPI.id("magnet_card"));
            if (magnet != Items.AIR) {
                Upgrades.add(magnet, item, 1, group);
            }
            MestAddonUpgrades.register();
            MenuLocators.register(
                    ToolkitItemLocator.class,
                    ToolkitItemLocator::writeToPacket,
                    ToolkitItemLocator::readFromPacket);
        });
    }
}
