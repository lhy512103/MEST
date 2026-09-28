package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import com.lhy.mest.MESplicedterminal;

/** One bounded page of physical storage-cell slots, never the contents stored inside a cell. */
public record DriveCellListPacket(int containerId, int revision, int page, int totalSlots,
        int totalDrives, boolean truncated, List<Row> rows) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 54;
    public static final Type<DriveCellListPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_cell_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DriveCellListPacket> STREAM_CODEC =
            StreamCodec.ofMember(DriveCellListPacket::write, DriveCellListPacket::read);

    public record Row(int drive, Component name, int slot, ItemStack stack) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        if (rows.size() > PAGE_SIZE) {
            throw new IllegalArgumentException("Too many drive slots");
        }
        buf.writeVarInt(containerId);
        buf.writeVarInt(revision);
        buf.writeVarInt(page);
        buf.writeVarInt(totalSlots);
        buf.writeVarInt(totalDrives);
        buf.writeBoolean(truncated);
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeVarInt(row.drive());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, row.name());
            buf.writeVarInt(row.slot());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, row.stack());
        }
    }

    private static DriveCellListPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int revision = buf.readVarInt();
            int page = buf.readVarInt();
            int total = buf.readVarInt();
            int drives = buf.readVarInt();
            boolean truncated = buf.readBoolean();
            int size = buf.readVarInt();
            if (container < 0 || revision < 0 || page < 0 || total < 0 || drives < 0
                    || size < 0 || size > PAGE_SIZE || total < size || page > 4096) {
                throw new IllegalArgumentException("Invalid drive page");
            }
            List<Row> rows = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int drive = buf.readVarInt();
                Component name = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
                int slot = buf.readVarInt();
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                if (drive < 0 || drive >= 256 || slot < 0 || slot >= 128) {
                    throw new IllegalArgumentException("Invalid drive slot");
                }
                rows.add(new Row(drive, name, slot, stack));
            }
            if (buf.isReadable()) {
                throw new IllegalArgumentException("Trailing drive data");
            }
            return new DriveCellListPacket(container, revision, page, total, drives, truncated, List.copyOf(rows));
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid payloads are discarded, not routed to the screen.
            }
            return new DriveCellListPacket(-1, 0, 0, 0, 0, false, List.of());
        }
    }
}
