package com.lhy.mest.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/** Authenticated priority adjustment against a menu-scoped drive ordinal and revision. */
public record DrivePriorityPacket(int containerId, int revision, int drive, int delta)
        implements CustomPacketPayload {
    public static final Type<DrivePriorityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_priority"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DrivePriorityPacket> STREAM_CODEC =
            StreamCodec.ofMember(DrivePriorityPacket::write, DrivePriorityPacket::read);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    /** Mirrors the AE2 priority screen steps: 1, 10, 100 and 1000 in either direction. */
    public static boolean validDelta(int delta) {
        int magnitude = Math.abs(delta);
        return magnitude == 1 || magnitude == 10 || magnitude == 100 || magnitude == 1000;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        if (containerId < 0 || revision < 0 || drive < 0 || drive >= DriveCellListPacket.MAX_GROUPS
                || !validDelta(delta)) {
            throw new IllegalArgumentException("Invalid drive priority action");
        }
        buf.writeVarInt(containerId);
        buf.writeVarInt(revision);
        buf.writeVarInt(drive);
        buf.writeVarInt(delta);
    }

    private static DrivePriorityPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int revision = buf.readVarInt();
            int drive = buf.readVarInt();
            int delta = buf.readVarInt();
            if (container < 0 || revision < 0 || drive < 0 || drive >= DriveCellListPacket.MAX_GROUPS
                    || !validDelta(delta) || buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive priority action");
            }
            return new DrivePriorityPacket(container, revision, drive, delta);
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid priority actions never reach a drive.
            }
            return new DrivePriorityPacket(-1, -1, -1, 0);
        }
    }

    public static void handle(DrivePriorityPacket packet, IPayloadContext context) {
        if (packet.containerId() < 0 || packet.revision() < 0 || packet.drive() < 0
                || packet.drive() >= DriveCellListPacket.MAX_GROUPS
                || !validDelta(packet.delta())) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUsePatternAccess(player)
                    && BUDGETS.tryAcquire(player)) {
                menu.driveCells().changePriority(player, packet.revision(), packet.drive(), packet.delta());
            }
        });
    }
}
