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

/** Persist the toolkit-hotbar toggle on the spliced terminal item. */
public record ToolkitBarTogglePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<ToolkitBarTogglePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarTogglePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ToolkitBarTogglePacket::enabled, ToolkitBarTogglePacket::new);

    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(4);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolkitBarTogglePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && BUDGETS.tryAcquire(player)) {
                ToolkitBarActions.setBarEnabled(player, packet.enabled());
            }
        });
    }
}
