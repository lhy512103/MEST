package com.lhy.mest.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

/** Component-backed slots; shrinking the visible inventory never discards its saved tail. */
public class PersistentItemInventory extends AppEngInternalInventory {
    public PersistentItemInventory(int size, List<ItemStack> stored, BooleanSupplier clientSide,
            Consumer<List<ItemStack>> save) {
        super(size);
        // Keep the tail in the component until a later capacity increase makes it visible again.
        List<ItemStack> retained = stored.stream().map(ItemStack::copy).toList();
        for (int i = 0; i < Math.min(size, retained.size()); i++) {
            setItemDirect(i, retained.get(i).copy());
        }
        setHost(new InternalInventoryHost() {
            @Override
            public void saveChangedInventory(AppEngInternalInventory inventory) {
                var snapshot = new ArrayList<ItemStack>(Math.max(inventory.size(), retained.size()));
                for (int i = 0; i < inventory.size(); i++) {
                    snapshot.add(inventory.getStackInSlot(i).copy());
                }
                for (int i = inventory.size(); i < retained.size(); i++) {
                    snapshot.add(retained.get(i).copy());
                }
                save.accept(snapshot);
            }

            @Override
            public boolean isClientSide() {
                return clientSide.getAsBoolean();
            }
        });
    }

    public static PersistentItemInventory vanilla(Player player, ItemStack stack,
            DataComponentType<ItemContainerContents> component, int size) {
        return new PersistentItemInventory(size,
                stack.getOrDefault(component, ItemContainerContents.EMPTY).stream().toList(),
                () -> player.level().isClientSide(), contents -> stack.set(component, ItemContainerContents.fromItems(contents)));
    }

    public static PersistentItemInventory large(Player player, ItemStack stack,
            DataComponentType<MestItemContents> component, int size) {
        return new PersistentItemInventory(size, stack.getOrDefault(component, MestItemContents.EMPTY).items(),
                () -> player.level().isClientSide(), contents -> stack.set(component, new MestItemContents(contents)));
    }
}
