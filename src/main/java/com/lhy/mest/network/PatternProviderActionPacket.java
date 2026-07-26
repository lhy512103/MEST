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

/** A versioned action against one provider slot in the current menu session. */
public record PatternProviderActionPacket(
        int containerId,
        long epoch,
        long providerId,
        long revision,
        int providerSlot,
        Action action) implements CustomPacketPayload {
    public static final Type<PatternProviderActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "pattern_provider_action"));

    public static final StreamCodec<ByteBuf, PatternProviderActionPacket> STREAM_CODEC = StreamCodec.ofMember(
            PatternProviderActionPacket::write,
            PatternProviderActionPacket::read);

    public enum Action {
        PICKUP_OR_SET_DOWN,
        QUICK_MOVE_TO_PLAYER
    }

    private static final Action[] ACTIONS = Action.values();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static PatternProviderActionPacket read(ByteBuf buf) {
        int containerId = -1;
        try {
            containerId = ByteBufCodecs.VAR_INT.decode(buf);
            long epoch = ByteBufCodecs.VAR_LONG.decode(buf);
            long providerId = ByteBufCodecs.VAR_LONG.decode(buf);
            long revision = ByteBufCodecs.VAR_LONG.decode(buf);
            int providerSlot = ByteBufCodecs.VAR_INT.decode(buf);
            int actionOrdinal = buf.readUnsignedByte();
            if (actionOrdinal >= ACTIONS.length || buf.isReadable()) {
                throw new IllegalArgumentException("Malformed pattern provider action");
            }
            return new PatternProviderActionPacket(
                    containerId, epoch, providerId, revision, providerSlot, ACTIONS[actionOrdinal]);
        } catch (RuntimeException exception) {
            discardRemaining(buf);
            return invalid(containerId);
        }
    }

    private void write(ByteBuf buf) {
        if (!isWellFormed()) {
            throw new IllegalStateException("Cannot encode a malformed pattern provider action");
        }
        ByteBufCodecs.VAR_INT.encode(buf, containerId);
        ByteBufCodecs.VAR_LONG.encode(buf, epoch);
        ByteBufCodecs.VAR_LONG.encode(buf, providerId);
        ByteBufCodecs.VAR_LONG.encode(buf, revision);
        ByteBufCodecs.VAR_INT.encode(buf, providerSlot);
        buf.writeByte(action.ordinal());
    }

    boolean isWellFormed() {
        return containerId >= 0
                && epoch > 0
                && providerId > 0
                && revision > 0
                && providerSlot >= 0
                && providerSlot < PatternProviderClientState.MAX_INVENTORY_SIZE
                && action != null;
    }

    private static PatternProviderActionPacket invalid(int containerId) {
        return new PatternProviderActionPacket(
                containerId, 0, 0, 0, -1, Action.PICKUP_OR_SET_DOWN);
    }

    private static void discardRemaining(ByteBuf buf) {
        try {
            buf.skipBytes(buf.readableBytes());
        } catch (RuntimeException ignored) {
            // Decoding must still return a rejected sentinel if the buffer itself is already invalid.
        }
    }

    public static void handle(PatternProviderActionPacket packet, IPayloadContext context) {
        if (!packet.isWellFormed()) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUsePatternAccess(player)) {
                menu.getPatternAccessSession().handleAction(player, packet);
            }
        });
    }
}
