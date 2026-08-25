package com.lhy.mest.integration;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;

import appeng.client.gui.Icon;
import appeng.integration.modules.emi.EmiStackHelper;
import appeng.integration.modules.emi.EmiUseCraftingRecipeHandler;
import appeng.integration.modules.itemlists.CraftingHelper;
import appeng.menu.SlotSemantics;

import com.lhy.mest.client.MESTScreen;
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
        widgets.add(new PullItemsWidget(x, y, recipe));
    }

    private static boolean isCraftingRecipe(EmiRecipe recipe) {
        if (recipe.getCategory().equals(VanillaEmiRecipeCategories.CRAFTING)) {
            return true;
        }
        RecipeHolder<?> holder = recipe.getBackingRecipe();
        return holder != null && holder.value() instanceof CraftingRecipe;
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

        private PullItemsWidget(int x, int y, EmiRecipe recipe) {
            this.x = x;
            this.y = y;
            this.recipe = recipe;
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
            boolean hovered = getBounds().contains(mouseX, mouseY);
            graphics.blit(EMI_BUTTONS, x, y, SIZE, SIZE, 72, hovered ? 12 : 0, SIZE, SIZE, 256, 256);
            Icon.CRAFT_HAMMER.getBlitter().dest(x + 1, y + 1, 10, 10).blit(graphics);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            return List.of(ClientTooltipComponent.create(
                    Component.translatable("gui.mesplicedterminal.recipe.pull_items").getVisualOrderText()));
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0 || !getBounds().contains(mouseX, mouseY)
                    || !MestPullItemsSupport.shouldShowExtraButton()) {
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
            if (screen != null) {
                screen.onClose();
            }
            return true;
        }
    }
}
