package com.lhy.mest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.lhy.mest.integration.MestRecipeTransferContext.Target;

class MestRecipeTransferControllerTest {
    @Test
    void movesToCraftingWhenEncodingSelectionBecomesHidden() {
        assertEquals(Target.CRAFTING,
                MestRecipeTransferController.replacementFor(Target.PATTERN_ENCODING, false, true));
    }

    @Test
    void movesToEncodingWhenCraftingSelectionBecomesHidden() {
        assertEquals(Target.PATTERN_ENCODING,
                MestRecipeTransferController.replacementFor(Target.CRAFTING, true, false));
    }

    @Test
    void keepsSelectionWhenBothTargetsHaveTheSameAvailability() {
        assertNull(MestRecipeTransferController.replacementFor(Target.CRAFTING, true, true));
        assertNull(MestRecipeTransferController.replacementFor(Target.PATTERN_ENCODING, false, false));
    }
}
