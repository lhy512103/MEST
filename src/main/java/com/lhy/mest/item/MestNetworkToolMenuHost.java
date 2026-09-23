package com.lhy.mest.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.upgrades.Upgrades;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.items.contents.StackDependentSupplier;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.SupplierInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;

import com.lhy.mest.config.MestConfig;

/**
 * Network-tool host whose inventory size comes from config instead of AE2's hardcoded 9.
 *
 * <p>Like AE2's own host, the inventory is rebuilt whenever the located stack changes and always
 * saves into the stack it was read from. A snapshot bound to "whatever is at the locator now"
 * let cards be taken from a tool that had already been moved away.
 */
public final class MestNetworkToolMenuHost extends NetworkToolMenuHost<ItemMestNetworkToolkit> {
    private final InternalInventory upgrades;

    public MestNetworkToolMenuHost(ItemMestNetworkToolkit item, Player player, ItemMenuHostLocator locator,
            IInWorldGridNodeHost gridHost) {
        super(item, player, locator, gridHost);
        this.upgrades = new SupplierInternalInventory<>(
                new StackDependentSupplier<>(this::getItemStack, this::createInventory));
    }

    @Override
    public InternalInventory getInventory() {
        return upgrades;
    }

    private InternalInventory createInventory(ItemStack stack) {
        boolean bound = stack.getItem() instanceof ItemMestNetworkToolkit;
        AppEngInternalInventory inv = new AppEngInternalInventory(new InternalInventoryHost() {
            @Override
            public void saveChangedInventory(AppEngInternalInventory changed) {
                if (bound) {
                    stack.set(DataComponents.CONTAINER, changed.toItemContainerContents());
                }
            }

            @Override
            public boolean isClientSide() {
                return getPlayer().level().isClientSide();
            }
        }, MestConfig.networkToolkitSlots());
        inv.setEnableClientEvents(true);
        inv.setFilter(bound ? new UpgradeCardFilter() : LockedFilter.INSTANCE);
        if (bound) {
            inv.fromItemContainerContents(
                    stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
        }
        return inv;
    }

    private static final class LockedFilter implements IAEItemFilter {
        private static final LockedFilter INSTANCE = new LockedFilter();

        @Override
        public boolean allowExtract(InternalInventory inv, int slot, int amount) {
            return false;
        }

        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return false;
        }
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
