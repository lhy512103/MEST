package com.lhy.mest.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MestCraftingPatternAutoUploadTest {
    @Test
    void fallbackCraftingGroupIdsCoverEcoAndLightningTech() {
        assertTrue(MestCraftingPatternAutoUpload.FALLBACK_CRAFTING_GROUP_IDS.contains(
                "neoecoae:crafting_system_l4"));
        assertTrue(MestCraftingPatternAutoUpload.FALLBACK_CRAFTING_GROUP_IDS.contains(
                "neoecoae:crafting_system_l9"));
        assertTrue(MestCraftingPatternAutoUpload.FALLBACK_CRAFTING_GROUP_IDS.contains(
                "ae2lt:matter_warping_matrix_controller"));
        assertTrue(MestCraftingPatternAutoUpload.FALLBACK_CRAFTING_GROUP_IDS.contains(
                "extendedae:assembler_matrix_pattern"));
        assertFalse(MestCraftingPatternAutoUpload.FALLBACK_CRAFTING_GROUP_IDS.contains(
                "ae2:pattern_provider"));
    }
}
