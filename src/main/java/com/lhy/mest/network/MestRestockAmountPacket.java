package com.lhy.mest.network;

import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.Maps;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.MestRestockClient;

/** ME counts for hotbar overlay, same payload shape as wtlib RestockAmountPacket. */
public record MestRestockAmountPacket(HashMap<Holder<Item>, Long> items) implements CustomPacketPayload {
    public static final Type<MestRestockAmountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "restock_amounts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MestRestockAmountPacket> STREAM_CODEC =
            ByteBufCodecs.map(
                            Maps::newHashMapWithExpectedSize,
                            ByteBufCodecs.holderRegistry(Registries.ITEM),
                            ByteBufCodecs.VAR_LONG)
                    .map(MestRestockAmountPacket::new, MestRestockAmountPacket::items);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MestRestockAmountPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Map<Item, Long> map = new HashMap<>();
            packet.items().forEach((item, count) -> map.put(item.value(), count));
            MestRestockClient.setAmounts(map);
        });
    }
}
