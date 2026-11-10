package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import com.lhy.mest.network.RemoteMenuAccess;

@Mixin(ServerPlayer.class)
public abstract class RemoteMenuMixin {
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"),
            require = 1)
    private boolean mest$validateRemoteMenu(AbstractContainerMenu menu, Player player, Operation<Boolean> original) {
        return RemoteMenuAccess.stillValid((ServerPlayer) player, menu, () -> original.call(menu, player));
    }
}
