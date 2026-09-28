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

/** Client action against every encoded pattern in the cache panel. */
public record PatternCacheActionPacket(Action action, boolean value) implements CustomPacketPayload {
    public static final Type<PatternCacheActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "pattern_cache_action"));

    private static final Action[] ACTIONS = Action.values();

    /** One packet rewrites the whole cache, so the quota is far tighter than for single-slot actions. */
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(4);

    public static final StreamCodec<ByteBuf, PatternCacheActionPacket> STREAM_CODEC = StreamCodec.ofMember(
            PatternCacheActionPacket::write,
            PatternCacheActionPacket::read);

    public enum Action {
        SWAP,
        TIMES_2,
        TIMES_3,
        TIMES_5,
        EQUALS_1,
        DIVIDE_2,
        DIVIDE_3,
        DIVIDE_5,
        TIMES_8,
        DIVIDE_8,
        TIMES_16,
        DIVIDE_16,
        TIMES_32,
        DIVIDE_32,
        ITEM_SUBSTITUTION,
        FLUID_SUBSTITUTION
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static PatternCacheActionPacket read(ByteBuf buf) {
        try {
            int actionOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            boolean value = ByteBufCodecs.BOOL.decode(buf);
            if (actionOrdinal < 0 || actionOrdinal >= ACTIONS.length || buf.isReadable()) {
                throw new IllegalArgumentException("Malformed pattern cache action");
            }
            return new PatternCacheActionPacket(ACTIONS[actionOrdinal], value);
        } catch (RuntimeException exception) {
            discardRemaining(buf);
            return invalid();
        }
    }

    private void write(ByteBuf buf) {
        if (!isWellFormed()) {
            throw new IllegalStateException("Cannot encode a malformed pattern cache action");
        }
        ByteBufCodecs.VAR_INT.encode(buf, action.ordinal());
        ByteBufCodecs.BOOL.encode(buf, value);
    }

    boolean isWellFormed() {
        return action != null;
    }

    private static PatternCacheActionPacket invalid() {
        return new PatternCacheActionPacket(null, false);
    }

    private static void discardRemaining(ByteBuf buf) {
        try {
            buf.skipBytes(buf.readableBytes());
        } catch (RuntimeException ignored) {
            // Decoding must still return a rejected sentinel if the buffer itself is already invalid.
        }
    }

    public static void handle(PatternCacheActionPacket packet, IPayloadContext context) {
        if (!packet.isWellFormed()) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.canUseTerminal(player)
                    && BUDGETS.tryAcquire(player)) {
                menu.handlePatternCacheAction(packet.action(), packet.value());
            }
        });
    }
}
