package com.lhy.mest.terminal;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.ToolkitBarSyncPacket;

@EventBusSubscriber(modid = MESplicedterminal.MODID)
public final class ToolkitBarEvents {
    private ToolkitBarEvents() {}

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
