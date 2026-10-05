package com.lhy.mest.compat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.GenericStack;

import com.lhy.mest.terminal.MESTMenu;

/**
 * Runtime-isolated bridge for Useless Mod. This class deliberately contains no static references to
 * Useless Mod types: optional integration must remain harmless when the mod is absent or its API has
 * changed between pack versions.
 */
public final class UselessPatternBridge {
    private static final String ENTRY_CLASS =
            "com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeCatalog$Entry";
    private static final String CATALOG_CLASS =
            "com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeCatalog";
    private static final String IDENTITY_CLASS =
            "com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeIdentity";
    private static final String ENCODING_CLASS =
            "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.OmniversalPatternEncoding";
    private static final String SUPPORT_CLASS =
            "com.lhy.mest.compat.UselessOmniversalPatternSupport";

    private UselessPatternBridge() {
    }

    /** Returns false rather than linking optional classes when Useless Mod is absent or incompatible. */
    public static boolean isUselessEntry(Object object) {
        if (object == null) {
            return false;
        }
        try {
            return load(ENTRY_CLASS).isInstance(object);
        } catch (LinkageError | ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static ItemStack convert(MESTMenu menu, ItemStack processingPattern) {
        if (menu.getUselessPatternRecipeId() == null
                || menu.getUselessPatternFingerprint() == null
                || menu.getUselessPatternSourceId() == null) {
            return processingPattern;
        }
        try {
            Class<?> identityType = load(IDENTITY_CLASS);
            Object identity = identityType.getConstructor(ResourceLocation.class, String.class)
                    .newInstance(menu.getUselessPatternRecipeId(), menu.getUselessPatternFingerprint());
            Object resolved = load(CATALOG_CLASS)
                    .getMethod("resolve", net.minecraft.world.level.Level.class, String.class, identityType)
                    .invoke(null, menu.getPlayer().level(), menu.getUselessPatternSourceId(), identity);
            Object entry = resolved instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (entry == null) {
                return processingPattern;
            }
            ItemStack converted = (ItemStack) load(ENCODING_CLASS)
                    .getMethod("encode", ItemStack.class, entry.getClass(), net.minecraft.world.level.Level.class)
                    .invoke(null, processingPattern, entry, menu.getPlayer().level());
            if (converted != null) {
                menu.clearUselessPatternSelection();
            }
            return converted == null ? processingPattern : converted;
        } catch (LinkageError | ReflectiveOperationException | RuntimeException ignored) {
            return processingPattern;
        }
    }

    public static boolean encode(
            MESTMenu menu,
            Object entry,
            @Nullable List<List<GenericStack>> inputs,
            @Nullable List<GenericStack> outputs) {
        if (!isUselessEntry(entry)) {
            return false;
        }
        try {
            Class<?> entryType = load(ENTRY_CLASS);
            Method method = load(SUPPORT_CLASS).getMethod(
                    "encode", MESTMenu.class, entryType, List.class, List.class);
            return Boolean.TRUE.equals(method.invoke(null, menu, entry, inputs, outputs));
        } catch (LinkageError | ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, UselessPatternBridge.class.getClassLoader());
    }
}
