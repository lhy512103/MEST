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

        registrar.playToServer(
                PatternCacheActionPacket.TYPE,
                PatternCacheActionPacket.STREAM_CODEC,
                PatternCacheActionPacket::handle);

        registrar.playToServer(
                MestPickBlockPacket.TYPE,
                MestPickBlockPacket.STREAM_CODEC,
                MestPickBlockPacket::handle);

        registrar.playToServer(
                ToolkitBarClickPacket.TYPE,
                ToolkitBarClickPacket.STREAM_CODEC,
                ToolkitBarClickPacket::handle);

        registrar.playToServer(
                ToolkitBarTogglePacket.TYPE,
                ToolkitBarTogglePacket.STREAM_CODEC,
                ToolkitBarTogglePacket::handle);

        registrar.playToClient(
                PatternProviderListPacket.TYPE,
                PatternProviderListPacket.STREAM_CODEC,
                PatternProviderClientBridge::handle);

        registrar.playToClient(
                ProviderPickerListPacket.TYPE,
                ProviderPickerListPacket.STREAM_CODEC,
                ProviderPickerClientBridge::handle);

        registrar.playToClient(
                MestRestockAmountPacket.TYPE,
                MestRestockAmountPacket.STREAM_CODEC,
                MestRestockAmountPacket::handle);
    }
}
