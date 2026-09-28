package com.lhy.mest.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.recipe.MestWutCombineRecipe;

public final class ModRecipes {
    private ModRecipes() {
    }

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MESplicedterminal.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<MestWutCombineRecipe>> WUT_COMBINE =
            SERIALIZERS.register("wut_combine", MestWutCombineRecipe.Serializer::new);
}
