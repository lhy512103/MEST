package com.lhy.mest.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Tests for the pure decision logic of {@link MestEncodingHelper} (recipe classification and
 * pre-encoding validation shared by the EMI/JEI bridges).
 *
 * <p>This project's unit tests run without a Minecraft bootstrap (see
 * {@code MestRecipeTransferContextTest}), so anything touching registries, {@code ItemStack}s,
 * networking, or the menu (the actual encode paths and ingredient-priority logic) cannot be
 * exercised here. Even proxying {@link net.minecraft.world.item.crafting.Recipe} fails because its
 * static initializer requires the registry bootstrap, so the {@code Recipe}-based overloads are
 * covered only for the {@code null} case and the MC-free core overloads are tested directly.
 */
class MestEncodingHelperTest {
    @Test
    void nullRecipeIsNotASupportedCraftingRecipe() {
        assertFalse(MestEncodingHelper.isSupportedCraftingRecipe(null));
    }

    @Test
    void classifyTreatsNullRecipeAsProcessingUnlessCategorizedAsCrafting() {
        assertEquals(MestEncodingHelper.RecipeKind.PROCESSING,
                MestEncodingHelper.classifyRecipe(null, false));
        // EMI's category hint promotes even a recipe without a backing recipe to a crafting pattern.
        assertEquals(MestEncodingHelper.RecipeKind.CRAFTING,
                MestEncodingHelper.classifyRecipe(null, true));
    }

    @Test
    void craftingValidationAcceptsUnknownBackingRecipe() {
        // A null backing recipe cannot be size-checked and must be allowed through.
        assertEquals(MestEncodingHelper.ValidationResult.OK,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.CRAFTING, null, false, false));
    }

    @Test
    void craftingValidationChecks3x3Fit() {
        assertEquals(MestEncodingHelper.ValidationResult.OK,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.CRAFTING, true, false, false));
        assertEquals(MestEncodingHelper.ValidationResult.RECIPE_TOO_LARGE,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.CRAFTING, false, true, true));
    }

    @Test
    void processingValidationRequiresInputsAndOutputs() {
        assertEquals(MestEncodingHelper.ValidationResult.OK,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.PROCESSING, true, true, true));
        assertEquals(MestEncodingHelper.ValidationResult.INCOMPATIBLE_RECIPE,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.PROCESSING, true, false, true));
        assertEquals(MestEncodingHelper.ValidationResult.INCOMPATIBLE_RECIPE,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.PROCESSING, true, true, false));
    }

    @Test
    void processingValidationIgnoresRecipeDimensions() {
        // An oversized backing recipe must not matter when encoding a processing pattern.
        assertEquals(MestEncodingHelper.ValidationResult.OK,
                MestEncodingHelper.validate(
                        MestEncodingHelper.RecipeKind.PROCESSING, false, true, true));
    }
}
