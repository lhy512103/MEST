package com.lhy.mest.compat;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import appeng.api.stacks.GenericStack;

import com.sorrowmist.useless.content.recipe.AdvancedAlloyFurnaceRecipe;
import com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeCatalog;
import com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeFingerprint;

import com.lhy.mest.terminal.MESTMenu;

/**
 * Selects the Useless Mod recipe for MEST and lets the server convert the encoded processing
 * pattern through Useless Mod's public encoding API.
 */
public final class UselessOmniversalPatternSupport {
    private UselessOmniversalPatternSupport() {
    }

    public static boolean encode(
            MESTMenu menu,
            AlloyFurnaceRecipeCatalog.Entry entry,
            @Nullable List<List<GenericStack>> precomputedInputs,
            @Nullable List<GenericStack> precomputedOutputs) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(entry, "entry");

        ClientLevel level = clientLevel();
        if (level == null) {
            return false;
        }
        AdvancedAlloyFurnaceRecipe recipe = entry.recipe();
        List<List<GenericStack>> inputs = precomputedInputs;
        List<GenericStack> outputs = precomputedOutputs;
        if (inputs == null || outputs == null || inputs.isEmpty() || outputs.isEmpty()) {
            return false;
        }

        String fingerprint = AlloyFurnaceRecipeFingerprint.create(recipe, level.registryAccess());
        menu.selectUselessPattern(recipe.id(), fingerprint, entry.sourceId());
        com.lhy.mest.integration.MestEncodingHelper.encode(
                menu,
                com.lhy.mest.integration.MestEncodingHelper.RecipeKind.PROCESSING,
                null,
                List.of(),
                inputs,
                outputs,
                stack -> true);
        return true;
    }

    @Nullable
    private static ClientLevel clientLevel() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level;
    }
}
