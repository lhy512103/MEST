package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.lhy.mest.terminal.ToolkitBarState;
import com.lhy.mest.terminal.ToolkitHand;

@Mixin(Inventory.class)
public abstract class ToolkitInventoryMixin {
    @Inject(method = "getSelected", at = @At("HEAD"), cancellable = true)
    private void mest$getSelected(CallbackInfoReturnable<ItemStack> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (ToolkitHand.isOverrideActive(inventory.player)) {
            cir.setReturnValue(ToolkitBarState.selectedStack(inventory.player));
        }
    }

    @Inject(method = "removeFromSelected", at = @At("HEAD"), cancellable = true)
    private void mest$removeFromSelected(boolean all, CallbackInfoReturnable<ItemStack> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (!ToolkitHand.isOverrideActive(inventory.player)) {
            return;
        }
        ItemStack selected = ToolkitBarState.selectedStack(inventory.player);
        if (selected.isEmpty()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        ItemStack removed = selected.copyWithCount(all ? selected.getCount() : 1);
        selected.shrink(removed.getCount());
        ToolkitBarState.setSelectedStack(inventory.player, selected);
        cir.setReturnValue(removed);
    }

    @Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
    private void mest$getDestroySpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (ToolkitHand.isOverrideActive(inventory.player)) {
            cir.setReturnValue(ToolkitBarState.selectedStack(inventory.player).getDestroySpeed(state));
        }
    }
}
