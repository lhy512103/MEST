package com.lhy.mest.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import appeng.api.networking.IGrid;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.compat.MestWtlibSupport;

/** Server-owned, fail-closed remote menu sessions. Only the bound distance test is exempted. */
@EventBusSubscriber(modid = MESplicedterminal.MODID)
public final class RemoteMenuAccess {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private RemoteMenuAccess() {
    }

    public static boolean open(ServerPlayer player, ServerLevel level, BlockPos pos,
            BlockHitResult hit, IGrid originalGrid) {
        if (originalGrid == null || player.level() != level || !level.isLoaded(pos)) {
            return false;
        }
        BlockEntity target = level.getBlockEntity(pos);
        if (target == null) {
            return false;
        }
        Block block = level.getBlockState(pos).getBlock();
        if (!MestWtlibSupport.hasLinkedTerminal(player, originalGrid)
                || !RemoteMenuPermission.mayUse(player, level, pos, target, hit)
                || level.getBlockState(pos).getBlock() != block) {
            return false;
        }
        var previousMenu = player.containerMenu;
        // Never call MenuProvider directly: the actual block's authorization/opening path must run.
        level.getBlockState(pos).useWithoutItem(level, player, hit);
        var opened = player.containerMenu;
        if (opened == previousMenu || opened == player.inventoryMenu) {
            return false;
        }
        Session session = new Session(opened, level, pos.immutable(), block, target, hit, originalGrid);
        SESSIONS.put(player.getUUID(), session);
        if (!stillValid(player, opened, () -> opened.stillValid(player))) {
            player.closeContainer();
            return false;
        }
        return true;
    }

    /** Called at BOTH vanilla server validity call sites; never ORs away custom menu rejection. */
    public static boolean stillValid(ServerPlayer player, AbstractContainerMenu menu, BooleanSupplier original) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.menu != menu || menu != player.containerMenu) {
            if (session != null && session.menu != player.containerMenu) {
                SESSIONS.remove(player.getUUID());
            }
            return original.getAsBoolean();
        }
        // Keep revoked sessions until close, so subsequent local checks cannot resurrect access.
        return session.validity.evaluate(() -> authorized(player, session), original,
                () -> RemoteMenuDistanceScope.evaluate(player, session.level, menu, session.pos.asLong(), original));
    }

    private static boolean authorized(ServerPlayer player, Session session) {
        ServerLevel level = session.level;
        BlockPos pos = session.pos;
        if (!RemoteMenuValidityPolicy.sameTarget(player.level(), level, level.isLoaded(pos),
                session.blockEntity, level.isLoaded(pos) ? level.getBlockEntity(pos) : null,
                session.blockEntity.isRemoved()) || level.getBlockState(pos).getBlock() != session.block) {
            return false;
        }
        // No lease cache: changing grid, losing the terminal, or losing permission revokes immediately.
        return MestWtlibSupport.hasLinkedTerminal(player, session.grid)
                && RemoteMenuPermission.mayUse(player, level, pos, session.blockEntity, session.hit)
                && player.level() == level && level.isLoaded(pos)
                && level.getBlockEntity(pos) == session.blockEntity && !session.blockEntity.isRemoved()
                && level.getBlockState(pos).getBlock() == session.block;
    }

    public static void clear(ServerPlayer player, AbstractContainerMenu menu) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.menu == menu) {
            SESSIONS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clear(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SESSIONS.remove(player.getUUID());
        }
    }

    private static final class Session {
        private final AbstractContainerMenu menu;
        private final ServerLevel level;
        private final BlockPos pos;
        private final Block block;
        private final BlockEntity blockEntity;
        private final BlockHitResult hit;
        private final IGrid grid;
        private final RemoteMenuValidityPolicy validity = new RemoteMenuValidityPolicy();

        private Session(AbstractContainerMenu menu, ServerLevel level, BlockPos pos, Block block,
                BlockEntity blockEntity, BlockHitResult hit, IGrid grid) {
            this.menu = menu;
            this.level = level;
            this.pos = pos;
            this.block = block;
            this.blockEntity = blockEntity;
            this.hit = hit;
            this.grid = grid;
        }
    }
}
