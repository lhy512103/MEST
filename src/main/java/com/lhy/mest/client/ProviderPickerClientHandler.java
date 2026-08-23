package com.lhy.mest.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.network.ProviderPickerListPacket;

public final class ProviderPickerClientHandler {
    private ProviderPickerClientHandler() {
    }

    public static void handle(ProviderPickerListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof MESTScreen screen) {
                screen.applyProviderPickerList(packet);
            }
        });
    }
}
