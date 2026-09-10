package com.lhy.mest.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.ToolkitBarClickPacket;

/**
 * Left-click on a toolkit quick-bar cell swaps that slot with the player's main hand.
 *
 * <p>Only clicks that land on a visible bar are consumed, so normal gameplay input is untouched.
 */
@EventBusSubscriber(modid = MESplicedterminal.MODID, value = Dist.CLIENT)
public final class ToolkitBarInput {
    private ToolkitBarInput() {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS
                || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (!ToolkitBarHud.isBarEnabled(ToolkitBarHud.findTerminal(minecraft.player))) {
            return;
        }
        // The event exposes raw window pixels; the HUD is laid out in GUI-scaled units.
        double scaleX = (double) minecraft.getWindow().getGuiScaledWidth()
                / Math.max(1, minecraft.getWindow().getScreenWidth());
        double scaleY = (double) minecraft.getWindow().getGuiScaledHeight()
                / Math.max(1, minecraft.getWindow().getScreenHeight());
        int slot = ToolkitBarHud.slotAt(
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                minecraft.mouseHandler.xpos() * scaleX,
                minecraft.mouseHandler.ypos() * scaleY);
        if (slot < 0) {
            return;
        }
        PacketDistributor.sendToServer(new ToolkitBarClickPacket(slot));
        event.setCanceled(true);
    }
}
