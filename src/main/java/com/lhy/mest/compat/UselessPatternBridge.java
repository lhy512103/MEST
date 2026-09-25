package com.lhy.mest.compat;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.GenericStack;

import com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeCatalog;

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
