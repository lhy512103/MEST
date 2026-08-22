package com.lhy.mest.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.PatternTermSlot;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.util.ConfigInventory;

import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.PatternAccessSession;

/**
 * Container for the ME Spliced Terminal. Extends AE2's {@link CraftingTermMenu} (which itself extends
 * MEStorageMenu), so it provides both the incremental ME item list and a 3x3 crafting matrix with a
 * result slot. The dockable module layout placing these into floating panels is a client-side concern.
 */
public class MESTMenu extends CraftingTermMenu {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "spliced_terminal");
    public static final MenuType<MESTMenu> TYPE = MenuTypeBuilder.create(MESTMenu::new, MESTMenuHost.class)
            .buildUnregistered(ID);

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    private static final int CRAFTING_GRID_SLOTS = CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT;

    private static final String ACTION_SET_PATTERN_MODE = "mestSetPatternMode";
    private static final String ACTION_ENCODE_PATTERN = "mestEncodePattern";
    private static final String ACTION_CLEAR_PATTERN = "mestClearPattern";
    private static final String ACTION_SET_PATTERN_SUBSTITUTION = "mestSetPatternSubstitution";
    private static final String ACTION_SET_PATTERN_FLUID_SUBSTITUTION = "mestSetPatternFluidSubstitution";
    private static final String ACTION_SET_STONECUTTING_RECIPE_ID = "mestSetStonecuttingRecipeId";
    private static final String ACTION_CYCLE_PROCESSING_OUTPUT = "mestCycleProcessingOutput";

    private final MESTMenuHost host;
    private final PatternAccessSession patternAccessSession;
    private final PatternEncodingLogic patternEncodingLogic;
    private final FakeSlot[] patternCraftingSlots = new FakeSlot[CRAFTING_GRID_SLOTS];
    private final FakeSlot[] processingInputSlots = new FakeSlot[AEProcessingPattern.MAX_INPUT_SLOTS];
    private final FakeSlot[] processingOutputSlots = new FakeSlot[AEProcessingPattern.MAX_OUTPUT_SLOTS];
    private FakeSlot stonecuttingInputSlot;
    private FakeSlot smithingTableTemplateSlot;
    private FakeSlot smithingTableBaseSlot;
    private FakeSlot smithingTableAdditionSlot;
    private PatternTermSlot patternCraftOutputSlot;
    private RestrictedInputSlot blankPatternSlot;
    private RestrictedInputSlot encodedPatternSlot;
    private final ConfigInventory encodedInputsInv;
    private final ConfigInventory encodedOutputsInv;
    private final List<RecipeHolder<StonecutterRecipe>> stonecuttingRecipes = new ArrayList<>();

    private RecipeHolder<CraftingRecipe> currentPatternCraftingRecipe;
    private EncodingMode currentPatternMode;

    // Re-entrancy guard: updatePatternCraftingOutput() writes into patternCraftOutputSlot and reads
    // encodedInputsInv, both of which fire onSlotChange(), which calls updatePatternCraftingOutput().
    // Without this guard the constructor's initial call recurses infinitely (StackOverflowError).
    private boolean updatingPatternCraftingOutput;

    // Debounce for the recipe lookup in updatePatternCraftingOutput(): while a batch slot update is in
    // progress (full content sync, per-slot sync cascade), slot changes only mark this dirty and the
    // lookup runs once at the end of the batch instead of once per slot.
    private boolean patternCraftingOutputDirty;
    private boolean batchingSlotUpdates;

    @GuiSync(93)
    public EncodingMode patternEncodingMode = EncodingMode.CRAFTING;
    @GuiSync(92)
    public boolean patternSubstitute = false;
    @GuiSync(91)
    public boolean patternSubstituteFluids = true;
    @GuiSync(90)
    @Nullable
    public ResourceLocation stonecuttingRecipeId;

    public final IntSet patternSlotsSupportingFluidSubstitution = new IntArraySet();

    public MESTMenu(int id, Inventory ip, MESTMenuHost host) {
        super(TYPE, id, ip, host, true);
        this.host = host;
        this.patternAccessSession = new PatternAccessSession(this);
        this.patternEncodingLogic = host.getLogic();
        this.encodedInputsInv = patternEncodingLogic.getEncodedInputInv();
        this.encodedOutputsInv = patternEncodingLogic.getEncodedOutputInv();

        addSlot(new RestrictedInputSlot(
                        RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                        host.getSubInventory(WTMenuHost.INV_SINGULARITY),
                        0),
                AE2wtlibSlotSemantics.SINGULARITY);

        addPatternEncodingSlots();

        this.patternEncodingMode = patternEncodingLogic.getMode();
        this.patternSubstitute = patternEncodingLogic.isSubstitution();
        this.patternSubstituteFluids = patternEncodingLogic.isFluidSubstitution();
        this.stonecuttingRecipeId = patternEncodingLogic.getStonecuttingRecipeId();

        registerClientAction(ACTION_ENCODE_PATTERN, this::encodePattern);
        registerClientAction(ACTION_CLEAR_PATTERN, this::clearPatternEncoding);
        registerClientAction(ACTION_SET_PATTERN_MODE, EncodingMode.class, this::setPatternEncodingMode);
        registerClientAction(ACTION_SET_PATTERN_SUBSTITUTION, Boolean.class, this::setPatternSubstitute);
        registerClientAction(ACTION_SET_PATTERN_FLUID_SUBSTITUTION, Boolean.class, this::setPatternFluidSubstitute);
        registerClientAction(ACTION_SET_STONECUTTING_RECIPE_ID, ResourceLocation.class,
                this::setStonecuttingRecipeId);
        registerClientAction(ACTION_CYCLE_PROCESSING_OUTPUT, this::cycleProcessingOutput);

        updateStonecuttingRecipes();
        updatePatternCraftingOutput();
        applyPatternEncodingSlotActivation();
    }

    @Override
    public IGridNode getGridNode() {
        return host.getActionableNode();
    }

    public PatternAccessSession getPatternAccessSession() {
        return patternAccessSession;
    }

    public boolean canUsePatternAccess(ServerPlayer player) {
        if (!isServerSide()
                || getPlayer() != player
                || player.containerMenu != this
                || !isValidMenu()
                || !stillValid(player)
                || !host.isValid()
                || !getLinkStatus().connected()) {
            return false;
        }
        var node = getGridNode();
        return node != null && node.isActive() && node.getGrid() != null;
    }

    public boolean isWUT() {
        return host.getItemStack().getItem() instanceof ItemWUT;
    }

    public PatternEncodingLogic getPatternEncodingLogic() {
        return patternEncodingLogic;
    }

    public EncodingMode getPatternEncodingMode() {
        return patternEncodingMode;
    }

    public void setPatternEncodingMode(EncodingMode mode) {
        if (isClientSide()) {
            patternEncodingMode = mode;
            applyPatternEncodingSlotActivation();
            sendClientAction(ACTION_SET_PATTERN_MODE, mode);
            return;
        }
        if (this.patternEncodingMode != mode || patternEncodingLogic.getMode() != mode) {
            patternEncodingLogic.setMode(mode);
            this.patternEncodingMode = mode;
            if (mode == EncodingMode.STONECUTTING) {
                updateStonecuttingRecipes();
            }
            updatePatternCraftingOutput();
            checkFluidSubstitutionSupport();
            applyPatternEncodingSlotActivation();
            broadcastChanges();
        }
    }

    public boolean isPatternSubstitute() {
        return patternSubstitute;
    }

    public void setPatternSubstitute(boolean substitute) {
        if (isClientSide()) {
            patternSubstitute = substitute;
            sendClientAction(ACTION_SET_PATTERN_SUBSTITUTION, substitute);
            return;
        }
        patternEncodingLogic.setSubstitution(substitute);
        patternSubstitute = substitute;
        broadcastChanges();
    }

    public boolean isPatternFluidSubstitute() {
        return patternSubstituteFluids;
    }

    public void setPatternFluidSubstitute(boolean substitute) {
        if (isClientSide()) {
            patternSubstituteFluids = substitute;
            checkFluidSubstitutionSupport();
            sendClientAction(ACTION_SET_PATTERN_FLUID_SUBSTITUTION, substitute);
            return;
        }
        patternEncodingLogic.setFluidSubstitution(substitute);
        patternSubstituteFluids = substitute;
        checkFluidSubstitutionSupport();
        broadcastChanges();
    }

    public void encodePattern() {
        if (isClientSide()) {
            sendClientAction(ACTION_ENCODE_PATTERN);
            return;
        }

        ItemStack encodedPattern = createEncodedPattern();
        if (encodedPattern != null) {
            var encodeOutput = this.encodedPatternSlot.getItem();

            if (!encodeOutput.isEmpty()
                    && !PatternDetailsHelper.isEncodedPattern(encodeOutput)
                    && !AEItems.BLANK_PATTERN.is(encodeOutput)) {
                return;
            } else if (encodeOutput.isEmpty()) {
                var blankPattern = this.blankPatternSlot.getItem();
                if (!AEItems.BLANK_PATTERN.is(blankPattern)) {
                    return;
                }

                blankPattern.shrink(1);
                if (blankPattern.getCount() <= 0) {
                    this.blankPatternSlot.set(ItemStack.EMPTY);
                }
            }

            this.encodedPatternSlot.set(encodedPattern);
        } else {
            clearEncodedPatternOnly();
        }
    }

    public void clearPatternEncoding() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR_PATTERN);
            return;
        }

        encodedInputsInv.clear();
        encodedOutputsInv.clear();
        updatePatternCraftingOutput();
        broadcastChanges();
    }

    public void cycleProcessingOutput() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_PROCESSING_OUTPUT);
            return;
        }
        if (patternEncodingMode != EncodingMode.PROCESSING) {
            return;
        }

        var newOutputs = new ItemStack[getProcessingOutputSlots().length];
        for (int i = 0; i < processingOutputSlots.length; i++) {
            newOutputs[i] = ItemStack.EMPTY;
            if (!processingOutputSlots[i].getItem().isEmpty()) {
                for (int j = 1; j < processingOutputSlots.length; j++) {
                    var nextItem = processingOutputSlots[(i + j) % processingOutputSlots.length].getItem();
                    if (!nextItem.isEmpty()) {
                        // Copy so no live slot stack reference is shared and later mutated through set().
                        newOutputs[i] = nextItem.copy();
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < newOutputs.length; i++) {
            processingOutputSlots[i].set(newOutputs[i]);
        }
        broadcastChanges();
    }

    public boolean canCycleProcessingOutputs() {
        return patternEncodingMode == EncodingMode.PROCESSING
                && Arrays.stream(processingOutputSlots).filter(s -> !s.getItem().isEmpty()).count() > 1;
    }

    @Contract("null -> false")
    public boolean canModifyAmountForPatternSlot(@Nullable Slot slot) {
        return isProcessingPatternSlot(slot) && slot.hasItem();
    }

    @Contract("null -> false")
    public boolean isProcessingPatternSlot(@Nullable Slot slot) {
        if (slot == null || patternEncodingMode != EncodingMode.PROCESSING) {
            return false;
        }

        for (var processingOutputSlot : processingOutputSlots) {
            if (processingOutputSlot == slot) {
                return true;
            }
        }
        for (var processingInputSlot : processingInputSlots) {
            if (processingInputSlot == slot) {
                return true;
            }
        }
        return false;
    }

    @Contract("null -> false")
    public boolean isPatternEncodingInputSlot(@Nullable Slot slot) {
        if (slot == null) {
            return false;
        }
        return switch (patternEncodingMode) {
            case CRAFTING -> containsIdentity(patternCraftingSlots, slot);
            case PROCESSING -> containsIdentity(processingInputSlots, slot);
            case SMITHING_TABLE -> slot == smithingTableTemplateSlot
                    || slot == smithingTableBaseSlot
                    || slot == smithingTableAdditionSlot;
            case STONECUTTING -> slot == stonecuttingInputSlot;
        };
    }

    private static boolean containsIdentity(Slot[] slots, Slot target) {
        for (var slot : slots) {
            if (slot == target) {
                return true;
            }
        }
        return false;
    }

    public FakeSlot[] getPatternCraftingSlots() {
        return patternCraftingSlots;
    }

    public PatternTermSlot getPatternCraftOutputSlot() {
        return patternCraftOutputSlot;
    }

    public FakeSlot[] getProcessingInputSlots() {
        return processingInputSlots;
    }

    public FakeSlot[] getProcessingOutputSlots() {
        return processingOutputSlots;
    }

    public FakeSlot getStonecuttingInputSlot() {
        return stonecuttingInputSlot;
    }

    public FakeSlot getSmithingTableTemplateSlot() {
        return smithingTableTemplateSlot;
    }

    public FakeSlot getSmithingTableBaseSlot() {
        return smithingTableBaseSlot;
    }

    public FakeSlot getSmithingTableAdditionSlot() {
        return smithingTableAdditionSlot;
    }

    public RestrictedInputSlot getBlankPatternSlot() {
        return blankPatternSlot;
    }

    public RestrictedInputSlot getEncodedPatternSlot() {
        return encodedPatternSlot;
    }

    public List<RecipeHolder<StonecutterRecipe>> getStonecuttingRecipes() {
        return stonecuttingRecipes;
    }

    @Nullable
    public ResourceLocation getStonecuttingRecipeId() {
        return stonecuttingRecipeId;
    }

    public void setStonecuttingRecipeId(ResourceLocation id) {
        if (isClientSide()) {
            stonecuttingRecipeId = id;
            sendClientAction(ACTION_SET_STONECUTTING_RECIPE_ID, id);
            return;
        }

        updateStonecuttingRecipes();
        ResourceLocation validatedId = stonecuttingRecipes.stream()
                .anyMatch(recipe -> recipe.id().equals(id)) ? id : null;
        patternEncodingLogic.setStonecuttingRecipeId(validatedId);
        stonecuttingRecipeId = validatedId;
        updatePatternCraftingOutput();
        broadcastChanges();
    }

    private void addPatternEncodingSlots() {
        var encodedInputs = encodedInputsInv.createMenuWrapper();
        var encodedOutputs = encodedOutputsInv.createMenuWrapper();

        for (int i = 0; i < patternCraftingSlots.length; i++) {
            var slot = new FakeSlot(encodedInputs, i);
            slot.setHideAmount(true);
            this.addSlot(this.patternCraftingSlots[i] = slot, MestSlotSemantics.PATTERN_CRAFTING_GRID);
        }
        this.addSlot(this.patternCraftOutputSlot = new PatternTermSlot(), MestSlotSemantics.PATTERN_CRAFTING_RESULT);

        for (int i = 0; i < processingInputSlots.length; i++) {
            this.addSlot(this.processingInputSlots[i] = new FakeSlot(encodedInputs, i),
                    MestSlotSemantics.PATTERN_PROCESSING_INPUTS);
        }
        for (int i = 0; i < processingOutputSlots.length; i++) {
            this.addSlot(this.processingOutputSlots[i] = new FakeSlot(encodedOutputs, i),
                    MestSlotSemantics.PATTERN_PROCESSING_OUTPUTS);
        }

        this.addSlot(this.stonecuttingInputSlot = new FakeSlot(encodedInputs, 0),
                MestSlotSemantics.PATTERN_STONECUTTING_INPUT);
        this.stonecuttingInputSlot.setHideAmount(true);

        this.addSlot(this.smithingTableTemplateSlot = new FakeSlot(encodedInputs, 0),
                MestSlotSemantics.PATTERN_SMITHING_TEMPLATE);
        this.smithingTableTemplateSlot.setHideAmount(true);
        this.addSlot(this.smithingTableBaseSlot = new FakeSlot(encodedInputs, 1),
                MestSlotSemantics.PATTERN_SMITHING_BASE);
        this.smithingTableBaseSlot.setHideAmount(true);
        this.addSlot(this.smithingTableAdditionSlot = new FakeSlot(encodedInputs, 2),
                MestSlotSemantics.PATTERN_SMITHING_ADDITION);
        this.smithingTableAdditionSlot.setHideAmount(true);

        this.addSlot(this.blankPatternSlot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.BLANK_PATTERN,
                patternEncodingLogic.getBlankPatternInv(), 0), SlotSemantics.BLANK_PATTERN);
        this.addSlot(
                this.encodedPatternSlot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.ENCODED_PATTERN,
                        patternEncodingLogic.getEncodedPatternInv(), 0),
                SlotSemantics.ENCODED_PATTERN);
        this.encodedPatternSlot.setStackLimit(1);
    }

    private ItemStack updatePatternCraftingOutput() {
        // During super-construction (CraftingTermMenu.<init> calls updateCurrentRecipeAndOutput, which
        // fires onSlotChange before our fields are initialised) encodedInputsInv is still null, and
        // patternCraftOutputSlot may not exist yet. Bail out until addPatternEncodingSlots() has run.
        if (encodedInputsInv == null || patternCraftOutputSlot == null) {
            return ItemStack.EMPTY;
        }
        if (updatingPatternCraftingOutput) {
            // Already recomputing — setResultItem()/getKey() below re-enters onSlotChange().
            return patternCraftOutputSlot.getItem();
        }
        updatingPatternCraftingOutput = true;
        try {
            return updatePatternCraftingOutput0();
        } finally {
            updatingPatternCraftingOutput = false;
        }
    }

    private ItemStack updatePatternCraftingOutput0() {
        var level = this.getPlayerInventory().player.level();

        var items = net.minecraft.core.NonNullList.withSize(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT,
                ItemStack.EMPTY);
        boolean invalidIngredients = false;
        for (int x = 0; x < items.size(); x++) {
            var stack = getEncodedCraftingIngredient(x);
            if (stack != null) {
                items.set(x, stack);
            } else {
                invalidIngredients = true;
            }
        }

        var input = CraftingInput.of(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, items);

        if (this.currentPatternCraftingRecipe == null
                || !this.currentPatternCraftingRecipe.value().matches(input, level)) {
            if (invalidIngredients) {
                this.currentPatternCraftingRecipe = null;
            } else {
                this.currentPatternCraftingRecipe = level.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, input, level)
                        .orElse(null);
            }
            this.currentPatternMode = this.patternEncodingMode;
        }
        checkFluidSubstitutionSupport();

        final ItemStack result;
        if (this.currentPatternCraftingRecipe == null) {
            result = ItemStack.EMPTY;
        } else {
            result = this.currentPatternCraftingRecipe.value().assemble(input, level.registryAccess());
        }

        this.patternCraftOutputSlot.setResultItem(result);
        return result;
    }

    private void checkFluidSubstitutionSupport() {
        this.patternSlotsSupportingFluidSubstitution.clear();

        if (this.patternEncodingMode != EncodingMode.CRAFTING || this.currentPatternCraftingRecipe == null) {
            return;
        }

        var encodedPattern = createEncodedPattern();
        if (encodedPattern != null) {
            var decodedPattern = PatternDetailsHelper.decodePattern(encodedPattern,
                    this.getPlayerInventory().player.level());
            if (decodedPattern instanceof AECraftingPattern craftingPattern) {
                for (int i = 0; i < craftingPattern.getSparseInputs().size(); i++) {
                    if (craftingPattern.getValidFluid(i) != null) {
                        patternSlotsSupportingFluidSubstitution.add(i);
                    }
                }
            }
        }
    }

    @Nullable
    private ItemStack createEncodedPattern() {
        return switch (this.patternEncodingMode) {
            case CRAFTING -> encodeCraftingPattern();
            case PROCESSING -> encodeProcessingPattern();
            case SMITHING_TABLE -> encodeSmithingTablePattern();
            case STONECUTTING -> encodeStonecuttingPattern();
        };
    }

    @Nullable
    private ItemStack encodeCraftingPattern() {
        var ingredients = new ItemStack[CRAFTING_GRID_SLOTS];
        boolean valid = false;
        for (int x = 0; x < ingredients.length; x++) {
            ingredients[x] = getEncodedCraftingIngredient(x);
            if (ingredients[x] == null) {
                return null;
            } else if (!ingredients[x].isEmpty()) {
                valid = true;
            }
        }
        if (!valid) {
            return null;
        }

        var result = updatePatternCraftingOutput();
        if (result.isEmpty() || currentPatternCraftingRecipe == null) {
            return null;
        }

        return PatternDetailsHelper.encodeCraftingPattern(this.currentPatternCraftingRecipe, ingredients, result,
                isPatternSubstitute(), isPatternFluidSubstitute());
    }

    @Nullable
    private ItemStack encodeProcessingPattern() {
        var inputs = new GenericStack[encodedInputsInv.size()];
        boolean valid = false;
        for (int slot = 0; slot < encodedInputsInv.size(); slot++) {
            inputs[slot] = encodedInputsInv.getStack(slot);
            if (inputs[slot] != null) {
                valid = true;
            }
        }
        if (!valid) {
            return null;
        }

        var outputs = new GenericStack[encodedOutputsInv.size()];
        for (int slot = 0; slot < encodedOutputsInv.size(); slot++) {
            outputs[slot] = encodedOutputsInv.getStack(slot);
        }
        if (outputs[0] == null) {
            return null;
        }

        return PatternDetailsHelper.encodeProcessingPattern(Arrays.asList(inputs), Arrays.asList(outputs));
    }

    @Nullable
    private ItemStack encodeSmithingTablePattern() {
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey template)
                || !(encodedInputsInv.getKey(1) instanceof AEItemKey base)
                || !(encodedInputsInv.getKey(2) instanceof AEItemKey addition)) {
            return null;
        }

        var input = new SmithingRecipeInput(template.toStack(), base.toStack(), addition.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMITHING, input, level)
                .orElse(null);
        if (recipe == null) {
            return null;
        }

        var output = AEItemKey.of(recipe.value().assemble(input, level.registryAccess()));
        return PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition, output,
                patternEncodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack encodeStonecuttingPattern() {
        if (stonecuttingRecipeId == null) {
            return null;
        }
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey input)) {
            return null;
        }

        var recipeInput = new SingleRecipeInput(input.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.STONECUTTING, recipeInput, level, stonecuttingRecipeId)
                .orElse(null);
        if (recipe == null) {
            return null;
        }

        var output = AEItemKey.of(recipe.value().getResultItem(level.registryAccess()));
        return PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, output,
                patternEncodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack getEncodedCraftingIngredient(int slot) {
        var what = encodedInputsInv.getKey(slot);
        if (what == null) {
            return ItemStack.EMPTY;
        } else if (what instanceof AEItemKey itemKey) {
            return itemKey.toStack(1);
        } else {
            return null;
        }
    }

    private void clearEncodedPatternOnly() {
        var encodedPattern = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodedPattern)) {
            this.encodedPatternSlot.set(AEItems.BLANK_PATTERN.stack(encodedPattern.getCount()));
        }
    }

    private void updateStonecuttingRecipes() {
        stonecuttingRecipes.clear();
        if (encodedInputsInv == null) {
            return;
        }
        if (encodedInputsInv.getKey(0) instanceof AEItemKey itemKey) {
            var level = getPlayer().level();
            var recipeInput = new SingleRecipeInput(itemKey.toStack());
            stonecuttingRecipes.addAll(
                    level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, recipeInput, level));
        }

        if (stonecuttingRecipeId != null
                && stonecuttingRecipes.stream().noneMatch(r -> r.id().equals(stonecuttingRecipeId))) {
            if (isServerSide()) {
                patternEncodingLogic.setStonecuttingRecipeId(null);
            }
            stonecuttingRecipeId = null;
        }
    }

    public void applyPatternEncodingSlotActivation() {
        boolean crafting = patternEncodingMode == EncodingMode.CRAFTING;
        boolean processing = patternEncodingMode == EncodingMode.PROCESSING;
        boolean smithing = patternEncodingMode == EncodingMode.SMITHING_TABLE;
        boolean stonecutting = patternEncodingMode == EncodingMode.STONECUTTING;

        for (var slot : patternCraftingSlots) {
            slot.setActive(crafting);
        }
        patternCraftOutputSlot.setActive(crafting);
        for (var slot : processingInputSlots) {
            slot.setActive(processing);
        }
        for (var slot : processingOutputSlots) {
            slot.setActive(processing);
        }
        smithingTableTemplateSlot.setActive(smithing);
        smithingTableBaseSlot.setActive(smithing);
        smithingTableAdditionSlot.setActive(smithing);
        stonecuttingInputSlot.setActive(stonecutting);
    }

    @Override
    public void setItem(int slotID, int stateId, ItemStack stack) {
        boolean wasBatching = batchingSlotUpdates;
        batchingSlotUpdates = true;
        try {
            super.setItem(slotID, stateId, stack);
        } finally {
            batchingSlotUpdates = wasBatching;
        }
        markPatternCraftingOutputDirty();
    }

    @Override
    public void initializeContents(int stateId, List<ItemStack> items, ItemStack carried) {
        boolean wasBatching = batchingSlotUpdates;
        batchingSlotUpdates = true;
        try {
            super.initializeContents(stateId, items, carried);
        } finally {
            batchingSlotUpdates = wasBatching;
        }
        markPatternCraftingOutputDirty();
    }

    private void markPatternCraftingOutputDirty() {
        patternCraftingOutputDirty = true;
        if (!batchingSlotUpdates) {
            flushPatternCraftingOutput();
        }
    }

    private void flushPatternCraftingOutput() {
        if (patternCraftingOutputDirty) {
            patternCraftingOutputDirty = false;
            updatePatternCraftingOutput();
        }
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            this.patternEncodingMode = patternEncodingLogic.getMode();
            this.patternSubstitute = patternEncodingLogic.isSubstitution();
            this.patternSubstituteFluids = patternEncodingLogic.isFluidSubstitution();
            this.stonecuttingRecipeId = patternEncodingLogic.getStonecuttingRecipeId();
        }
        flushPatternCraftingOutput();
        super.broadcastChanges();
        // AEBaseMenu construction can invoke this override before our fields are initialized.
        if (patternAccessSession != null) {
            patternAccessSession.serverTick();
        }
    }

    @Override
    public void removed(Player player) {
        patternAccessSession.close();
        super.removed(player);
    }

    @Override
    public void onServerDataSync(it.unimi.dsi.fastutil.shorts.ShortSet updatedFields) {
        super.onServerDataSync(updatedFields);

        // Client-side: only refresh UI-derived state. Never write the synced mode back into
        // patternEncodingLogic here - the server owns the logic's item data, and writing to it from the
        // client can drift from (and later fight with) the authoritative server state.
        if (updatedFields.contains((short) 93) && currentPatternMode != patternEncodingMode) {
            currentPatternMode = patternEncodingMode;
            updatePatternCraftingOutput();
            updateStonecuttingRecipes();
        }
        if (updatedFields.contains((short) 91) || updatedFields.contains((short) 92)) {
            checkFluidSubstitutionSupport();
        }
        applyPatternEncodingSlotActivation();
    }

    @Override
    public void onSlotChange(Slot slot) {
        super.onSlotChange(slot);

        if (slot == this.encodedPatternSlot && isServerSide()) {
            this.broadcastChanges();
        }
        if (slot == this.stonecuttingInputSlot) {
            updateStonecuttingRecipes();
        }
        markPatternCraftingOutputDirty();
    }

    @Override
    protected int transferStackToMenu(ItemStack input) {
        int initialCount = input.getCount();

        if (blankPatternSlot.mayPlace(input)) {
            input = blankPatternSlot.safeInsert(input);
            if (input.isEmpty()) {
                return initialCount;
            }
        }

        if (encodedPatternSlot.mayPlace(input)) {
            input = encodedPatternSlot.safeInsert(input);
            if (input.isEmpty()) {
                return initialCount;
            }
        }

        int transferred = initialCount - input.getCount();
        return transferred + super.transferStackToMenu(input);
    }
}
