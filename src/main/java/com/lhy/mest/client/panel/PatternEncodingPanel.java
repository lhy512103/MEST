package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.inventory.Slot;

import appeng.menu.slot.AppEngSlot;
import appeng.parts.encoding.EncodingMode;

import com.lhy.mest.client.dock.ModulePanel;
import com.lhy.mest.client.dock.Scrollbar;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Floating pattern encoder module. Server-side behavior is provided by {@link MESTMenu}; this panel
 * only lays out AE2-style slots and sends high-level menu actions for mode/options/encoding.
 */
public class PatternEncodingPanel extends ModulePanel {
    private static final int SLOT = 18;
    private static final int GAP = 4;
    private static final int BUTTON_H = 14;
    private static final int TAB_W = 38;
    private static final int STONECUTTING_COLS = 4;
    private static final int STONECUTTING_ROWS = 2;

    private final MESTMenu menu;
    private int processingScroll;
    private int stonecuttingScroll;
    private final Scrollbar scrollbar = new Scrollbar();
    private RecipeHolder<StonecutterRecipe> hoveredStonecuttingRecipe;
    private EncodingMode lastLayoutMode;
    private int lastLayoutX = Integer.MIN_VALUE;
    private int lastLayoutY = Integer.MIN_VALUE;
    private int lastLayoutWidth = Integer.MIN_VALUE;
    private int lastLayoutHeight = Integer.MIN_VALUE;
    private boolean lastLayoutVisible;
    private boolean lastLayoutHosted;

    public PatternEncodingPanel(MESTMenu menu) {
        this.menu = menu;

        for (Slot slot : menu.getPatternCraftingSlots()) {
            registerSlot(slot);
        }
        registerSlot(menu.getPatternCraftOutputSlot());
        for (Slot slot : menu.getProcessingInputSlots()) {
            registerSlot(slot);
        }
        for (Slot slot : menu.getProcessingOutputSlots()) {
            registerSlot(slot);
        }
        registerSlot(menu.getStonecuttingInputSlot());
        registerSlot(menu.getSmithingTableTemplateSlot());
        registerSlot(menu.getSmithingTableBaseSlot());
        registerSlot(menu.getSmithingTableAdditionSlot());
        registerSlot(menu.getBlankPatternSlot());
        registerSlot(menu.getEncodedPatternSlot());
    }

    @Override
    public String id() {
        return "pattern_encoding";
    }

    @Override
    public Component title() {
        return Component.translatable("gui.mesplicedterminal.pattern_encoding");
    }

    @Override
    public int defaultWidth() {
        return 214;
    }

    @Override
    public int defaultHeight() {
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + 102;
    }

    @Override
    public int minWidth() {
        return defaultWidth();
    }

    @Override
    public int minHeight() {
        return defaultHeight();
    }

    public void tick() {
        if (layoutStateChanged()) {
            layoutSlots();
        }
    }

    @Override
    public void layoutSlots() {
        hideOwnedSlots();
        if (!visible) {
            rememberLayoutState();
            return;
        }

        layoutPatternStorageSlots();

        EncodingMode mode = menu.getPatternEncodingMode();
        if (mode == EncodingMode.CRAFTING) {
            layoutCraftingSlots();
        } else if (mode == EncodingMode.PROCESSING) {
            layoutProcessingSlots();
        } else if (mode == EncodingMode.SMITHING_TABLE) {
            layoutSmithingSlots();
        } else if (mode == EncodingMode.STONECUTTING) {
            layoutStonecuttingSlots();
        }
        rememberLayoutState();
    }

    private boolean layoutStateChanged() {
        return lastLayoutMode != menu.getPatternEncodingMode()
                || lastLayoutX != x
                || lastLayoutY != y
                || lastLayoutWidth != width
                || lastLayoutHeight != height
                || lastLayoutVisible != visible
                || lastLayoutHosted != hosted;
    }

    private void rememberLayoutState() {
        lastLayoutMode = menu.getPatternEncodingMode();
        lastLayoutX = x;
        lastLayoutY = y;
        lastLayoutWidth = width;
        lastLayoutHeight = height;
        lastLayoutVisible = visible;
        lastLayoutHosted = hosted;
    }

    private void hideOwnedSlots() {
        for (Slot slot : ownedSlots()) {
            slot.x = -9999;
            slot.y = -9999;
        }
    }

    private void setSlot(Slot slot, int x, int y) {
        if (slot instanceof AppEngSlot appEngSlot && !appEngSlot.isActive()) {
            return;
        }
        slot.x = x;
        slot.y = y;
    }

    private int workTop() {
        return contentTop() + BUTTON_H + GAP + 2;
    }

    private void layoutPatternStorageSlots() {
        int right = contentLeft() + contentWidth();
        int y = workTop();
        setSlot(menu.getBlankPatternSlot(), right - SLOT * 2 - GAP, y);
        setSlot(menu.getEncodedPatternSlot(), right - SLOT, y);
    }

    private void layoutCraftingSlots() {
        int left = contentLeft();
        int top = workTop();
        var slots = menu.getPatternCraftingSlots();
        for (int i = 0; i < slots.length; i++) {
            setSlot(slots[i], left + (i % 3) * SLOT, top + (i / 3) * SLOT);
        }
        setSlot(menu.getPatternCraftOutputSlot(), left + 3 * SLOT + 24, top + SLOT);
    }

    private void layoutProcessingSlots() {
        int left = contentLeft();
        int top = workTop();
        int maxScroll = maxProcessingScroll();
        if (processingScroll > maxScroll) {
            processingScroll = maxScroll;
        }

        var inputs = menu.getProcessingInputSlots();
        for (int i = 0; i < inputs.length; i++) {
            int row = i / 3 - processingScroll;
            if (row >= 0 && row < 3) {
                setSlot(inputs[i], left + (i % 3) * SLOT, top + row * SLOT);
            }
        }

        int outLeft = left + 3 * SLOT + 26;
        var outputs = menu.getProcessingOutputSlots();
        for (int i = 0; i < outputs.length; i++) {
            int row = i / 3 - processingScroll;
            if (row >= 0 && row < 3) {
                setSlot(outputs[i], outLeft + (i % 3) * SLOT, top + row * SLOT);
            }
        }
    }

    private void layoutSmithingSlots() {
        int left = contentLeft();
        int top = workTop() + SLOT;
        setSlot(menu.getSmithingTableTemplateSlot(), left, top);
        setSlot(menu.getSmithingTableBaseSlot(), left + SLOT + GAP, top);
        setSlot(menu.getSmithingTableAdditionSlot(), left + 2 * (SLOT + GAP), top);
    }

    private void layoutStonecuttingSlots() {
        setSlot(menu.getStonecuttingInputSlot(), contentLeft(), workTop() + SLOT);
    }

    @Override
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        renderModeTabs(g, font, mouseX, mouseY);
        renderActionButtons(g, font, mouseX, mouseY);

        EncodingMode mode = menu.getPatternEncodingMode();
        if (mode == EncodingMode.CRAFTING) {
            renderCraftingBackground(g, font);
        } else if (mode == EncodingMode.PROCESSING) {
            renderProcessingBackground(g, font);
        } else if (mode == EncodingMode.SMITHING_TABLE) {
            renderSmithingBackground(g, font);
        } else if (mode == EncodingMode.STONECUTTING) {
            renderStonecuttingBackground(g, font, mouseX, mouseY);
        }
        renderPatternStorageBackground(g, font);
    }

    @Override
    public void renderForegroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        List<Component> tooltip = tooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void renderModeTabs(GuiGraphics g, Font font, int mouseX, int mouseY) {
        int x = contentLeft();
        int y = contentTop();
        for (EncodingMode mode : EncodingMode.values()) {
            Rect r = tabRect(mode, x, y);
            boolean selected = menu.getPatternEncodingMode() == mode;
            drawButton(g, font, r, modeLabel(mode), selected || r.contains(mouseX, mouseY));
        }
    }

    private Rect tabRect(EncodingMode mode, int left, int top) {
        return new Rect(left + mode.ordinal() * (TAB_W + 2), top, TAB_W, BUTTON_H);
    }

    private void renderActionButtons(GuiGraphics g, Font font, int mouseX, int mouseY) {
        for (ActionButton button : actionButtons()) {
            drawButton(g, font, button.rect(), button.label(),
                    button.selected() || button.rect().contains(mouseX, mouseY));
        }
    }

    private List<ActionButton> actionButtons() {
        List<ActionButton> buttons = new ArrayList<>();
        int y = contentTop() + contentHeight() - BUTTON_H;
        int x = contentLeft();
        buttons.add(new ActionButton("encode",
                new Rect(x, y, 42, BUTTON_H),
                Component.translatable("gui.mesplicedterminal.pattern_encode"), false));
        buttons.add(new ActionButton("clear",
                new Rect(x + 44, y, 36, BUTTON_H),
                Component.translatable("gui.mesplicedterminal.pattern_clear"), false));

        EncodingMode mode = menu.getPatternEncodingMode();
        if (mode == EncodingMode.CRAFTING || mode == EncodingMode.SMITHING_TABLE
                || mode == EncodingMode.STONECUTTING) {
            buttons.add(new ActionButton("substitute",
                    new Rect(x + 84, y, 38, BUTTON_H),
                    Component.translatable("gui.mesplicedterminal.pattern_substitute_short"),
                    menu.isPatternSubstitute()));
        }
        if (mode == EncodingMode.CRAFTING) {
            buttons.add(new ActionButton("fluid_substitute",
                    new Rect(x + 124, y, 38, BUTTON_H),
                    Component.translatable("gui.mesplicedterminal.pattern_fluid_substitute_short"),
                    menu.isPatternFluidSubstitute()));
        }
        if (mode == EncodingMode.PROCESSING && menu.canCycleProcessingOutputs()) {
            buttons.add(new ActionButton("cycle",
                    new Rect(x + 84, y, 42, BUTTON_H),
                    Component.translatable("gui.mesplicedterminal.pattern_cycle"), false));
        }
        return buttons;
    }

    private void drawButton(GuiGraphics g, Font font, Rect rect, Component label, boolean selected) {
        ModulePanel.drawButton(g, font, label, rect.x(), rect.y(), rect.w(), rect.h(), selected);
    }

    private void renderCraftingBackground(GuiGraphics g, Font font) {
        for (Slot slot : menu.getPatternCraftingSlots()) {
            drawSlotIfVisible(g, slot);
        }
        drawArrow(g, contentLeft() + 3 * SLOT + 5, workTop() + SLOT + 4);
        drawSlotIfVisible(g, menu.getPatternCraftOutputSlot());
    }

    private void renderProcessingBackground(GuiGraphics g, Font font) {
        for (Slot slot : menu.getProcessingInputSlots()) {
            drawSlotIfVisible(g, slot);
        }
        drawArrow(g, contentLeft() + 3 * SLOT + 6, workTop() + SLOT + 4);
        for (Slot slot : menu.getProcessingOutputSlots()) {
            drawSlotIfVisible(g, slot);
        }
        if (maxProcessingScroll() > 0) {
            int trackX = processingScrollbarTrackX();
            int trackY = workTop();
            int trackH = 3 * SLOT;
            scrollbar.setScroll(processingScroll);
            scrollbar.render(g, trackX, trackY, 8, trackH, maxProcessingScroll());
        }
    }

    private void renderSmithingBackground(GuiGraphics g, Font font) {
        drawSlotIfVisible(g, menu.getSmithingTableTemplateSlot());
        drawSlotIfVisible(g, menu.getSmithingTableBaseSlot());
        drawSlotIfVisible(g, menu.getSmithingTableAdditionSlot());
        int resultX = contentLeft() + 3 * (SLOT + GAP) + 28;
        int resultY = workTop() + SLOT;
        drawArrow(g, contentLeft() + 3 * (SLOT + GAP) + 5, resultY + 4);
        ModulePanel.drawSlot(g, resultX - 1, resultY - 1);
        ItemStack result = getSmithingResult();
        if (!result.isEmpty()) {
            g.renderItem(result, resultX, resultY);
            g.renderItemDecorations(font, result, resultX, resultY);
        }
    }

    private void renderStonecuttingBackground(GuiGraphics g, Font font, int mouseX, int mouseY) {
        drawSlotIfVisible(g, menu.getStonecuttingInputSlot());
        hoveredStonecuttingRecipe = null;
        int left = contentLeft() + SLOT + 18;
        int top = workTop();
        var recipes = menu.getStonecuttingRecipes();
        var selected = menu.getStonecuttingRecipeId();
        stonecuttingScroll = Math.min(stonecuttingScroll, maxStonecuttingScroll());
        int start = stonecuttingScroll * STONECUTTING_COLS;
        int end = Math.min(recipes.size(), start + STONECUTTING_COLS * STONECUTTING_ROWS);
        for (int i = start; i < end; i++) {
            var recipe = recipes.get(i);
            int visibleIndex = i - start;
            int x = left + (visibleIndex % STONECUTTING_COLS) * (SLOT + 2);
            int y = top + (visibleIndex / STONECUTTING_COLS) * (SLOT + 4);
            boolean isSelected = selected != null && selected.equals(recipe.id());
            boolean hover = contains(mouseX, mouseY, x - 1, y - 1, SLOT, SLOT);
            ModulePanel.drawSlot(g, x - 1, y - 1);
            if (isSelected) {
                g.fill(x - 1, y - 1, x + 17, y, 0xFF6AA84F);
                g.fill(x - 1, y - 1, x, y + 17, 0xFF6AA84F);
            } else if (hover) {
                g.fill(x - 1, y - 1, x + 17, y + 17, 0x30FFFFFF);
            }
            ItemStack result = recipe.value().getResultItem(menu.getPlayer().registryAccess());
            g.renderItem(result, x, y);
            g.renderItemDecorations(font, result, x, y);
            if (hover) {
                hoveredStonecuttingRecipe = recipe;
            }
        }
        if (maxStonecuttingScroll() > 0) {
            scrollbar.setScroll(stonecuttingScroll);
            scrollbar.render(g,
                    stonecuttingScrollbarTrackX(), stonecuttingScrollbarTrackY(),
                    8, stonecuttingScrollbarTrackH(),
                    maxStonecuttingScroll());
        }
    }

    private void renderPatternStorageBackground(GuiGraphics g, Font font) {
        drawSlotIfVisible(g, menu.getBlankPatternSlot());
        drawSlotIfVisible(g, menu.getEncodedPatternSlot());
        int labelY = workTop() + SLOT + 3;
        g.drawString(font, Component.translatable("gui.mesplicedterminal.pattern_storage"),
                contentLeft() + contentWidth() - 82, labelY, COLOR_TITLE_TEXT, false);
    }

    private void drawSlotIfVisible(GuiGraphics g, Slot slot) {
        if (slot.x > -1000 && slot.y > -1000) {
            ModulePanel.drawSlot(g, slot.x - 1, slot.y - 1);
        }
    }

    private void drawArrow(GuiGraphics g, int x, int y) {
        ModulePanel.drawCraftingArrow(g, x, y);
    }

    private ItemStack getSmithingResult() {
        var input = new SmithingRecipeInput(
                menu.getSmithingTableTemplateSlot().getItem(),
                menu.getSmithingTableBaseSlot().getItem(),
                menu.getSmithingTableAdditionSlot().getItem());
        var level = menu.getPlayer().level();
        var recipe = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMITHING, input, level)
                .orElse(null);
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        return recipe.value().assemble(input, level.registryAccess());
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0 || !contains(mx, my)) {
            return false;
        }

        for (EncodingMode mode : EncodingMode.values()) {
            if (tabRect(mode, contentLeft(), contentTop()).contains(mx, my)) {
                menu.setPatternEncodingMode(mode);
                return true;
            }
        }
        for (ActionButton action : actionButtons()) {
            if (action.rect().contains(mx, my)) {
                runAction(action.id());
                return true;
            }
        }
        if (menu.getPatternEncodingMode() == EncodingMode.STONECUTTING) {
            var recipe = stonecuttingRecipeAt(mx, my);
            if (recipe != null) {
                menu.setStonecuttingRecipeId(recipe.id());
                Minecraft.getInstance().getSoundManager()
                        .play(SimpleSoundInstance.forUI(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1.0F));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!visible || !contains(mx, my) || scrollY == 0) {
            return false;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.PROCESSING && maxProcessingScroll() > 0) {
            processingScroll = Math.max(0, Math.min(maxProcessingScroll(), processingScroll - (int) Math.signum(scrollY)));
            scrollbar.setScroll(processingScroll);
            layoutSlots();
            return true;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.STONECUTTING && maxStonecuttingScroll() > 0) {
            stonecuttingScroll = Math.max(0,
                    Math.min(maxStonecuttingScroll(), stonecuttingScroll - (int) Math.signum(scrollY)));
            scrollbar.setScroll(stonecuttingScroll);
            return true;
        }
        return false;
    }

    // --- Processing scrollbar drag interaction ---------------------------

    private int processingScrollbarTrackX() {
        return contentLeft() + 6 * SLOT + 30;
    }

    private int processingScrollbarTrackY() {
        return workTop();
    }

    private int processingScrollbarTrackH() {
        return 3 * SLOT;
    }

    @Override
    public boolean scrollbarPressed(double mx, double my) {
        if (!visible) {
            return false;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.PROCESSING && maxProcessingScroll() > 0) {
            if (!(mx >= processingScrollbarTrackX() && mx < processingScrollbarTrackX() + 8
                    && my >= processingScrollbarTrackY()
                    && my < processingScrollbarTrackY() + processingScrollbarTrackH())) {
                return false;
            }
            scrollbar.setScroll(processingScroll);
            boolean consumed = scrollbar.mousePressed(mx, my,
                    processingScrollbarTrackX(), processingScrollbarTrackY(), 8, processingScrollbarTrackH(),
                    3, maxProcessingScroll());
            if (consumed) {
                processingScroll = scrollbar.scroll();
                layoutSlots();
            }
            return consumed;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.STONECUTTING && maxStonecuttingScroll() > 0) {
            if (!(mx >= stonecuttingScrollbarTrackX() && mx < stonecuttingScrollbarTrackX() + 8
                    && my >= stonecuttingScrollbarTrackY()
                    && my < stonecuttingScrollbarTrackY() + stonecuttingScrollbarTrackH())) {
                return false;
            }
            scrollbar.setScroll(stonecuttingScroll);
            boolean consumed = scrollbar.mousePressed(mx, my,
                    stonecuttingScrollbarTrackX(), stonecuttingScrollbarTrackY(), 8, stonecuttingScrollbarTrackH(),
                    STONECUTTING_ROWS, maxStonecuttingScroll());
            if (consumed) {
                stonecuttingScroll = scrollbar.scroll();
            }
            return consumed;
        }
        return false;
    }

    @Override
    public boolean scrollbarDragged(double mx, double my) {
        if (!scrollbar.isDragging()) {
            return false;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.PROCESSING) {
            scrollbar.mouseDragged(my,
                    processingScrollbarTrackY(), processingScrollbarTrackH(),
                    maxProcessingScroll());
            processingScroll = scrollbar.scroll();
            layoutSlots();
            return true;
        }
        if (menu.getPatternEncodingMode() == EncodingMode.STONECUTTING) {
            scrollbar.mouseDragged(my,
                    stonecuttingScrollbarTrackY(), stonecuttingScrollbarTrackH(),
                    maxStonecuttingScroll());
            stonecuttingScroll = scrollbar.scroll();
            return true;
        }
        return false;
    }

    @Override
    public void scrollbarReleased() {
        scrollbar.mouseReleased();
    }

    @Override
    public boolean scrollbarDragging() {
        return scrollbar.isDragging();
    }

    private void runAction(String id) {
        switch (id) {
            case "encode" -> menu.encodePattern();
            case "clear" -> menu.clearPatternEncoding();
            case "substitute" -> menu.setPatternSubstitute(!menu.isPatternSubstitute());
            case "fluid_substitute" -> menu.setPatternFluidSubstitute(!menu.isPatternFluidSubstitute());
            case "cycle" -> menu.cycleProcessingOutput();
            default -> {
            }
        }
    }

    private RecipeHolder<StonecutterRecipe> stonecuttingRecipeAt(double mx, double my) {
        int left = contentLeft() + SLOT + 18;
        int top = workTop();
        var recipes = menu.getStonecuttingRecipes();
        int start = stonecuttingScroll * STONECUTTING_COLS;
        int end = Math.min(recipes.size(), start + STONECUTTING_COLS * STONECUTTING_ROWS);
        for (int i = start; i < end; i++) {
            int visibleIndex = i - start;
            int x = left + (visibleIndex % STONECUTTING_COLS) * (SLOT + 2);
            int y = top + (visibleIndex / STONECUTTING_COLS) * (SLOT + 4);
            if (contains(mx, my, x - 1, y - 1, SLOT, SLOT)) {
                return recipes.get(i);
            }
        }
        return null;
    }

    private int maxProcessingScroll() {
        return Math.max(0, menu.getProcessingInputSlots().length / 3 - 3);
    }

    private int stonecuttingTotalRows() {
        return (menu.getStonecuttingRecipes().size() + STONECUTTING_COLS - 1) / STONECUTTING_COLS;
    }

    private int maxStonecuttingScroll() {
        return Math.max(0, stonecuttingTotalRows() - STONECUTTING_ROWS);
    }

    private int stonecuttingScrollbarTrackX() {
        return contentLeft() + SLOT + 18 + STONECUTTING_COLS * (SLOT + 2) + 2;
    }

    private int stonecuttingScrollbarTrackY() {
        return workTop();
    }

    private int stonecuttingScrollbarTrackH() {
        return STONECUTTING_ROWS * (SLOT + 4);
    }

    private List<Component> tooltipAt(int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        for (EncodingMode mode : EncodingMode.values()) {
            if (tabRect(mode, contentLeft(), contentTop()).contains(mouseX, mouseY)) {
                lines.add(modeTooltip(mode));
                return lines;
            }
        }
        for (ActionButton button : actionButtons()) {
            if (button.rect().contains(mouseX, mouseY)) {
                lines.add(Component.translatable("gui.mesplicedterminal.pattern_action." + button.id()));
                if (button.selected()) {
                    lines.add(Component.translatable("gui.mesplicedterminal.pattern_action.enabled")
                            .withStyle(ChatFormatting.GRAY));
                }
                return lines;
            }
        }
        if (hoveredStonecuttingRecipe != null) {
            lines.add(hoveredStonecuttingRecipe.value()
                    .getResultItem(menu.getPlayer().registryAccess())
                    .getHoverName());
        }
        return lines;
    }

    private static Component modeLabel(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> Component.translatable("gui.mesplicedterminal.pattern_mode.crafting.short");
            case PROCESSING -> Component.translatable("gui.mesplicedterminal.pattern_mode.processing.short");
            case SMITHING_TABLE -> Component.translatable("gui.mesplicedterminal.pattern_mode.smithing.short");
            case STONECUTTING -> Component.translatable("gui.mesplicedterminal.pattern_mode.stonecutting.short");
        };
    }

    private static Component modeTooltip(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> Component.translatable("gui.mesplicedterminal.pattern_mode.crafting");
            case PROCESSING -> Component.translatable("gui.mesplicedterminal.pattern_mode.processing");
            case SMITHING_TABLE -> Component.translatable("gui.mesplicedterminal.pattern_mode.smithing");
            case STONECUTTING -> Component.translatable("gui.mesplicedterminal.pattern_mode.stonecutting");
        };
    }

    private static boolean contains(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return PatternEncodingPanel.contains(mx, my, x, y, w, h);
        }
    }

    private record ActionButton(String id, Rect rect, Component label, boolean selected) {
    }
}
