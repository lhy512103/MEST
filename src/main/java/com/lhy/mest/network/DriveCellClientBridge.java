package com.lhy.mest.network;

import java.util.Objects;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/** Dedicated servers never load the drive panel's client-only classes. */
public final class DriveCellClientBridge {
    private static volatile IPayloadHandler<DriveCellListPacket> handler = (packet, context) -> {
    };

    private DriveCellClientBridge() {
    }

    public static void install(IPayloadHandler<DriveCellListPacket> clientHandler) {
        handler = Objects.requireNonNull(clientHandler);
    }

    public static void handle(DriveCellListPacket packet, IPayloadContext context) {
        if (packet.containerId() >= 0) {
            handler.handle(packet, context);
        }
    }
}
