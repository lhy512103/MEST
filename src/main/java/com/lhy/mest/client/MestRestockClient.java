package com.lhy.mest.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Client cache of ME restock amounts, matching wtlib CraftingTerminalHandler.restockAbleItems. */
public final class MestRestockClient {
    private static Map<Item, Long> amounts = new HashMap<>();

    private MestRestockClient() {
    }

    public static void setAmounts(Map<Item, Long> items) {
        amounts = items;
    }

    public static boolean isRestockAble(ItemStack stack) {
        return amounts.containsKey(stack.getItem());
    }

    public static long accessibleAmount(ItemStack stack) {
        return (long) stack.getCount() + amounts.getOrDefault(stack.getItem(), 0L);
    }
}
