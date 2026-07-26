package com.lhy.mest.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MestRecipeTransferContextTest {
    @Test
    void scopesSelectionToMenuIdentityEvenWhenContainerIdIsReused() {
        var oldMenu = new Object();
        var newMenu = new Object();

        MestRecipeTransferContext.beginMenu(oldMenu, 10);
        MestRecipeTransferContext.select(
                oldMenu, 10, MestRecipeTransferContext.Target.PATTERN_ENCODING);
        assertEquals(MestRecipeTransferContext.Target.PATTERN_ENCODING,
                MestRecipeTransferContext.targetFor(oldMenu, 10));

        MestRecipeTransferContext.beginMenu(newMenu, 10);
        assertEquals(MestRecipeTransferContext.Target.CRAFTING,
                MestRecipeTransferContext.targetFor(newMenu, 10));
        assertEquals(MestRecipeTransferContext.Target.CRAFTING,
                MestRecipeTransferContext.targetFor(oldMenu, 10));

        MestRecipeTransferContext.select(
                oldMenu, 10, MestRecipeTransferContext.Target.PATTERN_ENCODING);
        assertEquals(MestRecipeTransferContext.Target.CRAFTING,
                MestRecipeTransferContext.targetFor(newMenu, 10));

        MestRecipeTransferContext.clear(oldMenu, 10);
        MestRecipeTransferContext.select(
                newMenu, 10, MestRecipeTransferContext.Target.PATTERN_ENCODING);
        assertEquals(MestRecipeTransferContext.Target.PATTERN_ENCODING,
                MestRecipeTransferContext.targetFor(newMenu, 10));

        MestRecipeTransferContext.clear(newMenu, 10);
    }
}
