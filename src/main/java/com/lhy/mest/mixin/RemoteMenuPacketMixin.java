package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import com.lhy.mest.network.RemoteMenuAccess;

/** Slot/recipe/button packets must perform the same live checks as server ticks. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RemoteMenuPacketMixin {
    @WrapOperation(
            method = { "handleContainerClick", "handlePlaceRecipe", "handleContainerButtonClick", "handleSetBeaconPacket" },
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"),
            require = 4)
    private boolean mest$validateRemoteInteraction(AbstractContainerMenu menu, Player player, Operation<Boolean> original) {
        return RemoteMenuAccess.stillValid((ServerPlayer) player, menu, () -> original.call(menu, player));
    }
}
