package com.lhy.mest.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.Icon;
import appeng.integration.modules.emi.EmiStackHelper;
import appeng.integration.modules.emi.EmiUseCraftingRecipeHandler;
import appeng.integration.modules.itemlists.CraftingHelper;
import appeng.integration.modules.itemlists.TransferHelper;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.items.CraftingTermMenu;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.compat.UselessPatternBridge;
import com.lhy.mest.terminal.MESTMenu;

/**
 * EMI recipe-transfer bridge for the MEST terminal.
 *
 * <p>MEST registers its own {@link net.minecraft.world.inventory.MenuType} ({@link MESTMenu#TYPE}),
 * distinct from AE2's {@code CraftingTermMenu.TYPE}, so AE2's built-in EMI handler (which is keyed to
 * the latter) never fires for MEST. Because {@link MESTMenu} extends
 * {@link appeng.menu.me.items.CraftingTermMenu}, AE2's {@link EmiUseCraftingRecipeHandler} is directly
 * reusable — we just re-register it under MEST's own menu type.
 *
 * <p>When both the crafting module and the pattern-encoding module are on screen, EMI's single {@code +}
 * fill button is claimed by encoding (the extra hammer pulls into the crafting grid). The extra button
 * and the {@code +} button both use AE2's vanilla tooltips and ingredient overlays so they match a
 * stock crafting / pattern terminal.
 *
 * <p>{@link EmiEntrypoint} is auto-discovered by EMI via a classpath scan; no {@code mods.toml}
 * entrypoint block is required. The class is only loaded when EMI is present on the classpath, so the
 * mod still loads without EMI.
 */
@EmiEntrypoint
public class MestEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addScreenBoundsProvider(MESTScreen.class, screen -> {
            var bounds = screen.recipeViewerBounds();
            if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
                return null;
            }
            return new Bounds(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
        });
        registry.addExclusionArea(MESTScreen.class, (screen, consumer) -> {
            for (var zone : screen.getExclusionZones()) {
                consumer.accept(new Bounds(zone.getX(), zone.getY(), zone.getWidth(), zone.getHeight()));
            }
        });
        registry.addDragDropHandler(MESTScreen.class, new MestEmiDragDropHandler());
        registry.addRecipeHandler(MESTMenu.TYPE, new MestEmiPatternEncodingHandler());
        registry.addRecipeHandler(MESTMenu.TYPE, new EmiUseCraftingRecipeHandler<>(MESTMenu.class) {
            @Override
            public boolean supportsRecipe(EmiRecipe recipe) {
                return !MestPullItemsSupport.shouldShowExtraButton() && super.supportsRecipe(recipe);
            }
        });
        registry.addRecipeDecorator(MestEmiPlugin::decoratePullItems);
    }

    private static void decoratePullItems(EmiRecipe recipe, WidgetHolder widgets) {
        if (!MestPullItemsSupport.shouldShowExtraButton() || !isCraftingRecipe(recipe)) {
            return;
        }
        int x = recipe.getDisplayWidth() + 5 + 14;
        int y = Math.max(0, recipe.getDisplayHeight() - 12);
        widgets.add(new PullItemsWidget(x, y, recipe, holderWidgets(widgets)));
    }

    private static boolean isCraftingRecipe(EmiRecipe recipe) {
        if (recipe.getCategory().equals(VanillaEmiRecipeCategories.CRAFTING)) {
            return true;
        }
        RecipeHolder<?> holder = recipe.getBackingRecipe();
        return holder != null && holder.value() instanceof CraftingRecipe;
    }

    /**
     * EMI's {@code WidgetHolder} implementation keeps a public {@code widgets} list, but that class
     * is not part of the API jar we compile against.
     */
    @SuppressWarnings("unchecked")
    private static List<Widget> holderWidgets(WidgetHolder holder) {
        try {
            var field = holder.getClass().getField("widgets");
            Object value = field.get(holder);
            if (value instanceof List<?> list) {
                return (List<Widget>) list;
            }
        } catch (ReflectiveOperationException ignored) {
            // Recipe displays that are not WidgetGroup cannot highlight sibling slots.
        }
        return List.of();
    }

    private static Map<Integer, SlotWidget> recipeInputSlots(EmiRecipe recipe, List<Widget> widgets) {
        Map<Integer, SlotWidget> inputSlots = new HashMap<>();
        List<EmiIngredient> inputs = recipe.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            EmiIngredient ingredient = inputs.get(i);
            for (Widget widget : widgets) {
                if (widget instanceof SlotWidget slot
                        && slot.getRecipe() == null
                        && slot.getStack() == ingredient) {
                    inputSlots.put(i, slot);
                    break;
                }
            }
        }
        return inputSlots;
    }

    private static void overlaySlot(GuiGraphics graphics, SlotWidget slot, int color) {
        Bounds bounds = slot.getBounds();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, 400.0F);
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, color);
        pose.popPose();
    }

    private static List<ClientTooltipComponent> tooltipComponents(List<Component> lines) {
        return lines.stream()
                .map(Component::getVisualOrderText)
                .map(ClientTooltipComponent::create)
                .toList();
    }

    private static final class MestEmiPatternEncodingHandler implements StandardRecipeHandler<MESTMenu> {
        @Override
        public List<Slot> getInputSources(MESTMenu menu) {
            var slots = new ArrayList<Slot>();
            slots.addAll(menu.getSlots(SlotSemantics.PLAYER_INVENTORY));
            slots.addAll(menu.getSlots(SlotSemantics.PLAYER_HOTBAR));
            slots.addAll(menu.getSlots(SlotSemantics.CRAFTING_GRID));
            return slots;
        }

        @Override
        public List<Slot> getCraftingSlots(MESTMenu menu) {
            return List.of(menu.getPatternCraftingSlots());
        }

        @Override
        public @Nullable Slot getOutputSlot(MESTMenu menu) {
            return menu.getPatternCraftOutputSlot();
        }

        @Override
        public EmiPlayerInventory getInventory(AbstractContainerScreen<MESTMenu> screen) {
            // Encoding does not consume items; an empty inventory also stops EMI's default
            // "missing ingredient" overlay from painting every input red.
            return new EmiPlayerInventory(List.of());
        }

        @Override
        public boolean supportsRecipe(EmiRecipe recipe) {
            var player = Minecraft.getInstance().player;
            return player != null
                    && player.containerMenu instanceof MESTMenu menu
                    && (MestPullItemsSupport.shouldShowExtraButton()
                            || MestRecipeTransferContext.targetFor(menu)
                            == MestRecipeTransferContext.Target.PATTERN_ENCODING);
        }

        @Override
        public boolean canCraft(EmiRecipe recipe, EmiCraftContext<MESTMenu> context) {
            if (context.getType() != EmiCraftContext.Type.FILL_BUTTON || !supportsRecipe(recipe)) {
                return false;
            }
            RecipeHolder<?> holder = getRecipeHolder(context, recipe);
            Recipe<?> backing = holder != null ? holder.value() : null;
            var kind = classifyRecipe(recipe, backing);
            return MestEncodingHelper.validate(
                    kind,
                    backing,
                    !EmiStackHelper.ofInputs(recipe).isEmpty(),
                    !EmiStackHelper.ofOutputs(recipe).isEmpty()) == MestEncodingHelper.ValidationResult.OK;
        }

        @Override
        public boolean craft(EmiRecipe recipe, EmiCraftContext<MESTMenu> context) {
            if (!canCraft(recipe, context)) {
                return false;
            }
            MESTMenu menu = context.getScreenHandler();
            RecipeHolder<?> holder = getRecipeHolder(context, recipe);
            Recipe<?> backing = holder != null ? holder.value() : null;
            var inputs = EmiStackHelper.ofInputs(recipe);
            if (UselessPatternBridge.isUselessEntry(recipe.getBackingRecipe())
                    && UselessPatternBridge.encode(menu, recipe.getBackingRecipe(), inputs, EmiStackHelper.ofOutputs(recipe))) {
                Minecraft.getInstance().setScreen(context.getScreen());
                return true;
            }
            MestEncodingHelper.encode(
                    menu,
                    classifyRecipe(recipe, backing),
                    holder,
                    inputs,
                    inputs,
                    EmiStackHelper.ofOutputs(recipe),
                    stack -> true);
            Minecraft.getInstance().setScreen(context.getScreen());
            return true;
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(EmiRecipe recipe, EmiCraftContext<MESTMenu> context) {
            if (!canCraft(recipe, context)) {
                return tooltipComponents(List.of(EmiRecipeHandler.NOT_ENOUGH_INGREDIENTS));
            }
            Set<AEKey> craftable = craftableKeys(context.getScreenHandler());
            boolean anyCraftable = recipe.getInputs().stream()
                    .anyMatch(ingredient -> isCraftable(craftable, ingredient));
            return tooltipComponents(TransferHelper.createEncodingTooltip(anyCraftable, true));
        }

        @Override
        public void render(EmiRecipe recipe, EmiCraftContext<MESTMenu> context, List<Widget> widgets, GuiGraphics draw) {
            if (!canCraft(recipe, context)) {
                return;
            }
            Set<AEKey> craftable = craftableKeys(context.getScreenHandler());
            if (craftable.isEmpty()) {
                return;
            }
            for (SlotWidget slot : recipeInputSlots(recipe, widgets).values()) {
                if (isCraftable(craftable, slot.getStack())) {
                    overlaySlot(draw, slot, TransferHelper.BLUE_SLOT_HIGHLIGHT_COLOR);
                }
            }
        }

        private static Set<AEKey> craftableKeys(MESTMenu menu) {
            var repo = menu.getClientRepo();
            if (repo == null) {
                return Set.of();
            }
            return repo.getAllEntries().stream()
                    .filter(GridInventoryEntry::isCraftable)
                    .map(GridInventoryEntry::getWhat)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        private static boolean isCraftable(Set<AEKey> craftableKeys, EmiIngredient ingredient) {
            return ingredient.getEmiStacks().stream().anyMatch(stack -> {
                GenericStack generic = EmiStackHelper.toGenericStack(stack);
                return generic != null && craftableKeys.contains(generic.what());
            });
        }

        private static MestEncodingHelper.RecipeKind classifyRecipe(
                EmiRecipe recipe,
                @Nullable Recipe<?> backing) {
            return MestEncodingHelper.classifyRecipe(
                    backing,
                    recipe.getCategory().equals(VanillaEmiRecipeCategories.CRAFTING));
        }

        @Nullable
        private static RecipeHolder<?> getRecipeHolder(
                EmiCraftContext<MESTMenu> context,
                EmiRecipe recipe) {
            if (recipe.getBackingRecipe() != null) {
                return recipe.getBackingRecipe();
            }
            if (recipe.getId() != null) {
                return context.getScreenHandler().getPlayer().level()
                        .getRecipeManager()
                        .byKey(recipe.getId())
                        .orElse(null);
            }
            return null;
        }
    }

    private static final class PullItemsWidget extends Widget {
        private static final int SIZE = 12;
        private static final ResourceLocation EMI_BUTTONS =
                ResourceLocation.fromNamespaceAndPath("emi", "textures/gui/buttons.png");

        private final int x;
        private final int y;
        private final EmiRecipe recipe;
        private final List<Widget> recipeWidgets;

        private PullItemsWidget(int x, int y, EmiRecipe recipe, List<Widget> recipeWidgets) {
            this.x = x;
            this.y = y;
            this.recipe = recipe;
            this.recipeWidgets = recipeWidgets;
        }

        @Override
        public Bounds getBounds() {
            return new Bounds(x, y, SIZE, SIZE);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (!MestPullItemsSupport.shouldShowExtraButton()) {
                return;
            }
            TransferPreview preview = preview();
            boolean canFill = preview != null && !preview.allMissing;
            boolean hovered = canFill && getBounds().contains(mouseX, mouseY);
            int textureV = canFill ? (hovered ? 12 : 0) : 24;
            graphics.blit(EMI_BUTTONS, x, y, SIZE, SIZE, 72, textureV, SIZE, SIZE, 256, 256);
            var hammer = Icon.CRAFT_HAMMER.getBlitter().dest(x + 1, y + 1, 10, 10);
            if (!canFill) {
                hammer.color(0.45F, 0.45F, 0.45F);
            }
            hammer.blit(graphics);
            if (preview != null && preview.missingSlots != null) {
                renderMissingOverlays(graphics, preview.missingSlots);
            }
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            TransferPreview preview = preview();
            if (preview == null) {
                return tooltipComponents(List.of(Component.translatable("emi.inapplicable")));
            }
            if (preview.allMissing) {
                // RecipeFillButtonWidget does not prepend "Fill recipe" while disabled; the
                // handler's default failure tooltip is the entire tooltip in this state.
                return tooltipComponents(List.of(EmiRecipeHandler.NOT_ENOUGH_INGREDIENTS));
            }
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.translatable("tooltip.emi.fill_recipe"));
            if (preview.missingSlots != null && preview.missingSlots.totalSize() > 0) {
                // EMI caches recipe-button tooltips; match AE2 and describe the normal (non-Ctrl)
                // action instead of sampling the modifier while this method happens to run.
                tooltip.addAll(TransferHelper.createCraftingTooltip(
                        preview.missingSlots, false, false));
            }
            return tooltipComponents(tooltip);
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0 || !getBounds().contains(mouseX, mouseY)
                    || !MestPullItemsSupport.shouldShowExtraButton()) {
                return false;
            }
            TransferPreview preview = preview();
            if (preview == null || preview.allMissing) {
                return false;
            }
            var player = Minecraft.getInstance().player;
            if (player == null || !(player.containerMenu instanceof MESTMenu menu)) {
                return false;
            }
            RecipeHolder<?> holder = recipe.getBackingRecipe();
            if (holder == null || !(holder.value() instanceof CraftingRecipe crafting)) {
                return false;
            }
            CraftingHelper.performTransfer(menu, holder.id(), crafting, Screen.hasControlDown());
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            Screen screen = Minecraft.getInstance().screen;
            if (screen != null && !(screen instanceof MESTScreen)) {
                screen.onClose();
            }
            return true;
        }

        private void renderMissingOverlays(GuiGraphics graphics, CraftingTermMenu.MissingIngredientSlots missing) {
            Map<Integer, SlotWidget> inputSlots = recipeInputSlots(recipe, recipeWidgets);
            for (Map.Entry<Integer, SlotWidget> entry : inputSlots.entrySet()) {
                boolean isMissing = missing.missingSlots().contains(entry.getKey());
                boolean craftable = missing.craftableSlots().contains(entry.getKey());
                if (!isMissing && !craftable) {
                    continue;
                }
                overlaySlot(graphics, entry.getValue(),
                        isMissing ? TransferHelper.RED_SLOT_HIGHLIGHT_COLOR : TransferHelper.BLUE_SLOT_HIGHLIGHT_COLOR);
            }
        }

        @Nullable
        private TransferPreview preview() {
            var player = Minecraft.getInstance().player;
            RecipeHolder<?> holder = recipe.getBackingRecipe();
            if (player == null || holder == null || !(holder.value() instanceof CraftingRecipe)
                    || !(player.containerMenu instanceof MESTMenu menu)) {
                return null;
            }
            Map<Integer, Ingredient> ingredients =
                    EmiUseCraftingRecipeHandler.getGuiSlotToIngredientMap(holder.value());
            CraftingTermMenu.MissingIngredientSlots missing = menu.findMissingIngredients(ingredients);
            boolean allMissing = !ingredients.isEmpty()
                    && missing.missingSlots().size() == ingredients.size();
            return new TransferPreview(missing, allMissing);
        }

        private record TransferPreview(
                CraftingTermMenu.MissingIngredientSlots missingSlots,
                boolean allMissing) {
        }
    }

    private static final class MestEmiDragDropHandler implements EmiDragDropHandler<MESTScreen> {
        @Override
        public boolean dropStack(MESTScreen screen, EmiIngredient ingredient, int x, int y) {
            for (var emiStack : ingredient.getEmiStacks()) {
                GenericStack stack = EmiStackHelper.toGenericStack(emiStack);
                if (stack != null && screen.dropRecipeViewerStack(stack, x, y)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void render(
                MESTScreen screen,
                EmiIngredient dragged,
                GuiGraphics draw,
                int mouseX,
                int mouseY,
                float delta) {
            Set<GenericStack> stacks = dragged.getEmiStacks().stream()
                    .map(EmiStackHelper::toGenericStack)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            screen.renderRecipeViewerDropTargets(draw, stacks);
        }
    }
}
