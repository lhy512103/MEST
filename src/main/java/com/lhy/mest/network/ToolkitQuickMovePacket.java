package com.lhy.mest.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.netty.buffer.ByteBuf;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.ToolkitBarState;

public record ToolkitQuickMovePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<ToolkitQuickMovePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_quick_move"));
    public static final StreamCodec<ByteBuf, ToolkitQuickMovePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ToolkitQuickMovePacket::enabled, ToolkitQuickMovePacket::new);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(4);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolkitQuickMovePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !BUDGETS.tryAcquire(player)) {
                return;
            }
            if (ToolkitBarState.hasTerminal(player)) {
                ToolkitBarState.setQuickMove(player, packet.enabled());
            }
        });
    }
}
