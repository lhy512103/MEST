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

/** Selects one of the 27 visible quick-bar cells. Extra bars only change page; slot is Inventory.selected. */
public record ToolkitBarSelectPacket(int index) implements CustomPacketPayload {
    public static final Type<ToolkitBarSelectPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarSelectPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ToolkitBarSelectPacket::index,
                    ToolkitBarSelectPacket::new);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolkitBarSelectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && BUDGETS.tryAcquire(player)) {
                ToolkitBarActions.selectVisibleCell(player, packet.index());
            }
        });
    }
}
