package com.lhy.mest.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.compat.MestWtlibSupport;

/** Middle-click pick from ME using the spliced terminal's pick-block setting. */
public record MestPickBlockPacket(ItemStack stack) implements CustomPacketPayload {
    public static final Type<MestPickBlockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "pick_block"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MestPickBlockPacket> STREAM_CODEC =
            ItemStack.STREAM_CODEC.map(MestPickBlockPacket::new, MestPickBlockPacket::stack);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MestPickBlockPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MestWtlibSupport.pickBlock(player, packet.stack());
            }
        });
    }
}
