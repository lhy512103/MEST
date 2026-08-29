package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.network.codec.ByteBufCodecs;

import org.junit.jupiter.api.Test;

class PatternCacheActionPacketTest {

    @Test
    void codecRoundTripsValidPacket() {
        var expected = new PatternCacheActionPacket(PatternCacheActionPacket.Action.TIMES_2, true);
        ByteBuf buf = Unpooled.buffer();
        try {
            PatternCacheActionPacket.STREAM_CODEC.encode(buf, expected);

            var decoded = PatternCacheActionPacket.STREAM_CODEC.decode(buf);

            assertTrue(decoded.isWellFormed());
            assertEquals(expected, decoded);
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void codecRejectsInvalidOrdinal() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 99);
            ByteBufCodecs.BOOL.encode(buf, true);

            var decoded = PatternCacheActionPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void codecRejectsTrailingData() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 0);
            ByteBufCodecs.BOOL.encode(buf, false);
            buf.writeByte(0);

            var decoded = PatternCacheActionPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void codecRejectsTruncatedPacket() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 0);

            var decoded = PatternCacheActionPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }
}
