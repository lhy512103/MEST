package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import appeng.items.contents.NetworkToolMenuHost;
import appeng.items.tools.NetworkToolItem;

import com.lhy.mest.terminal.MestNetworkToolkitAccess;

@Mixin(NetworkToolItem.class)
public abstract class NetworkToolFindMixin {
    @Inject(method = "findNetworkToolInv", at = @At("RETURN"), cancellable = true, remap = false)
    private static void mest$fallbackToTerminalToolkit(
            Player player, CallbackInfoReturnable<NetworkToolMenuHost<?>> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        NetworkToolMenuHost<?> host = MestNetworkToolkitAccess.menuHostOf(player);
        if (host != null) {
            cir.setReturnValue(host);
        }
    }
}
