package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
    void emptyViewportRoundTripAndOversizedGroupCountIsDiscarded() {
        RegistryFriendlyByteBuf buf = registryBuffer();
        try {
            var viewport = new DriveCellListPacket(4, 8, 0, 0, false, List.of(), List.of(), 0, List.of());
            DriveCellListPacket.STREAM_CODEC.encode(buf, viewport);
            var decoded = DriveCellListPacket.STREAM_CODEC.decode(buf);
            assertEquals(viewport.containerId(), decoded.containerId());
            assertTrue(decoded.groups().isEmpty());
            assertEquals(0, buf.readableBytes());
            buf.writeVarInt(4);
            buf.writeVarInt(8);
            buf.writeVarInt(0);
            buf.writeVarInt(100);
            buf.writeBoolean(false);
            buf.writeVarInt(DriveCellListPacket.MAX_GROUPS + 1);
            assertEquals(-1, DriveCellListPacket.STREAM_CODEC.decode(buf).containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void priorityRejectsInvalidDriveAndTrailingData() {
        RegistryFriendlyByteBuf buf = registryBuffer();
        try {
            var action = new DrivePriorityPacket(4, 8, 2);
            DrivePriorityPacket.STREAM_CODEC.encode(buf, action);
            assertEquals(action, DrivePriorityPacket.STREAM_CODEC.decode(buf));
            buf.writeVarInt(4);
            buf.writeVarInt(8);
            buf.writeVarInt(DriveCellListPacket.MAX_GROUPS);
            assertEquals(-1, DrivePriorityPacket.STREAM_CODEC.decode(buf).containerId());
            buf.writeVarInt(4);
            buf.writeVarInt(8);
            buf.writeVarInt(2);
            buf.writeByte(1);
            assertEquals(-1, DrivePriorityPacket.STREAM_CODEC.decode(buf).containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void requestRejectsTruncationAndTrailingData() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 4);
            assertTrue(DriveCellRequestPacket.STREAM_CODEC.decode(buf).containerId() < 0);
            ByteBufCodecs.VAR_INT.encode(buf, 4);
            ByteBufCodecs.VAR_INT.encode(buf, 0);
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
