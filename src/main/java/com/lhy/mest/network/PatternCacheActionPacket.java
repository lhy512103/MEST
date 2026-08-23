package com.lhy.mest.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/** Client action against every encoded pattern in the cache panel. */
public record PatternCacheActionPacket(Action action, boolean value) implements CustomPacketPayload {
    public static final Type<PatternCacheActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "pattern_cache_action"));

    public static final StreamCodec<ByteBuf, PatternCacheActionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(ordinal -> Action.values()[ordinal], Action::ordinal),
            PatternCacheActionPacket::action,
            ByteBufCodecs.BOOL,
            PatternCacheActionPacket::value,
            PatternCacheActionPacket::new);

    public enum Action {
        SWAP,
        TIMES_2,
        TIMES_3,
        TIMES_5,
        EQUALS_1,
        DIVIDE_2,
        DIVIDE_3,
        DIVIDE_5,
        TIMES_8,
        DIVIDE_8,
        TIMES_16,
        DIVIDE_16,
        TIMES_32,
        DIVIDE_32,
        ITEM_SUBSTITUTION,
        FLUID_SUBSTITUTION
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PatternCacheActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu) {
                menu.handlePatternCacheAction(packet.action(), packet.value());
            }
        });
    }
}
