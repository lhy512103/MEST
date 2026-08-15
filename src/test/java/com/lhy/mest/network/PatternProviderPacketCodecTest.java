package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.network.handling.IPayloadContext;

class PatternProviderPacketCodecTest {
    @Test
    void actionCodecRoundTripsValidPacket() {
        var expected = new PatternProviderActionPacket(
                7, 11, 13, 17, 19,
                PatternProviderActionPacket.Action.QUICK_MOVE_TO_PLAYER);
        ByteBuf buf = Unpooled.buffer();
        try {
            PatternProviderActionPacket.STREAM_CODEC.encode(buf, expected);

            assertEquals(expected, PatternProviderActionPacket.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void actionCodecRejectsInvalidOrdinalWithoutThrowing() {
        ByteBuf buf = Unpooled.buffer();
        try {
            writeActionEnvelope(buf);
            buf.writeByte(255);

            var decoded = PatternProviderActionPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void actionCodecRejectsTruncatedPacketWithoutThrowing() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 7);

            var decoded = PatternProviderActionPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(7, decoded.containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void listCodecRoundTripsValidReset() {
        var expected = PatternProviderListPacket.reset(23, 29);
        var buf = registryBuffer();
        try {
            PatternProviderListPacket.STREAM_CODEC.encode(buf, expected);

            var decoded = PatternProviderListPacket.STREAM_CODEC.decode(buf);

            assertTrue(decoded.isWellFormed());
            assertEquals(expected.containerId(), decoded.containerId());
            assertEquals(expected.epoch(), decoded.epoch());
            assertEquals(expected.operation(), decoded.operation());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void listCodecRejectsInvalidOperationWithoutThrowing() {
        var buf = registryBuffer();
        try {
            buf.writeVarInt(23);
            buf.writeVarLong(29);
            buf.writeVarInt(99);

            var decoded = PatternProviderListPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(23, decoded.containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void listCodecRejectsTruncatedPacketWithoutThrowing() {
        var buf = registryBuffer();
        try {
            buf.writeVarInt(23);

            var decoded = PatternProviderListPacket.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(23, decoded.containerId());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void listEnvelopeValidationRejectsMalformedNumericFields() {
        assertFalse(PatternProviderListPacket.reset(1, 0).isWellFormed());
        assertFalse(new PatternProviderListPacket(
                1, 2, PatternProviderListPacket.Operation.DELTA,
                3, 4, 1, 1, 0, 0, null, new Int2ObjectArrayMap<>()).isWellFormed());
        assertFalse(new PatternProviderListPacket(
                1, 2, PatternProviderListPacket.Operation.FULL,
                3, 4, 0, 1, PatternProviderClientState.MAX_INVENTORY_SIZE + 1,
                0, null, new Int2ObjectArrayMap<>()).isWellFormed());
    }

    @Test
    void fullChunkCountBoundsMatchSparseServerChunking() {
        int maximumInventory = PatternProviderClientState.MAX_INVENTORY_SIZE;
        int maximumChunks = PatternProviderClientState.MAX_CHUNKS_PER_UPDATE;

        assertTrue(PatternProviderListPacket.isPlausibleFullChunkCount(1, maximumInventory));
        assertTrue(PatternProviderListPacket.isPlausibleFullChunkCount(maximumChunks, maximumInventory));
        assertFalse(PatternProviderListPacket.isPlausibleFullChunkCount(0, maximumInventory));
        assertFalse(PatternProviderListPacket.isPlausibleFullChunkCount(maximumChunks + 1, maximumInventory));
        assertFalse(PatternProviderListPacket.isPlausibleFullChunkCount(2, 1));
    }

    @Test
    void malformedListPacketIsRejectedBeforeHandlerEnqueue() {
        var calls = new AtomicInteger();
        var context = (IPayloadContext) Proxy.newProxyInstance(
                IPayloadContext.class.getClassLoader(),
                new Class<?>[] { IPayloadContext.class },
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    return null;
                });

        PatternProviderClientBridge.handle(PatternProviderListPacket.reset(23, 0), context);

        assertEquals(0, calls.get());
    }

    @Test
    void requestCodecRejectsTruncatedPacketWithoutThrowing() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 31);

            var decoded = PatternProviderListPacket.Request.STREAM_CODEC.decode(buf);

            assertFalse(decoded.isWellFormed());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void requestValidationRejectsNegativeContainerId() {
        assertTrue(new PatternProviderListPacket.Request(0, true).isWellFormed());
        assertFalse(new PatternProviderListPacket.Request(-1, false).isWellFormed());
    }

    private static void writeActionEnvelope(ByteBuf buf) {
        ByteBufCodecs.VAR_INT.encode(buf, 7);
        ByteBufCodecs.VAR_LONG.encode(buf, 11L);
        ByteBufCodecs.VAR_LONG.encode(buf, 13L);
        ByteBufCodecs.VAR_LONG.encode(buf, 17L);
        ByteBufCodecs.VAR_INT.encode(buf, 19);
    }

    private static RegistryFriendlyByteBuf registryBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}
