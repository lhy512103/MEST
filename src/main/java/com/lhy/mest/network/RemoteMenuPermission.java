package com.lhy.mest.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;

/** Fail-closed authorization; a MenuProvider alone is NOT an authorization API. */
final class RemoteMenuPermission {
    private RemoteMenuPermission() {
    }

    static boolean mayUse(ServerPlayer player, ServerLevel level, BlockPos pos,
            BlockEntity expected, BlockHitResult hit) {
        if (!RemoteMenuValidityPolicy.sameTarget(player.level(), level, level.isLoaded(pos), expected,
                level.isLoaded(pos) ? level.getBlockEntity(pos) : null, expected == null || expected.isRemoved())
                || !level.mayInteract(player, pos)) {
            return false;
        }
        var event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        if (event.isCanceled() || event.getUseBlock() == TriState.FALSE) {
            return false;
        }
        // Event handlers are external code: do not trust a pre-event identity check alone.
        if (player.level() != level || !level.isLoaded(pos) || level.getBlockEntity(pos) != expected
                || expected.isRemoved() || !level.mayInteract(player, pos)) {
            return false;
        }
        if (expected instanceof RemoteMenuAuthorizer authorizer) {
            return authorizer.mayOpenRemoteMenu(player, level, pos, hit);
        }
        var state = level.getBlockState(pos);
        Class<?> blockClass = state.getBlock().getClass();
        Class<?> entityClass = expected.getClass();
        boolean known = (blockClass == BarrelBlock.class && entityClass == BarrelBlockEntity.class)
                || (blockClass == FurnaceBlock.class && entityClass == FurnaceBlockEntity.class)
                || (blockClass == BlastFurnaceBlock.class && entityClass == BlastFurnaceBlockEntity.class)
                || (blockClass == SmokerBlock.class && entityClass == SmokerBlockEntity.class);
        if (blockClass == ChestBlock.class && entityClass == ChestBlockEntity.class) {
            // A double chest would authorize and exempt TWO identities; do not widen the scope.
            known = state.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    && !ChestBlock.isChestBlockedAt(level, pos);
        }
        // Exact classes intentionally exclude third-party subclasses with additional use checks.
        return known && ((BaseContainerBlockEntity) expected).canOpen(player);
    }
}
