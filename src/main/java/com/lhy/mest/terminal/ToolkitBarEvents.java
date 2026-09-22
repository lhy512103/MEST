package com.lhy.mest.terminal;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.ToolkitBarSyncPacket;

@EventBusSubscriber(modid = MESplicedterminal.MODID)
public final class ToolkitBarEvents {
    private ToolkitBarEvents() {}

    /**
     * Equip from the extra bar is not a vanilla hotbar swap. Cancel {@code use()} on both sides so
     * the client cannot predict it, and apply the copy-based swap only on the server.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.isCanceled() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        if (!ToolkitHand.isOverrideActive(player)) {
            return;
        }
        InteractionResult result = ToolkitBarActions.equipFromSelected(player, !player.level().isClientSide());
        if (result == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    @SubscribeEvent
    public static void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (!(event.getEntity() instanceof Player player) || !ToolkitHand.isOverrideActive(player)) {
            return;
        }
        ItemStack toMain = event.getItemSwappedToMainHand();
        if (!toMain.isEmpty() && !ToolkitBarState.mayStore(toMain)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ToolkitBarSyncPacket.send(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ToolkitBarState.clear(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (ToolkitBarState.storedBar(player) != ToolkitBarState.Bar.CENTER
                    && !ToolkitBarState.isBarEnabled(player)) {
                ToolkitBarState.setSelection(
                        player, ToolkitBarState.Bar.CENTER, player.getInventory().selected);
            }
            ToolkitBarState.persistIfDirty(player);
        }
    }
}
