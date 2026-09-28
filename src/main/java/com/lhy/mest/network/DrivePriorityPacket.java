package com.lhy.mest.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/** Opens the AE2 priority screen for a menu-scoped drive ordinal at the given revision. */
public record DrivePriorityPacket(int containerId, int revision, int drive) implements CustomPacketPayload {
    public static final Type<DrivePriorityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_priority"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DrivePriorityPacket> STREAM_CODEC =
            StreamCodec.ofMember(DrivePriorityPacket::write, DrivePriorityPacket::read);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static boolean valid(int container, int revision, int drive) {
        return container >= 0 && revision >= 0 && drive >= 0 && drive < DriveCellListPacket.MAX_GROUPS;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        if (!valid(containerId, revision, drive)) {
            throw new IllegalArgumentException("Invalid drive priority action");
        }
        buf.writeVarInt(containerId);
        buf.writeVarInt(revision);
        buf.writeVarInt(drive);
    }

    private static DrivePriorityPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int revision = buf.readVarInt();
            int drive = buf.readVarInt();
            if (!valid(container, revision, drive) || buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive priority action");
            }
            return new DrivePriorityPacket(container, revision, drive);
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid priority actions never reach a drive.
            }
            return new DrivePriorityPacket(-1, -1, -1);
        }
    }

    public static void handle(DrivePriorityPacket packet, IPayloadContext context) {
        if (!valid(packet.containerId(), packet.revision(), packet.drive())) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUsePatternAccess(player)
                    && BUDGETS.tryAcquire(player)) {
                menu.driveCells().openPriority(player, packet.revision(), packet.drive());
            }
        });
    }
}
