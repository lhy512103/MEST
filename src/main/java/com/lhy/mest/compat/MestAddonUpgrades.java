package com.lhy.mest.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import appeng.api.upgrades.Upgrades;
import appeng.core.localization.GuiText;

import com.lhy.mest.registry.ModItems;

/**
 * Associates third-party upgrade cards with the spliced terminal through AE2's public
 * {@link Upgrades#add} API. Lightning Tech and Import/Export Card only register against vanilla
 * wireless terminals / WUT, so MEST never appeared in their "可用于" lists.
 */
public final class MestAddonUpgrades {
    private MestAddonUpgrades() {
    }

    public static void register() {
        Item terminal = ModItems.SPLICED_TERMINAL.get();
        String group = GuiText.WirelessTerminals.getTranslationKey();
        add(terminal, group, "ae2lt", "ae2lt:overloaded_frequency_card", 1);
        add(terminal, group, "ae2importexportcard", "ae2importexportcard:import_card", 1);
        add(terminal, group, "ae2importexportcard", "ae2importexportcard:export_card", 1);
    }

    private static void add(Item terminal, String group, String modId, String itemId, int max) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        Item card = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        if (card == Items.AIR) {
            return;
        }
        Upgrades.add(card, terminal, max, group);
    }
}
