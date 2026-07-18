package com.lhy.mest.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MestRecipeTransferContextTest {
    @AfterEach
    void reset() {
        MestRecipeTransferContext.clear(10);
        MestRecipeTransferContext.clear(11);
    }

    @Test
    void scopesSelectionToOneMenuInstance() {
        MestRecipeTransferContext.beginMenu(10);
        MestRecipeTransferContext.select(10, MestRecipeTransferContext.Target.PATTERN_ENCODING);

        assertEquals(MestRecipeTransferContext.Target.PATTERN_ENCODING,
                MestRecipeTransferContext.targetFor(10));
        assertEquals(MestRecipeTransferContext.Target.CRAFTING,
                MestRecipeTransferContext.targetFor(11));

        MestRecipeTransferContext.beginMenu(11);
        assertEquals(MestRecipeTransferContext.Target.CRAFTING,
                MestRecipeTransferContext.targetFor(11));
    }
}
