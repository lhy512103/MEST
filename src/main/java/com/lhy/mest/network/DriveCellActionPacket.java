package com.lhy.mest.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/** Opaque viewport/revision/row reference; clients never supply a target block or item stack. */
public record DriveCellActionPacket(int containerId, int revision, int row, Action action)
        implements CustomPacketPayload {
    public static final Type<DriveCellActionPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_cell_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DriveCellActionPacket> STREAM_CODEC =
            StreamCodec.ofMember(DriveCellActionPacket::write, DriveCellActionPacket::read);
    private static final Action[] ACTIONS = Action.values();
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    public enum Action {
        CURSOR, QUICK_MOVE,
        /** Toggle editing this cell in the terminal's cell workbench. */
        EDIT
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarInt(revision);
        buf.writeVarInt(row);
        buf.writeByte(action.ordinal());
    }

    private static DriveCellActionPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int revision = buf.readVarInt();
            int row = buf.readVarInt();
            int action = buf.readUnsignedByte();
            if (container < 0 || revision < 0 || row < 0 || row >= DriveCellListPacket.MAX_ROWS
                    || action >= ACTIONS.length || buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive action");
            }
            return new DriveCellActionPacket(container, revision, row, ACTIONS[action]);
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid actions never reach a drive.
            }
            return new DriveCellActionPacket(-1, -1, -1, null);
        }
    }

    public static void handle(DriveCellActionPacket packet, IPayloadContext context) {
        if (packet.action() == null || packet.containerId() < 0 || packet.revision() < 0
                || packet.row() < 0 || packet.row() >= DriveCellListPacket.MAX_ROWS) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUsePatternAccess(player)
                    && BUDGETS.tryAcquire(player)) {
                menu.driveCells().act(player, packet.revision(), packet.row(), packet.action());
            }
        });
    }
}
