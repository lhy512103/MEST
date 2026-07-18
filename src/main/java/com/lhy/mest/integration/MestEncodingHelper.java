package com.lhy.mest.integration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.google.common.math.LongMath;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.CraftingRecipeUtil;

import com.lhy.mest.terminal.MESTMenu;

/** AE2's recipe-encoding algorithm adapted to the combined terminal's menu type. */
public final class MestEncodingHelper {
    private static final Comparator<GridInventoryEntry> ENTRY_COMPARATOR = Comparator
            .comparing(GridInventoryEntry::isCraftable)
            .thenComparing(MestEncodingHelper::isUndamaged)
            .thenComparing(GridInventoryEntry::getStoredAmount);

    private MestEncodingHelper() {
    }

    public static boolean isSupportedCraftingRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) {
            return false;
        }
        return recipe.getType() == RecipeType.CRAFTING
                || recipe.getType() == RecipeType.STONECUTTING
                || recipe.getType() == RecipeType.SMITHING;
    }

    public static void encodeProcessingRecipe(
            MESTMenu menu,
            List<List<GenericStack>> genericIngredients,
            List<GenericStack> genericResults) {
        menu.setPatternEncodingMode(EncodingMode.PROCESSING);
        var priorities = getIngredientPriorities(menu);
        encodeBestMatchingStacksIntoSlots(genericIngredients, priorities, menu.getProcessingInputSlots());
        encodeBestMatchingStacksIntoSlots(
                genericResults.stream().filter(Objects::nonNull).map(List::of).toList(),
                priorities,
                menu.getProcessingOutputSlots());
    }

    public static void encodeCraftingRecipe(
            MESTMenu menu,
            @Nullable RecipeHolder<?> recipe,
            List<List<GenericStack>> genericIngredients,
            Predicate<ItemStack> visiblePredicate) {
        if (recipe != null && recipe.value().getType() == RecipeType.STONECUTTING) {
            menu.setPatternEncodingMode(EncodingMode.STONECUTTING);
            menu.setStonecuttingRecipeId(recipe.id());
        } else if (recipe != null && recipe.value().getType() == RecipeType.SMITHING) {
            menu.setPatternEncodingMode(EncodingMode.SMITHING_TABLE);
        } else {
            menu.setPatternEncodingMode(EncodingMode.CRAFTING);
        }

        var priorities = getIngredientPriorities(menu);
        var encodedInputs = NonNullList.withSize(menu.getPatternCraftingSlots().length, ItemStack.EMPTY);

        if (recipe != null) {
            var ingredients3x3 = CraftingRecipeUtil.ensure3by3CraftingMatrix(recipe.value());
            for (int slot = 0; slot < ingredients3x3.size() && slot < encodedInputs.size(); slot++) {
                var ingredient = ingredients3x3.get(slot);
                if (ingredient.isEmpty()) {
                    continue;
                }
                var bestNetworkIngredient = priorities.entrySet().stream()
                        .filter(entry -> entry.getKey() instanceof AEItemKey itemKey && itemKey.matches(ingredient))
                        .max(Map.Entry.comparingByValue())
                        .map(entry -> entry.getKey() instanceof AEItemKey itemKey ? itemKey.toStack() : null);
                var bestIngredient = bestNetworkIngredient.orElseGet(() -> {
                    var candidates = ingredient.getItems();
                    for (var candidate : candidates) {
                        if (visiblePredicate.test(candidate)) {
                            return candidate;
                        }
                    }
                    return candidates.length == 0 ? ItemStack.EMPTY : candidates[0];
                });
                encodedInputs.set(slot, bestIngredient);
            }
        } else {
            for (int slot = 0; slot < genericIngredients.size() && slot < encodedInputs.size(); slot++) {
                var candidates = genericIngredients.get(slot);
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                var bestIngredient = findBestIngredient(priorities, candidates).what();
                if (bestIngredient instanceof AEItemKey itemKey) {
                    encodedInputs.set(slot, itemKey.toStack());
                } else {
                    encodedInputs.set(slot, GenericStack.wrapInItemStack(bestIngredient, 1));
                }
            }
        }

        for (int i = 0; i < encodedInputs.size(); i++) {
            setFilter(menu.getPatternCraftingSlots()[i], encodedInputs.get(i));
        }
        for (var outputSlot : menu.getProcessingOutputSlots()) {
            setFilter(outputSlot, ItemStack.EMPTY);
        }
    }

    private static void encodeBestMatchingStacksIntoSlots(
            List<List<GenericStack>> possibleInputsBySlot,
            Map<AEKey, Integer> priorities,
            FakeSlot[] slots) {
        var encodedInputs = new ArrayList<GenericStack>();
        for (var candidates : possibleInputsBySlot) {
            if (candidates != null && !candidates.isEmpty()) {
                addOrMerge(encodedInputs, findBestIngredient(priorities, candidates));
            }
        }

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = i < encodedInputs.size()
                    ? GenericStack.wrapInItemStack(encodedInputs.get(i))
                    : ItemStack.EMPTY;
            setFilter(slots[i], stack);
        }
    }

    private static void setFilter(FakeSlot slot, ItemStack stack) {
        PacketDistributor.sendToServer(new InventoryActionPacket(InventoryAction.SET_FILTER, slot.index, stack));
    }

    private static GenericStack findBestIngredient(
            Map<AEKey, Integer> priorities,
            List<GenericStack> possibleIngredients) {
        return possibleIngredients.stream()
                .filter(Objects::nonNull)
                .map(stack -> Pair.of(stack, priorities.getOrDefault(stack.what(), Integer.MIN_VALUE)))
                .max(Comparator.comparingInt(Pair::getRight))
                .map(Pair::getLeft)
                .orElseThrow(() -> new IllegalArgumentException("Recipe slot has no encodable ingredients"));
    }

    private static void addOrMerge(List<GenericStack> stacks, GenericStack newStack) {
        for (int i = 0; i < stacks.size(); i++) {
            var existing = stacks.get(i);
            if (Objects.equals(existing.what(), newStack.what())) {
                long newAmount = LongMath.saturatedAdd(existing.amount(), newStack.amount());
                stacks.set(i, new GenericStack(newStack.what(), newAmount));
                long overflow = newStack.amount() - (newAmount - existing.amount());
                if (overflow > 0) {
                    stacks.add(new GenericStack(newStack.what(), overflow));
                }
                return;
            }
        }
        stacks.add(newStack);
    }

    private static Map<AEKey, Integer> getIngredientPriorities(MESTMenu menu) {
        var result = new HashMap<AEKey, Integer>();
        var repo = menu.getClientRepo();
        if (repo != null) {
            var orderedEntries = repo.getAllEntries().stream()
                    .sorted(ENTRY_COMPARATOR)
                    .map(GridInventoryEntry::getWhat)
                    .toList();
            for (int i = 0; i < orderedEntries.size(); i++) {
                result.put(orderedEntries.get(i), i);
            }
        }
        for (var item : menu.getPlayerInventory().items) {
            var key = AEItemKey.of(item);
            if (key != null) {
                result.putIfAbsent(key, -1);
            }
        }
        return result;
    }

    private static boolean isUndamaged(GridInventoryEntry entry) {
        return !(entry.getWhat() instanceof AEItemKey itemKey) || !itemKey.isDamaged();
    }
}
