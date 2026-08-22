package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.server.level.ServerPlayer;

import com.lhy.mest.network.RemoteMenuAccess;

@Mixin(ServerPlayer.class)
public abstract class RemoteMenuMixin {
    @ModifyExpressionValue(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"),
            require = 0)
    private boolean mest$keepRemoteProviderMenuOpen(boolean original) {
        if (original) {
            return true;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        return RemoteMenuAccess.keepsMenuValid(self, self.containerMenu);
    }
}
