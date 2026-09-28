package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import com.lhy.mest.terminal.ToolkitBarState;

@Mixin(Gui.class)
public class ToolkitHotbarGuiMixin {
    @Final
    @Shadow
    private Minecraft minecraft;

    @WrapWithCondition(
            method = "renderItemHotbar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private boolean mest$skipVanillaHotbarSelection(
            GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
        if (!ResourceLocation.withDefaultNamespace("hud/hotbar_selection").equals(sprite)) {
            return true;
        }
        Player player = minecraft.player;
        return player == null || !ToolkitBarState.isToolkitSelected(player);
    }
}
