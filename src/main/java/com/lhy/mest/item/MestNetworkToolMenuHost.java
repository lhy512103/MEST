package com.lhy.mest.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.upgrades.Upgrades;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;

import com.lhy.mest.config.MestConfig;

/**
 * Network-tool host whose inventory size comes from config instead of AE2's hardcoded 9.
 */
public final class MestNetworkToolMenuHost extends NetworkToolMenuHost<ItemMestNetworkToolkit>
        implements InternalInventoryHost {
    private final AppEngInternalInventory upgrades;

    public MestNetworkToolMenuHost(ItemMestNetworkToolkit item, Player player, ItemMenuHostLocator locator,
            IInWorldGridNodeHost gridHost) {
        super(item, player, locator, gridHost);
        this.upgrades = new AppEngInternalInventory(this, MestConfig.networkToolkitSlots());
        this.upgrades.setEnableClientEvents(true);
        this.upgrades.setFilter(new UpgradeCardFilter());
        this.upgrades.fromItemContainerContents(
                getItemStack().getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    @Override
    public InternalInventory getInventory() {
        return upgrades;
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        getItemStack().set(DataComponents.CONTAINER, inv.toItemContainerContents());
    }

    @Override
    public boolean isClientSide() {
        return getPlayer().level().isClientSide();
    }

    private static final class UpgradeCardFilter implements IAEItemFilter {
        @Override
        public boolean allowExtract(InternalInventory inv, int slot, int amount) {
            return true;
        }

        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return stack.isEmpty() || Upgrades.isUpgradeCardItem(stack.getItem());
        }
    }
}
