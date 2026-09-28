package com.lhy.mest.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.network.DriveCellListPacket;
import com.lhy.mest.terminal.MESTMenu;

/** Routes drive snapshots only to the active terminal session on the client thread. */
public final class DriveCellClientHandler {
    private DriveCellClientHandler() {
    }

    public static void handle(DriveCellListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof MESTScreen screen
                    && minecraft.player != null
                    && minecraft.player.containerMenu instanceof MESTMenu menu
                    && screen.getMenu() == menu
                    && menu.containerId == packet.containerId()) {
                screen.updateDriveCells(packet);
            }
        });
    }
}
