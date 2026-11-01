package com.lhy.mest.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

class PersistentItemInventoryTest {
    @BeforeAll
    static void bootstrap() {
        MestItemContentsTest.bootstrap();
    }

    @Test
    void shrinkingEditingAndReopeningPreservesHidden576thSlot() {
        var saved = new AtomicReference<>(new MestItemContents(stored(576)));
        var small = large(18, saved);
        small.setItemDirect(0, new ItemStack(Items.DIRT, 4));
        var reopened = large(18, saved);
        reopened.setItemDirect(1, new ItemStack(Items.DIRT, 8));
        var restored = large(576, saved);
        assertEquals(4, restored.getStackInSlot(0).getCount());
        assertEquals(8, restored.getStackInSlot(1).getCount());
        assertTrue(restored.getStackInSlot(575).is(Items.STONE));
        assertEquals(12, restored.getStackInSlot(575).getCount());
    }

    @Test
    void vanillaNetworkToolContentsSurviveShrinkSaveAndGrow() {
        var saved = new AtomicReference<>(ItemContainerContents.fromItems(stored(216)));
        var small = new PersistentItemInventory(9, saved.get().stream().toList(), () -> false,
                items -> saved.set(ItemContainerContents.fromItems(items)));
        small.setItemDirect(0, new ItemStack(Items.DIRT));
        var restored = new PersistentItemInventory(216, saved.get().stream().toList(), () -> false,
                items -> saved.set(ItemContainerContents.fromItems(items)));
        assertTrue(restored.getStackInSlot(0).is(Items.DIRT));
        assertEquals(12, restored.getStackInSlot(215).getCount());
    }

    @Test
    void clearingVisibleSlotsDoesNotEraseHiddenContents() {
        var saved = new AtomicReference<>(new MestItemContents(stored(576)));
        var small = large(18, saved);
        for (int i = 0; i < small.size(); i++) {
            small.setItemDirect(i, ItemStack.EMPTY);
        }
        assertTrue(saved.get().items().get(0).isEmpty());
        assertEquals(12, saved.get().items().get(575).getCount());
    }

    @Test
    void loadingDoesNotRewriteComponentsOrRetainMutableTailAliases() {
        var source = stored(576);
        var saved = new AtomicReference<List<ItemStack>>();
        var small = new PersistentItemInventory(18, source, () -> false, saved::set);
        assertNull(saved.get());
        source.get(575).setCount(1);
        small.setItemDirect(0, ItemStack.EMPTY);
        assertEquals(12, saved.get().get(575).getCount());
    }

    @Test
    void clientViewsDoNotPersistServerOwnedComponents() {
        var saved = new AtomicReference<List<ItemStack>>();
        var client = new PersistentItemInventory(18, stored(576), () -> true, saved::set);
        client.setItemDirect(0, new ItemStack(Items.DIRT));
        assertNull(saved.get());
    }

    private static PersistentItemInventory large(int size, AtomicReference<MestItemContents> saved) {
        return new PersistentItemInventory(size, saved.get().items(), () -> false,
                items -> saved.set(new MestItemContents(items)));
    }

    private static List<ItemStack> stored(int size) {
        var items = new ArrayList<ItemStack>(Collections.nCopies(size, ItemStack.EMPTY));
        items.set(0, new ItemStack(Items.STONE));
        items.set(size - 1, new ItemStack(Items.STONE, 12));
        return items;
    }
}
