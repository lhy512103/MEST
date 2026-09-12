package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.lhy.mest.terminal.ToolkitBarState;
import com.lhy.mest.terminal.ToolkitHand;

@Mixin(Player.class)
public abstract class ToolkitPlayerMixin {
    @Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
    private void mest$setMainHand(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        if (slot != EquipmentSlot.MAINHAND) {
            return;
        }
        Player player = (Player) (Object) this;
        if (ToolkitHand.isOverrideActive(player)) {
            ToolkitBarState.setSelectedStack(player, stack);
            ci.cancel();
        }
    }
}
