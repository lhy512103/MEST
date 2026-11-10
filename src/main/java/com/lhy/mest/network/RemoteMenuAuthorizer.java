package com.lhy.mest.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Optional, explicit opt-in implemented by a machine's block entity.
 *
 * <p>This check must be side-effect-free and repeatable: it runs before opening and on every
 * server validity check. Implementations must check ALL current non-distance requirements,
 * including ownership, locks and any authorization normally performed by block interaction.
 * Returning true asserts that invoking this block's useWithoutItem is a safe menu-opening action.
 * It does not bypass protection events, the block's actual useWithoutItem, or menu.stillValid.
 * Only the bound block position gets a distance exemption during stillValid; multi-block and
 * custom distance predicates require their own safe integration and are not implicitly supported.
 * Cross-dimension access is never allowed by this interface.
 */
public interface RemoteMenuAuthorizer {
    boolean mayOpenRemoteMenu(ServerPlayer player, ServerLevel level, BlockPos pos, BlockHitResult hit);
}
