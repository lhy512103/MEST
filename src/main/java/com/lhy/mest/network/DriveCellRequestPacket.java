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

/** Explicit, throttled subscription: page -1 stops scanning when the module is hidden. */
public record DriveCellRequestPacket(int containerId, int page) implements CustomPacketPayload {
    public static final Type<DriveCellRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_cell_request"));
    public static final StreamCodec<ByteBuf, DriveCellRequestPacket> STREAM_CODEC = StreamCodec.ofMember(
            DriveCellRequestPacket::write, DriveCellRequestPacket::read);

    private void write(ByteBuf buf) {
        ByteBufCodecs.VAR_INT.encode(buf, containerId);
        ByteBufCodecs.VAR_INT.encode(buf, page);
    }

    private static DriveCellRequestPacket read(ByteBuf buf) {
        try {
            int container = ByteBufCodecs.VAR_INT.decode(buf);
            int page = ByteBufCodecs.VAR_INT.decode(buf);
            if (container < 0 || page < -1 || page > 4096 || buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive request");
            }
            return new DriveCellRequestPacket(container, page);
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Reject malformed client requests without reading more data.
            }
            return new DriveCellRequestPacket(-1, -2);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DriveCellRequestPacket packet, IPayloadContext context) {
        if (packet.containerId() < 0 || packet.page() < -1 || packet.page() > 4096) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUseTerminal(player)) {
                menu.driveCells().request(player, packet.page());
            }
        });
    }
}
