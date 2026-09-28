package com.lhy.mest.client;

import com.lhy.mest.integration.MestRecipeTransferContext;
import com.lhy.mest.terminal.MESTMenu;

/** Keeps the recipe-viewer target synchronized with the visible recipe panels. */
public final class MestRecipeTransferController {
    public void beginMenu(MESTMenu menu) {
        MestRecipeTransferContext.beginMenu(menu);
    }

    public void updateAvailability(MESTMenu menu, boolean encodingVisible, boolean craftingVisible) {
        MestRecipeTransferContext.updateAvailability(menu, encodingVisible, craftingVisible);
    }

    public void synchronize(MESTMenu menu, boolean encodingVisible, boolean craftingVisible) {
        updateAvailability(menu, encodingVisible, craftingVisible);
        MestRecipeTransferContext.Target selected = MestRecipeTransferContext.targetFor(menu);
        MestRecipeTransferContext.Target replacement = replacementFor(
                selected, encodingVisible, craftingVisible);
        if (replacement != null) {
            select(menu, replacement);
        }
    }

    static MestRecipeTransferContext.Target replacementFor(
            MestRecipeTransferContext.Target selected, boolean encodingVisible, boolean craftingVisible) {
        if (selected == MestRecipeTransferContext.Target.PATTERN_ENCODING
                && !encodingVisible && craftingVisible) {
            return MestRecipeTransferContext.Target.CRAFTING;
        }
        if (selected == MestRecipeTransferContext.Target.CRAFTING
                && !craftingVisible && encodingVisible) {
            return MestRecipeTransferContext.Target.PATTERN_ENCODING;
        }
        return null;
    }

    public void select(MESTMenu menu, MestRecipeTransferContext.Target target) {
        MestRecipeTransferContext.select(menu, target);
    }

    public void clear(MESTMenu menu) {
        MestRecipeTransferContext.clear(menu);
    }
}
