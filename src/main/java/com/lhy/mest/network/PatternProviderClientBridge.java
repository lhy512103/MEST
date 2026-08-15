package com.lhy.mest.network;

import java.util.Objects;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * Common-side registration target for a clientbound payload.
 *
 * <p>The dedicated server registers the payload without linking client classes. The physical
 * client installs its UI handler during client initialization before any play payload can arrive.
 */
public final class PatternProviderClientBridge {
    private static volatile IPayloadHandler<PatternProviderListPacket> handler = (packet, context) -> {
    };

    private PatternProviderClientBridge() {
    }

    public static void install(IPayloadHandler<PatternProviderListPacket> clientHandler) {
        handler = Objects.requireNonNull(clientHandler, "clientHandler");
    }

    public static void handle(PatternProviderListPacket packet, IPayloadContext context) {
        if (packet.isWellFormed()) {
            handler.handle(packet, context);
        }
    }
}