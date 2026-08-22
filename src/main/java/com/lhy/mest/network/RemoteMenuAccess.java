package com.lhy.mest.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import com.lhy.mest.MESplicedterminal;

/**
 * Keeps a machine menu opened from the spliced terminal valid while the player is using it
 * remotely (vanilla {@code stillValid} would close it for distance).
 */
@EventBusSubscriber(modid = MESplicedterminal.MODID)
public final class RemoteMenuAccess {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private RemoteMenuAccess() {
    }

    public static boolean open(ServerPlayer player, MenuProvider provider, ServerLevel level, BlockPos pos) {
        var openedId = player.openMenu(provider, pos);
        if (openedId.isEmpty() || player.containerMenu.containerId != openedId.getAsInt()) {
            return false;
        }
        track(player, level, pos);
        return true;
    }

    public static boolean trackOpenedMenu(ServerPlayer player, AbstractContainerMenu previousMenu,
            ServerLevel level, BlockPos pos) {
        if (player.containerMenu == previousMenu) {
            return false;
        }
        track(player, level, pos);
        return true;
    }

    public static boolean keepsMenuValid(ServerPlayer player, AbstractContainerMenu menu) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.menu() != menu) {
            if (session != null) {
                SESSIONS.remove(player.getUUID());
            }
            return false;
        }
        ServerLevel level = player.server.getLevel(session.dimension());
        if (level == null || !level.isLoaded(session.pos())) {
            SESSIONS.remove(player.getUUID());
            return false;
        }
        var state = level.getBlockState(session.pos());
        if (state.isAir() || state.getBlock() != session.block()) {
            SESSIONS.remove(player.getUUID());
            return false;
        }
        if (session.blockEntity() != null
                && (session.blockEntity().isRemoved()
                || level.getBlockEntity(session.pos()) != session.blockEntity())) {
            SESSIONS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static void clear(ServerPlayer player, AbstractContainerMenu menu) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.menu() == menu) {
            SESSIONS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clear(player, event.getContainer());
        }
    }

    private static void track(ServerPlayer player, ServerLevel level, BlockPos pos) {
        SESSIONS.put(player.getUUID(), new Session(
                player.containerMenu,
                level.dimension(),
                pos.immutable(),
                level.getBlockState(pos).getBlock(),
                level.getBlockEntity(pos)));
    }

    private record Session(AbstractContainerMenu menu, ResourceKey<Level> dimension, BlockPos pos,
            Block block, @Nullable BlockEntity blockEntity) {
    }
}
