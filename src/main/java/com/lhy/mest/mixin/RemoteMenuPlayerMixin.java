package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import com.lhy.mest.network.RemoteMenuAccess;
import com.lhy.mest.network.RemoteMenuDistanceScope;

@Mixin(Player.class)
public abstract class RemoteMenuPlayerMixin {
    // ServerPlayer.doTick invokes super.tick, so the ServerPlayer.tick hook alone is insufficient.
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"),
            require = 1)
    private boolean mest$validateRemoteMenu(AbstractContainerMenu menu, Player player, Operation<Boolean> original) {
        if (player instanceof ServerPlayer serverPlayer) {
            return RemoteMenuAccess.stillValid(serverPlayer, menu, () -> original.call(menu, player));
        }
        return original.call(menu, player);
    }

    @Inject(method = "canInteractWithBlock(Lnet/minecraft/core/BlockPos;D)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void mest$exemptBoundDistance(BlockPos pos, double additionalRange, CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        if (player instanceof ServerPlayer
                && RemoteMenuDistanceScope.matches(player, player.level(), player.containerMenu, pos.asLong())) {
            cir.setReturnValue(true);
        }
    }
}
