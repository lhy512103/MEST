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

/** Bounded viewport of physical storage-cell icons with metadata for every visible drive group. */
public record DriveCellListPacket(int containerId, int revision, int windowStart, int totalSlots,
        boolean truncated, List<Group> groups, List<Row> rows) implements CustomPacketPayload {
    public static final int MAX_ROWS = 256;
    public static final int MAX_GROUPS = 256;
    public static final Type<DriveCellListPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_cell_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DriveCellListPacket> STREAM_CODEC =
            StreamCodec.ofMember(DriveCellListPacket::write, DriveCellListPacket::read);

    /** The index within groups is the drive ordinal used by each row and by priority actions. */
    public record Group(Component name, Component location, int slots, int priority, boolean editable,
            boolean powered, long usedBytes, long totalBytes) {
    }

    /** Status is a {@code CellState} ordinal; capacity fields are zero when the cell exposes no byte usage. */
    public record Row(int drive, int slot, ItemStack stack, int status, long usedBytes, long totalBytes,
            long usedTypes, long totalTypes) {
        public static final int STATUS_COUNT = 5;

        public Row(int drive, int slot, ItemStack stack) {
            this(drive, slot, stack, 0, 0, 0, 0, 0);
        }

        public boolean hasCapacity() {
            return totalBytes > 0;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        if (!validHeader(containerId, revision, windowStart, totalSlots, groups.size(), rows.size())
                || !validEntries(groups, rows, windowStart, totalSlots)) {
            throw new IllegalArgumentException("Invalid drive viewport");
        }
        buf.writeVarInt(containerId);
        buf.writeVarInt(revision);
        buf.writeVarInt(windowStart);
        buf.writeVarInt(totalSlots);
        buf.writeBoolean(truncated);
        buf.writeVarInt(groups.size());
        for (Group group : groups) {
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, group.name());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, group.location());
            buf.writeVarInt(group.slots());
            buf.writeInt(group.priority());
            buf.writeBoolean(group.editable());
            buf.writeBoolean(group.powered());
            buf.writeVarLong(group.usedBytes());
            buf.writeVarLong(group.totalBytes());
        }
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeVarInt(row.drive());
            buf.writeVarInt(row.slot());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, row.stack());
            buf.writeByte(row.status());
            buf.writeVarLong(row.usedBytes());
            buf.writeVarLong(row.totalBytes());
            buf.writeVarLong(row.usedTypes());
            buf.writeVarLong(row.totalTypes());
        }
    }

    private static DriveCellListPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int revision = buf.readVarInt();
            int start = buf.readVarInt();
            int total = buf.readVarInt();
            boolean truncated = buf.readBoolean();
            int groupCount = buf.readVarInt();
            if (!validHeader(container, revision, start, total, groupCount, 0)) {
                throw new IllegalArgumentException("Invalid drive viewport header");
            }
            List<Group> groups = new ArrayList<>(groupCount);
            for (int i = 0; i < groupCount; i++) {
                Component name = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
                Component location = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
                int slots = buf.readVarInt();
                int priority = buf.readInt();
                boolean editable = buf.readBoolean();
                boolean powered = buf.readBoolean();
                long usedBytes = buf.readVarLong();
                long totalBytes = buf.readVarLong();
                if (slots < 1 || slots > 128) {
                    throw new IllegalArgumentException("Invalid drive group");
                }
                groups.add(new Group(name, location, slots, priority, editable, powered, usedBytes, totalBytes));
            }
            int size = buf.readVarInt();
            if (!validHeader(container, revision, start, total, groupCount, size)) {
                throw new IllegalArgumentException("Invalid drive row count");
            }
            List<Row> rows = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int drive = buf.readVarInt();
                int slot = buf.readVarInt();
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int status = buf.readByte();
                long usedBytes = buf.readVarLong();
                long totalBytes = buf.readVarLong();
                long usedTypes = buf.readVarLong();
                long totalTypes = buf.readVarLong();
                rows.add(new Row(drive, slot, stack, status, usedBytes, totalBytes, usedTypes, totalTypes));
            }
            if (!validEntries(groups, rows, start, total) || buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive viewport data");
            }
            return new DriveCellListPacket(container, revision, start, total, truncated,
                    List.copyOf(groups), List.copyOf(rows));
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid payloads are discarded, not routed to the screen.
            }
            return new DriveCellListPacket(-1, 0, 0, 0, false, List.of(), List.of());
        }
    }

    private static boolean validHeader(int container, int revision, int start, int total, int groups, int rows) {
        return container >= 0 && revision >= 0 && start >= 0 && total >= 0 && start <= total
                && groups >= 0 && groups <= MAX_GROUPS && rows >= 0 && rows <= MAX_ROWS
                && rows <= total - start;
    }

    private static boolean validEntries(List<Group> groups, List<Row> rows, int start, int total) {
        int slots = 0;
        for (Group group : groups) {
            if (group.name() == null || group.location() == null || group.slots() < 1 || group.slots() > 128
                    || group.usedBytes() < 0 || group.totalBytes() < 0) {
                return false;
            }
            slots += group.slots();
        }
        if (slots != total || rows.size() != Math.min(MAX_ROWS, total - start)) {
            return false;
        }
        int groupIndex = 0;
        int groupStart = 0;
        for (int index = 0; index < rows.size(); index++) {
            int absolute = start + index;
            while (groupIndex < groups.size() && groupStart + groups.get(groupIndex).slots() <= absolute) {
                groupStart += groups.get(groupIndex++).slots();
            }
            Row row = rows.get(index);
            if (groupIndex == groups.size() || row.drive() != groupIndex
                    || row.slot() != absolute - groupStart || row.stack() == null
                    || row.status() < 0 || row.status() >= Row.STATUS_COUNT
                    || row.usedBytes() < 0 || row.totalBytes() < 0
                    || row.usedTypes() < 0 || row.totalTypes() < 0) {
                return false;
            }
        }
        return true;
    }
}
