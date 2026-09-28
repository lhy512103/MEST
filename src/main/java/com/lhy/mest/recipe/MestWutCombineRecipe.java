package com.lhy.mest.recipe;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import de.mari_023.ae2wtlib.api.AE2wtlibAPI;
import de.mari_023.ae2wtlib.api.registration.WTDefinition;
import de.mari_023.ae2wtlib.wut.recipe.Common;

import com.lhy.mest.item.ItemMEST;
import com.lhy.mest.registry.ModItems;
import com.lhy.mest.registry.ModRecipes;

/**
 * Shapeless merge of the spliced terminal with any other AE2WTLib wireless terminal into a WUT.
 * Mirrors {@code ae2wtlib:combine} but matches every registered {@link WTDefinition} at runtime.
 */
public final class MestWutCombineRecipe implements CraftingRecipe {
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findInputs(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        Inputs inputs = findInputs(input);
        if (inputs == null) {
            return ItemStack.EMPTY;
        }
        ItemStack wut = new ItemStack(AE2wtlibAPI.getWUT());
        wut = Common.mergeTerminal(wut, inputs.mest(), inputs.mestDefinition());
        return Common.mergeTerminal(wut, inputs.other(), inputs.otherDefinition());
    }

    @Nullable
    private static Inputs findInputs(CraftingInput input) {
        if (input.ingredientCount() != 2) {
            return null;
        }
        ItemStack mest = ItemStack.EMPTY;
        ItemStack other = ItemStack.EMPTY;
        WTDefinition mestDefinition = null;
        WTDefinition otherDefinition = null;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (AE2wtlibAPI.isUniversalTerminal(stack)) {
                return null;
            }
            WTDefinition definition = WTDefinition.ofOrNull(stack);
            if (definition == null) {
                return null;
            }
            if (stack.getItem() instanceof ItemMEST) {
                if (!mest.isEmpty()) {
                    return null;
                }
                mest = stack;
                mestDefinition = definition;
            } else {
                if (!other.isEmpty()) {
                    return null;
                }
                other = stack;
                otherDefinition = definition;
            }
        }
        if (mest.isEmpty() || other.isEmpty() || mestDefinition == null || otherDefinition == null) {
            return null;
        }
        return new Inputs(mest, mestDefinition, other, otherDefinition);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(AE2wtlibAPI.getWUT());
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(ModItems.SPLICED_TERMINAL.get()));
        ItemStack[] others = WTDefinition.wirelessTerminals().stream()
                .filter(definition -> !(definition.item() instanceof ItemMEST))
                .map(definition -> new ItemStack(definition.item()))
                .toArray(ItemStack[]::new);
        if (others.length > 0) {
            ingredients.add(Ingredient.of(others));
        }
        return ingredients;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.WUT_COMBINE.get();
    }

    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.EQUIPMENT;
    }

    private record Inputs(
            ItemStack mest,
            WTDefinition mestDefinition,
            ItemStack other,
            WTDefinition otherDefinition) {
    }

    public static final class Serializer implements RecipeSerializer<MestWutCombineRecipe> {
        private static final MestWutCombineRecipe INSTANCE = new MestWutCombineRecipe();
        private static final MapCodec<MestWutCombineRecipe> CODEC = MapCodec.unit(INSTANCE);
        private static final StreamCodec<RegistryFriendlyByteBuf, MestWutCombineRecipe> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public MapCodec<MestWutCombineRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MestWutCombineRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
