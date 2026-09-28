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
            require = 1)
    private boolean mest$keepRemoteProviderMenuOpen(boolean original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        // Revoke stale remote access even while the menu is locally valid.
        boolean remoteValid = RemoteMenuAccess.keepsMenuValid(self, self.containerMenu);
        return original || remoteValid;
    }
}
