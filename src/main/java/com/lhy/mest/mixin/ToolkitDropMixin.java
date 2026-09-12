package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;

import com.lhy.mest.terminal.ToolkitBarActions;
import com.lhy.mest.terminal.ToolkitHand;

/**
 * Vanilla {@code ServerPlayer#drop} writes {@code getSelected()} into the hotbar menu slot. That
 * would overwrite a vanilla hotbar cell with an empty toolkit stack. Extra-bar drops go through
 * {@link ToolkitBarActions#dropSelected} instead.
 */
@Mixin(ServerPlayer.class)
public abstract class ToolkitDropMixin {
    @WrapMethod(method = "drop(Z)Z")
    private boolean mest$dropToolkit(boolean dropStack, Operation<Boolean> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!ToolkitHand.isOverrideActive(player)) {
            return original.call(dropStack);
        }
        ToolkitBarActions.dropSelected(player, dropStack);
        return true;
    }
}
