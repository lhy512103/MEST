package com.lhy.mest.compat;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.GenericStack;

import com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeCatalog;
import com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeIdentity;
import com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.OmniversalPatternEncoding;

import com.lhy.mest.terminal.MESTMenu;

/**
 * Optional Useless Mod bridge. Only referenced from integration paths that already know the mod
 * loaded the recipe category, keeping the common path free of Useless classes.
 */
public final class UselessPatternBridge {
    private UselessPatternBridge() {
    }

    public static boolean isUselessEntry(Object object) {
        return object instanceof AlloyFurnaceRecipeCatalog.Entry;
    }

    public static ItemStack convert(MESTMenu menu, ItemStack processingPattern) {
        if (menu.getUselessPatternRecipeId() == null
                || menu.getUselessPatternFingerprint() == null
                || menu.getUselessPatternSourceId() == null) {
            return processingPattern;
        }
        var identity = new AlloyFurnaceRecipeIdentity(
                menu.getUselessPatternRecipeId(), menu.getUselessPatternFingerprint());
        var entry = AlloyFurnaceRecipeCatalog.resolve(
                menu.getPlayer().level(), menu.getUselessPatternSourceId(), identity).orElse(null);
        if (entry == null) {
            return processingPattern;
        }
        ItemStack converted = OmniversalPatternEncoding.encode(
                processingPattern, entry, menu.getPlayer().level());
        menu.clearUselessPatternSelection();
        return converted;
    }

    public static boolean encode(
            MESTMenu menu,
            Object entry,
            @Nullable List<List<GenericStack>> inputs,
            @Nullable List<GenericStack> outputs) {
        if (!(entry instanceof AlloyFurnaceRecipeCatalog.Entry uselessEntry)) {
            return false;
        }
        return UselessOmniversalPatternSupport.encode(menu, uselessEntry, inputs, outputs);
    }
}
