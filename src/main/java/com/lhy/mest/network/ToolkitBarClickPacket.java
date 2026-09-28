package com.lhy.mest.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.ToolkitBarActions;

/** A click on one of the toolkit quick bars selects that cell. Index 0-8 is left, 9-17 right. */
public record ToolkitBarClickPacket(int index) implements CustomPacketPayload {
    public static final Type<ToolkitBarClickPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_click"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarClickPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ToolkitBarClickPacket::index, ToolkitBarClickPacket::new);

    /** Hand-paced action; a burst of clicks inside one tick is never legitimate. */
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolkitBarClickPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && BUDGETS.tryAcquire(player)) {
                ToolkitBarActions.selectToolkitCell(player, packet.index());
            }
        });
    }
}
