package com.lhy.mest.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.registry.ModItems;

/**
 * Draws the toolkit quick bars beside the vanilla hotbar, using the same {@code hud/hotbar} sprite.
 *
 * <p>Left bar maps toolkit slots 0-8, right bar maps 9-17 — together the first 18 toolkit slots.
 * The bar is read straight from the terminal item's {@code TOOLKIT_INV} component, which is
 * network-synchronized, so there is no extra packet traffic and no per-frame server work.
 */
public final class ToolkitBarHud {
    private static final ResourceLocation HOTBAR_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/hotbar");
    public static final int SLOT = 20;
    public static final int BAR_SLOTS = 9;
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 22;
    private static final int HOTBAR_HALF = 91;
    private static final int OFFHAND_WIDTH = 29;
    private static final int ITEM_INSET = 3;

    private ToolkitBarHud() {}

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        ItemStack terminal = findTerminal(player);
        if (!isBarEnabled(terminal)) {
            return;
        }
        ItemContainerContents contents = terminal.getOrDefault(
                ModComponents.TOOLKIT_INV.get(), ItemContainerContents.EMPTY);
        int y = barY(g.guiHeight());
        drawBar(g, minecraft, contents, 0, leftX(g.guiWidth()), y);
        drawBar(g, minecraft, contents, BAR_SLOTS, rightX(g.guiWidth()), y);
    }

    public static int leftX(int guiWidth) {
        return guiWidth / 2 - HOTBAR_HALF - OFFHAND_WIDTH - BAR_WIDTH;
    }

    public static int rightX(int guiWidth) {
        return guiWidth / 2 + HOTBAR_HALF + OFFHAND_WIDTH;
    }

    public static int barY(int guiHeight) {
        return guiHeight - BAR_HEIGHT;
    }

    /**
     * Index into the toolkit inventory for a click at screen coordinates, or -1 when the pointer is
     * not over either bar. Left bar yields 0-8, right bar yields 9-17.
     */
    public static int slotAt(int guiWidth, int guiHeight, double mouseX, double mouseY) {
        int y = barY(guiHeight);
        int left = hitBar(leftX(guiWidth), y, mouseX, mouseY, 0);
        return left >= 0 ? left : hitBar(rightX(guiWidth), y, mouseX, mouseY, BAR_SLOTS);
    }

    private static int hitBar(int barX, int barY, double mouseX, double mouseY, int firstSlot) {
        if (mouseY < barY || mouseY >= barY + BAR_HEIGHT
                || mouseX < barX || mouseX >= barX + BAR_WIDTH) {
            return -1;
        }
        int offset = (int) ((mouseX - barX - ITEM_INSET) / SLOT);
        return offset < 0 || offset >= BAR_SLOTS ? -1 : firstSlot + offset;
    }

    private static void drawBar(
            GuiGraphics g, Minecraft minecraft, ItemContainerContents contents, int firstSlot, int x, int y) {
        RenderSystem.enableBlend();
        g.pose().pushPose();
        try {
            g.pose().translate(0.0F, 0.0F, -90.0F);
            g.blitSprite(HOTBAR_SPRITE, x, y, BAR_WIDTH, BAR_HEIGHT);
        } finally {
            g.pose().popPose();
            RenderSystem.disableBlend();
        }
        for (int i = 0; i < BAR_SLOTS; i++) {
            ItemStack stack = stackAt(contents, firstSlot + i);
            if (stack.isEmpty()) {
                continue;
            }
            int itemX = x + ITEM_INSET + i * SLOT;
            int itemY = y + ITEM_INSET;
            g.renderItem(stack, itemX, itemY);
            g.renderItemDecorations(minecraft.font, stack, itemX, itemY);
        }
    }

    private static ItemStack stackAt(ItemContainerContents contents, int index) {
        if (index < 0 || index >= contents.getSlots()) {
            return ItemStack.EMPTY;
        }
        return contents.getStackInSlot(index);
    }

    /** True when the given terminal stack has the quick bars toggled on. */
    public static boolean isBarEnabled(ItemStack terminal) {
        return !terminal.isEmpty() && terminal.getOrDefault(ModComponents.TOOLKIT_BAR.get(), false);
    }

    /** First spliced terminal in the player's inventory with bars enabled, else the first terminal. */
    public static ItemStack findTerminal(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        ItemStack first = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isTerminal(stack)) {
                continue;
            }
            if (isBarEnabled(stack)) {
                return stack;
            }
            if (first.isEmpty()) {
                first = stack;
            }
        }
        return first;
    }

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.SPLICED_TERMINAL.get());
    }
}
