package com.lhy.mest.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.lhy.mest.MESplicedterminal;

public final class MestPackets {
    private MestPackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MESplicedterminal.MODID);

        registrar.playToServer(
                PatternProviderListPacket.Request.TYPE,
                PatternProviderListPacket.Request.STREAM_CODEC,
                PatternProviderListPacket.Request::handle);

        registrar.playToServer(
                PatternProviderActionPacket.TYPE,
                PatternProviderActionPacket.STREAM_CODEC,
                PatternProviderActionPacket::handle);

        registrar.playToClient(
                PatternProviderListPacket.TYPE,
                PatternProviderListPacket.STREAM_CODEC,
                PatternProviderClientBridge::handle);
    }
}
