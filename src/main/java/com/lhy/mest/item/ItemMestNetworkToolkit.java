package com.lhy.mest.item;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.items.tools.NetworkToolItem;
import appeng.menu.locator.ItemMenuHostLocator;

/**
 * Larger network tool. {@link NetworkToolItem#findNetworkToolInv} picks it up via {@code instanceof},
 * so AE machines attach a ToolboxMenu without mixin. Extra slots live in
 * {@link MestNetworkToolMenuHost}.
 */
public class ItemMestNetworkToolkit extends NetworkToolItem {
    public ItemMestNetworkToolkit(Properties properties) {
        super(properties);
    }

    @Override
    public NetworkToolMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hitResult) {
        IInWorldGridNodeHost grid = null;
        if (hitResult != null) {
            grid = GridHelper.getNodeHost(player.level(), hitResult.getBlockPos());
        }
        return new MestNetworkToolMenuHost(this, player, locator, grid);
    }
}
