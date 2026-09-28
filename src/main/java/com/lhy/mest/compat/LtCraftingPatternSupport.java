package com.lhy.mest.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;

import com.moakiee.ae2lt.logic.tianshu.terminal.PatternEncodingDuplicateFilter;
import com.moakiee.ae2lt.logic.tianshu.terminal.TianshuPatternUploadRouting;

final class LtCraftingPatternSupport {
    private LtCraftingPatternSupport() {
    }

    static boolean isCraftingUploadGroup(PatternContainerGroup group) {
        return TianshuPatternUploadRouting.isCraftingUploadGroup(group);
    }

    static boolean containsDuplicate(InternalInventory inventory, ItemStack candidate, Level level) {
        return PatternEncodingDuplicateFilter.containsEquivalentPattern(inventory, candidate, level);
    }
}
