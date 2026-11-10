package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import appeng.util.inv.AppEngInternalInventory;

class TrashSlotTest {
    @BeforeAll
    static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void closedTrashRejectsGenericQuickMoveEvenIfClientActiveFlagIsTrue() {
        var open = new AtomicBoolean(false);
        var inventory = new AppEngInternalInventory(1);
        var slot = new TrashSlot(inventory, 0, open::get);
        slot.setActive(true);
        var held = new ItemStack(Items.STONE, 12);

        // AEBaseMenu.isValidQuickMoveDestination checks mayPlace, not isActive.
        assertFalse(slot.mayPlace(held));
        assertFalse(slot.isActive());
        assertEquals(12, slot.safeInsert(held).getCount());
        assertTrue(inventory.getStackInSlot(0).isEmpty());
    }

    @Test
    void hidingRetainsBufferedContentsButRejectsMergingAndTaking() {
        var open = new AtomicBoolean(true);
        var slot = new TrashSlot(new AppEngInternalInventory(1), 0, open::get);
        assertTrue(slot.safeInsert(new ItemStack(Items.STONE, 12)).isEmpty());
        open.set(false);

        assertEquals(12, slot.getItem().getCount());
        assertEquals(7, slot.safeInsert(new ItemStack(Items.STONE, 7)).getCount());
        assertEquals(12, slot.getItem().getCount());
        assertFalse(slot.mayPickup(null));
        open.set(true);
        assertTrue(slot.mayPickup(null));
        assertTrue(slot.safeInsert(new ItemStack(Items.STONE, 7)).isEmpty());
        assertEquals(19, slot.getItem().getCount());
    }

    @Test
    void hiddenSlotsStillAcceptAuthoritativeContentsSynchronization() {
        var open = new AtomicBoolean(false);
        var slot = new TrashSlot(new AppEngInternalInventory(1), 0, open::get);
        slot.set(new ItemStack(Items.STONE, 9));
        assertEquals(9, slot.getItem().getCount());
        open.set(true);
        assertEquals(9, slot.getItem().getCount());
    }

    @Test
    void openTrashStillRespectsParentSlotRestrictions() {
        var slot = new TrashSlot(new AppEngInternalInventory(1), 0, () -> true);
        slot.setSlotEnabled(false);
        assertFalse(slot.mayPlace(new ItemStack(Items.STONE)));
        assertFalse(slot.mayPickup(null));
        assertFalse(slot.isActive());
        slot.setSlotEnabled(true);
        slot.setActive(false);
        assertFalse(slot.isActive());
    }
}
