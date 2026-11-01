package com.lhy.mest.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

/** An AE2 slot inventory whose persistence is independent of vanilla's 256-slot container. */
public final class PersistentItemInventory extends AppEngInternalInventory {
    public PersistentItemInventory(int size, List<ItemStack> stored, BooleanSupplier clientSide,
            Consumer<List<ItemStack>> save) {
        super(size);
        for (int i = 0; i < Math.min(size, stored.size()); i++) {
            setItemDirect(i, stored.get(i).copy());
        }
        setHost(new InternalInventoryHost() {
            @Override
            public void saveChangedInventory(AppEngInternalInventory inventory) {
                var snapshot = new ArrayList<ItemStack>(inventory.size());
                for (int i = 0; i < inventory.size(); i++) {
                    snapshot.add(inventory.getStackInSlot(i).copy());
                }
                save.accept(snapshot);
            }

            @Override
            public boolean isClientSide() {
                return clientSide.getAsBoolean();
            }
        });
    }

    public static PersistentItemInventory large(Player player, ItemStack stack,
            DataComponentType<MestItemContents> component, int size) {
        return new PersistentItemInventory(size, stack.getOrDefault(component, MestItemContents.EMPTY).items(),
                () -> player.level().isClientSide(), contents -> stack.set(component, new MestItemContents(contents)));
    }
}
