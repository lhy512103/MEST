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

/** Drops the selected extra-bar toolkit cell. Does not use vanilla hotbar drop. */
public record ToolkitBarDropPacket(boolean all) implements CustomPacketPayload {
    public static final Type<ToolkitBarDropPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_drop"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarDropPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ToolkitBarDropPacket::all,
                    ToolkitBarDropPacket::new);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolkitBarDropPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && BUDGETS.tryAcquire(player)) {
                ToolkitBarActions.dropSelected(player, packet.all());
            }
        });
    }
}
