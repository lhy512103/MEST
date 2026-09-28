package com.lhy.mest.network;

import java.util.Objects;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

public final class ProviderPickerClientBridge {
    private static volatile IPayloadHandler<ProviderPickerListPacket> handler = (packet, context) -> {
    };

    private ProviderPickerClientBridge() {
    }

    public static void install(IPayloadHandler<ProviderPickerListPacket> clientHandler) {
        handler = Objects.requireNonNull(clientHandler, "clientHandler");
    }

    public static void handle(ProviderPickerListPacket packet, IPayloadContext context) {
        handler.handle(packet, context);
    }
}
