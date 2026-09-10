package com.lhy.mest.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.lhy.mest.registry.ModComponents;
import com.lhy.mest.registry.ModItems;

/**
 * Draws the toolkit quick bars beside the vanilla hotbar.
 *
 * <p>Left bar maps toolkit slots 0-8, right bar maps 9-17 — together the first 18 toolkit slots.
 * The bar is read straight from the terminal item's {@code TOOLKIT_INV} component, which is
 * network-synchronized, so there is no extra packet traffic and no per-frame server work.
 */
public final class ToolkitBarHud {
    public static final int SLOT = 18;
    public static final int BAR_SLOTS = 9;
    private static final int HOTBAR_WIDTH = 182;
    private static final int MARGIN = 6;
    private static final int BG = 0x99000000;
    private static final int BG_EMPTY = 0x66000000;

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
        int y = g.guiHeight() - 22;
        drawBar(g, minecraft, contents, 0, leftX(g.guiWidth()), y);
        drawBar(g, minecraft, contents, BAR_SLOTS, rightX(g.guiWidth()), y);
    }

    public static int leftX(int guiWidth) {
        return guiWidth / 2 - HOTBAR_WIDTH / 2 - MARGIN - BAR_SLOTS * SLOT;
    }

    public static int rightX(int guiWidth) {
        return guiWidth / 2 + HOTBAR_WIDTH / 2 + MARGIN;
    }

    public static int barY(int guiHeight) {
        return guiHeight - 22;
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
        if (mouseY < barY || mouseY >= barY + SLOT || mouseX < barX) {
            return -1;
        }
        int offset = (int) ((mouseX - barX) / SLOT);
        return offset >= BAR_SLOTS ? -1 : firstSlot + offset;
    }

    private static void drawBar(
            GuiGraphics g, Minecraft minecraft, ItemContainerContents contents, int firstSlot, int x, int y) {
        for (int i = 0; i < BAR_SLOTS; i++) {
            int cellX = x + i * SLOT;
            ItemStack stack = stackAt(contents, firstSlot + i);
            g.fill(cellX, y, cellX + SLOT - 2, y + SLOT - 2, stack.isEmpty() ? BG_EMPTY : BG);
            if (!stack.isEmpty()) {
                g.renderItem(stack, cellX + 1, y + 1);
                g.renderItemDecorations(minecraft.font, stack, cellX + 1, y + 1);
            }
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

    /** First spliced terminal in the player's inventory (hotbar, main, offhand) with bars enabled. */
    public static ItemStack findTerminal(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isTerminal(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.SPLICED_TERMINAL.get());
    }
}
