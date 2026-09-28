package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;

class DriveCellPacketTest {
    @Test
    void actionRoundTripAndMalformedActionIsDiscarded() {
        RegistryFriendlyByteBuf buf = registryBuffer();
        try {
            var action = new DriveCellActionPacket(4, 8, 2, DriveCellActionPacket.Action.QUICK_MOVE);
            DriveCellActionPacket.STREAM_CODEC.encode(buf, action);
            assertEquals(action, DriveCellActionPacket.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
            buf.writeVarInt(4);
            buf.writeVarInt(8);
            buf.writeVarInt(2);
            buf.writeByte(255);
            assertEquals(-1, DriveCellActionPacket.STREAM_CODEC.decode(buf).containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void pageRoundTripAndOversizedLengthIsDiscarded() {
        RegistryFriendlyByteBuf buf = registryBuffer();
        try {
            var page = new DriveCellListPacket(4, 8, 0, 0, 0, false, List.of());
            DriveCellListPacket.STREAM_CODEC.encode(buf, page);
            var decoded = DriveCellListPacket.STREAM_CODEC.decode(buf);
            assertEquals(page.containerId(), decoded.containerId());
            assertEquals(page.rows().size(), decoded.rows().size());
            assertEquals(0, buf.readableBytes());
            buf.writeVarInt(4);
            buf.writeVarInt(8);
            buf.writeVarInt(0);
            buf.writeVarInt(100);
            buf.writeVarInt(3);
            buf.writeBoolean(false);
            buf.writeVarInt(DriveCellListPacket.PAGE_SIZE + 1);
            assertEquals(-1, DriveCellListPacket.STREAM_CODEC.decode(buf).containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void requestRejectsTruncationAndTrailingData() {
        ByteBuf buf = Unpooled.buffer();
        try {
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 4);
            assertTrue(DriveCellRequestPacket.STREAM_CODEC.decode(buf).containerId() < 0);
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 4);
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 0);
            buf.writeByte(1);
            assertTrue(DriveCellRequestPacket.STREAM_CODEC.decode(buf).containerId() < 0);
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    private static RegistryFriendlyByteBuf registryBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
    }
}
