package com.lhy.mest.integration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IIngredientVisibility;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.ItemModText;
import appeng.integration.modules.itemlists.CraftingHelper;
import appeng.integration.modules.itemlists.TransferHelper;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.items.CraftingTermMenu;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.terminal.MESTMenu;

/**
 * JEI recipe-transfer bridge for the MEST terminal.
 *
 * <p>MEST registers its own {@link MenuType} ({@link MESTMenu#TYPE}), so AE2's external JEI addon
 * (which targets {@code CraftingTermMenu.TYPE}) does not cover MEST. This plugin registers a transfer
 * handler for {@link MESTMenu} keyed to {@link RecipeTypes#CRAFTING}, mirroring the ae2-jei-integration
 * {@code UseCraftingRecipeTransfer} flow: validate the recipe, surface missing-ingredient errors, then
 * delegate to {@link CraftingHelper#performTransfer} (which MESTMenu inherits as a
 * {@link CraftingTermMenu} subclass).
 *
 * <p>{@link JeiPlugin} is auto-discovered by JEI; the class is only loaded when JEI is present, so the
 * mod still loads without JEI.
 */
@JeiPlugin
public class MestJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(MESTScreen.class, MestJeiPlugin::propertiesFor);
        registration.addGuiContainerHandler(MESTScreen.class, new mezz.jei.api.gui.handlers.IGuiContainerHandler<>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(MESTScreen containerScreen) {
                return containerScreen.getExclusionZones();
            }
        });
    }

    /**
     * JEI rejects {@code guiXSize/guiYSize <= 0} and then hides both the ingredient list and
     * bookmarks. The vanilla handler cannot be used; this always returns a strictly positive
     * core rectangle (ME list). Other panels are extra areas so JEI can stair-step around them.
     */
    private static IGuiProperties propertiesFor(MESTScreen screen) {
        if (screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        Rect2i bounds = screen.recipeViewerBounds();
        int x = Math.max(0, bounds.getX());
        int y = Math.max(0, bounds.getY());
        int width = bounds.getWidth();
        int height = bounds.getHeight();
        if (width <= 0 || height <= 0) {
            width = Math.max(176, Math.min(screen.width / 2, screen.width - 220));
            height = Math.max(166, Math.min(screen.height * 2 / 3, screen.height - 40));
            x = Math.max(0, (screen.width - width) / 2);
            y = Math.max(0, (screen.height - height) / 2);
        }
        if (x + width > screen.width) {
            width = Math.max(1, screen.width - x);
        }
        if (y + height > screen.height) {
            height = Math.max(1, screen.height - y);
        }
        return new MestGuiProperties(x, y, width, height, screen.width, screen.height);
    }

    private record MestGuiProperties(
            int guiLeft, int guiTop, int guiXSize, int guiYSize, int screenWidth, int screenHeight)
            implements IGuiProperties {
        @Override
        public Class<? extends Screen> screenClass() {
            return MESTScreen.class;
        }
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        var encodingHandler = new MestPatternEncodingTransferHandler(
                registration.getTransferHelper(),
                registration.getJeiHelpers().getIngredientVisibility());
        registration.addRecipeTransferHandler(
                new MestCraftingTransferHandler<>(MESTMenu.class, MESTMenu.TYPE,
                        registration.getTransferHelper(), encodingHandler),
                RecipeTypes.CRAFTING);
        registration.addUniversalRecipeTransferHandler(encodingHandler);
    }

    /**
     * Crafting-matrix transfer handler for any {@link CraftingTermMenu} subtype. Logic mirrors
     * ae2-jei-integration's {@code UseCraftingRecipeTransfer}: reject non-crafting / oversized recipes,
     * compute missing ingredients via {@link CraftingTermMenu#findMissingIngredients}, show a user
     * error if nothing is available, otherwise call {@link CraftingHelper#performTransfer}.
     */
    private static final class MestCraftingTransferHandler<T extends CraftingTermMenu>
            implements IRecipeTransferHandler<T, RecipeHolder<CraftingRecipe>> {
        private final Class<T> menuClass;
        private final MenuType<T> menuType;
        private final IRecipeTransferHandlerHelper helper;
        private final MestPatternEncodingTransferHandler encodingHandler;

        MestCraftingTransferHandler(Class<T> menuClass, MenuType<T> menuType,
                IRecipeTransferHandlerHelper helper,
                MestPatternEncodingTransferHandler encodingHandler) {
            this.menuClass = menuClass;
            this.menuType = menuType;
            this.helper = helper;
            this.encodingHandler = encodingHandler;
        }

        @Override
        public Class<? extends T> getContainerClass() {
            return menuClass;
        }

        @Override
        public Optional<MenuType<T>> getMenuType() {
            return Optional.of(menuType);
        }

        @Override
        public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public IRecipeTransferError transferRecipe(T menu, RecipeHolder<CraftingRecipe> recipe,
                IRecipeSlotsView slotsView, Player player, boolean maxTransfer, boolean doTransfer) {
            if (menu instanceof MESTMenu mestMenu
                    && MestRecipeTransferContext.targetFor(mestMenu)
                    == MestRecipeTransferContext.Target.PATTERN_ENCODING) {
                return encodingHandler.transferRecipe(
                        mestMenu, recipe, slotsView, player, maxTransfer, doTransfer);
            }
            CraftingRecipe crafting = recipe.value();

            if (crafting.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) {
                return helper.createInternalError();
            }
            if (crafting.getIngredients().isEmpty()) {
                return helper.createUserErrorWithTooltip(ItemModText.INCOMPATIBLE_RECIPE.text());
            }
            if (!crafting.canCraftInDimensions(3, 3)) {
                return helper.createUserErrorWithTooltip(ItemModText.RECIPE_TOO_LARGE.text());
            }

            boolean craftMissing = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
                    .hasControlDown();
            List<IRecipeSlotView> inputSlotViews = slotsView.getSlotViews(RecipeIngredientRole.INPUT);
            Map<Integer, net.minecraft.world.item.crafting.Ingredient> guiSlotToIngredient =
                    helper.getGuiSlotIndexToIngredientMap(recipe);

            CraftingTermMenu.MissingIngredientSlots missing =
                    menu.findMissingIngredients(guiSlotToIngredient);

            // Everything missing — show a "no items" error highlighting the affected slots.
            if (missing.missingSlots().size() == guiSlotToIngredient.size()) {
                List<IRecipeSlotView> missingViews = missing.missingSlots().stream()
                        .map(idx -> idx < inputSlotViews.size() ? inputSlotViews.get(idx) : null)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                return helper.createUserErrorForMissingSlots(ItemModText.NO_ITEMS.text(), missingViews);
            }

            if (!doTransfer) {
                if (missing.totalSize() > 0) {
                    // Cosmetic: highlight what's missing/craftable but still allow the transfer.
                    return new CosmeticTransferError(missing.anyMissing());
                }
                return null;
            }

            CraftingHelper.performTransfer(menu, recipe.id(), crafting, craftMissing);
            return null;
        }
    }

    private static final class MestPatternEncodingTransferHandler
            implements IUniversalRecipeTransferHandler<MESTMenu> {
        private static final int CRAFTING_GRID_SIZE = 9;
        private static final int CRAFTABLE_HIGHLIGHT = 0x400000FF;

        private final IRecipeTransferHandlerHelper helper;
        private final IIngredientVisibility ingredientVisibility;

        private MestPatternEncodingTransferHandler(
                IRecipeTransferHandlerHelper helper,
                IIngredientVisibility ingredientVisibility) {
            this.helper = helper;
            this.ingredientVisibility = ingredientVisibility;
        }

        @Override
        public Class<? extends MESTMenu> getContainerClass() {
            return MESTMenu.class;
        }

        @Override
        public Optional<MenuType<MESTMenu>> getMenuType() {
            return Optional.of(MESTMenu.TYPE);
        }

        @Override
        public IRecipeTransferError transferRecipe(
                MESTMenu menu,
                Object rawRecipe,
                IRecipeSlotsView slotsView,
                Player player,
                boolean maxTransfer,
                boolean doTransfer) {
            if (MestRecipeTransferContext.targetFor(menu)
                    != MestRecipeTransferContext.Target.PATTERN_ENCODING) {
                return helper.createInternalError();
            }

            RecipeHolder<?> holder = rawRecipe instanceof RecipeHolder<?> recipeHolder ? recipeHolder : null;
            Recipe<?> recipe = holder != null ? holder.value() : null;
            var kind = MestEncodingHelper.classifyRecipe(recipe, false);

            var inputs = genericInputs(slotsView);
            var outputs = genericOutputs(slotsView);
            switch (MestEncodingHelper.validate(kind, recipe, !inputs.isEmpty(), !outputs.isEmpty())) {
                case RECIPE_TOO_LARGE -> {
                    return helper.createUserErrorWithTooltip(ItemModText.RECIPE_TOO_LARGE.text());
                }
                case INCOMPATIBLE_RECIPE -> {
                    return helper.createUserErrorWithTooltip(ItemModText.INCOMPATIBLE_RECIPE.text());
                }
                case OK -> {
                }
            }

            if (doTransfer) {
                com.lhy.mest.compat.plus.PlusEncodingUpload.captureRecipeSearchKey(rawRecipe);
                MestEncodingHelper.encode(
                        menu,
                        kind,
                        holder,
                        craftingIngredients(slotsView),
                        inputs,
                        outputs,
                        stack -> ingredientVisibility.isIngredientVisible(VanillaTypes.ITEM_STACK, stack));
                return null;
            }

            return new EncodingTransferError(findCraftableSlots(menu, slotsView));
        }

        private static List<List<GenericStack>> craftingIngredients(IRecipeSlotsView slotsView) {
            var inputSlots = slotsView.getSlotViews(RecipeIngredientRole.INPUT);
            var result = new java.util.ArrayList<List<GenericStack>>(CRAFTING_GRID_SIZE);
            for (int i = 0; i < CRAFTING_GRID_SIZE; i++) {
                if (i < inputSlots.size()) {
                    result.add(inputSlots.get(i).getAllIngredients()
                            .map(MestPatternEncodingTransferHandler::toGenericStack)
                            .filter(java.util.Objects::nonNull)
                            .toList());
                } else {
                    result.add(List.of());
                }
            }
            return result;
        }

        private static List<List<GenericStack>> genericInputs(IRecipeSlotsView slotsView) {
            return slotsView.getSlotViews(RecipeIngredientRole.INPUT).stream()
                    .map(slot -> slot.getAllIngredients()
                            .map(MestPatternEncodingTransferHandler::toGenericStack)
                            .filter(java.util.Objects::nonNull)
                            .toList())
                    .filter(slot -> !slot.isEmpty())
                    .toList();
        }

        private static List<GenericStack> genericOutputs(IRecipeSlotsView slotsView) {
            return slotsView.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                    .map(slot -> slot.getAllIngredients()
                            .map(MestPatternEncodingTransferHandler::toGenericStack)
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        private static GenericStack toGenericStack(ITypedIngredient<?> ingredient) {
            ItemStack item = ingredient.getCastIngredient(VanillaTypes.ITEM_STACK);
            if (item != null && !item.isEmpty()) {
                return GenericStack.fromItemStack(item);
            }
            FluidStack fluid = ingredient.getCastIngredient(NeoForgeTypes.FLUID_STACK);
            if (fluid != null && !fluid.isEmpty()) {
                AEFluidKey key = AEFluidKey.of(fluid);
                return key == null ? null : new GenericStack(key, fluid.getAmount());
            }
            return null;
        }

        private static List<IRecipeSlotView> findCraftableSlots(
                MESTMenu menu,
                IRecipeSlotsView slotsView) {
            var repo = menu.getClientRepo();
            if (repo == null) {
                return List.of();
            }
            Set<AEKey> craftableKeys = repo.getAllEntries().stream()
                    .filter(GridInventoryEntry::isCraftable)
                    .map(GridInventoryEntry::getWhat)
                    .collect(Collectors.toSet());
            return slotsView.getSlotViews(RecipeIngredientRole.INPUT).stream()
                    .filter(slot -> slot.getAllIngredients()
                            .map(MestPatternEncodingTransferHandler::toGenericStack)
                            .filter(java.util.Objects::nonNull)
                            .anyMatch(stack -> craftableKeys.contains(stack.what())))
                    .toList();
        }

        private record EncodingTransferError(List<IRecipeSlotView> craftableSlots)
                implements IRecipeTransferError {
            @Override
            public Type getType() {
                return Type.COSMETIC;
            }

            @Override
            public int getButtonHighlightColor() {
                return 0;
            }

            @Override
            public void showError(
                    GuiGraphics graphics,
                    int mouseX,
                    int mouseY,
                    IRecipeSlotsView recipeSlots,
                    int recipeX,
                    int recipeY) {
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(recipeX, recipeY, 0);
                for (var slot : craftableSlots) {
                    slot.drawHighlight(graphics, CRAFTABLE_HIGHLIGHT);
                }
                pose.popPose();
            }

            @Override
            public void getTooltip(ITooltipBuilder tooltip) {
                tooltip.addAll(TransferHelper.createEncodingTooltip(!craftableSlots.isEmpty(), true));
            }
        }
    }

    /** A purely cosmetic error (highlights missing slots but allows the transfer button). */
    private static final class CosmeticTransferError implements IRecipeTransferError {
        /** ARGB button highlight when some ingredients are missing (translucent orange, was -2130729728). */
        private static final int MISSING_HIGHLIGHT = 0x80FFA500;
        /** ARGB button highlight when everything is present (translucent blue, was -2142943745). */
        private static final int CRAFTABLE_HIGHLIGHT = 0x804545FF;

        private final boolean anyMissing;

        CosmeticTransferError(boolean anyMissing) {
            this.anyMissing = anyMissing;
        }

        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return anyMissing ? MISSING_HIGHLIGHT : CRAFTABLE_HIGHLIGHT;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            tooltip.add(ItemModText.NO_ITEMS.text());
        }
    }
}
