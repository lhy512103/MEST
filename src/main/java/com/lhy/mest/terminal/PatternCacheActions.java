package com.lhy.mest.terminal;

import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;

import com.lhy.mest.network.PatternCacheActionPacket;

/** Cache-pattern transformations; authentication and the single menu broadcast stay with the caller. */
class PatternCacheActions {
    private final List<Slot> slots;
    private final Level level;

    PatternCacheActions(List<Slot> slots, Level level) {
        this.slots = slots;
        this.level = level;
    }

    void apply(PatternCacheActionPacket.Action action, boolean value) {
        switch (action) {
            case SWAP -> rotateProcessingOutputs();
            case TIMES_2 -> modifyProcessingPatterns(2, false);
            case TIMES_3 -> modifyProcessingPatterns(3, false);
            case TIMES_5 -> modifyProcessingPatterns(5, false);
            case EQUALS_1 -> restoreProcessingRatios();
            case DIVIDE_2 -> modifyProcessingPatterns(2, true);
            case DIVIDE_3 -> modifyProcessingPatterns(3, true);
            case DIVIDE_5 -> modifyProcessingPatterns(5, true);
            case TIMES_8 -> modifyProcessingPatterns(8, false);
            case DIVIDE_8 -> modifyProcessingPatterns(8, true);
            case TIMES_16 -> modifyProcessingPatterns(16, false);
            case DIVIDE_16 -> modifyProcessingPatterns(16, true);
            case TIMES_32 -> modifyProcessingPatterns(32, false);
            case DIVIDE_32 -> modifyProcessingPatterns(32, true);
            case ITEM_SUBSTITUTION -> changeCraftingSubstitution(true, value);
            case FLUID_SUBSTITUTION -> changeCraftingSubstitution(false, value);
        }
    }

    void modifyProcessingPatterns(int scale, boolean divide) {
        if (scale <= 0) {
            return;
        }
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AEProcessingPattern process)) {
                continue;
            }
            var input = process.getSparseInputs().toArray(GenericStack[]::new);
            var output = process.getSparseOutputs().toArray(GenericStack[]::new);
            if (!PatternEncodingAmounts.canScale(input, scale, divide)
                    || !PatternEncodingAmounts.canScale(output, scale, divide)) {
                continue;
            }
            slot.set(PatternDetailsHelper.encodeProcessingPattern(
                    Arrays.asList(PatternEncodingAmounts.scaleStacks(input, scale, divide)),
                    Arrays.asList(PatternEncodingAmounts.scaleStacks(output, scale, divide))));
        }
    }

    void restoreProcessingRatios() {
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AEProcessingPattern process)) {
                continue;
            }
            long gcd = PatternEncodingAmounts.sharedGcd(process.getSparseInputs(), process.getSparseOutputs());
            if (gcd <= 1L) {
                continue;
            }
            slot.set(PatternDetailsHelper.encodeProcessingPattern(
                    PatternEncodingAmounts.divideStacks(process.getSparseInputs(), gcd),
                    PatternEncodingAmounts.divideStacks(process.getSparseOutputs(), gcd)));
        }
    }

    void rotateProcessingOutputs() {
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AEProcessingPattern process)) {
                continue;
            }
            slot.set(PatternDetailsHelper.encodeProcessingPattern(
                    process.getSparseInputs(),
                    Arrays.asList(PatternEncodingAmounts.rotateOutputs(
                            process.getSparseOutputs().toArray(GenericStack[]::new)))));
        }
    }

    void changeCraftingSubstitution(boolean itemSubstitution, boolean value) {
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AECraftingPattern craft)) {
                continue;
            }
            var recipe = craftingRecipeFor(craft);
            if (recipe == null) {
                continue;
            }
            try {
                slot.set(PatternDetailsHelper.encodeCraftingPattern(
                        recipe,
                        itemize(craft.getSparseInputs()),
                        itemize(craft.getOutputs().isEmpty() ? null : craft.getOutputs().getFirst()),
                        itemSubstitution ? value : craft.canSubstitute(),
                        itemSubstitution ? craft.canSubstituteFluids() : value));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Nullable
    private RecipeHolder<CraftingRecipe> craftingRecipeFor(AECraftingPattern craft) {
        var items = itemize(craft.getSparseInputs());
        if (items.length != 9) {
            return null;
        }
        return level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, Arrays.asList(items)), level)
                .orElse(null);
    }

    private static ItemStack[] itemize(List<GenericStack> stacks) {
        var items = new ItemStack[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) {
            items[i] = itemize(stacks.get(i));
        }
        return items;
    }

    private static ItemStack itemize(@Nullable GenericStack stack) {
        if (stack != null && stack.what() instanceof AEItemKey itemKey) {
            return itemKey.toStack((int) stack.amount());
        }
        return ItemStack.EMPTY;
    }
}
