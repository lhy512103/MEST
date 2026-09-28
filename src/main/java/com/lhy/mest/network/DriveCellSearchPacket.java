package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import appeng.api.stacks.AEKey;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/** Drive cell content search; keys are client-resolved matches so localized and pinyin names work. */
public record DriveCellSearchPacket(int containerId, int searchId, String query, List<AEKey> keys)
        implements CustomPacketPayload {
    public static final int MAX_QUERY = 64;
    public static final int MAX_KEYS = 256;
    public static final Type<DriveCellSearchPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            MESplicedterminal.MODID, "drive_cell_search"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DriveCellSearchPacket> STREAM_CODEC =
            StreamCodec.ofMember(DriveCellSearchPacket::write, DriveCellSearchPacket::read);
    private static final PlayerTickBudgets BUDGETS = new PlayerTickBudgets(8);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static boolean valid(int container, int searchId, String query, int keys) {
        return container >= 0 && searchId >= 0 && query != null && query.length() <= MAX_QUERY
                && keys >= 0 && keys <= MAX_KEYS;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        if (!valid(containerId, searchId, query, keys.size())) {
            throw new IllegalArgumentException("Invalid drive search");
        }
        buf.writeVarInt(containerId);
        buf.writeVarInt(searchId);
        buf.writeUtf(query, MAX_QUERY);
        buf.writeVarInt(keys.size());
        for (AEKey key : keys) {
            AEKey.STREAM_CODEC.encode(buf, key);
        }
    }

    private static DriveCellSearchPacket read(RegistryFriendlyByteBuf buf) {
        try {
            int container = buf.readVarInt();
            int searchId = buf.readVarInt();
            String query = buf.readUtf(MAX_QUERY);
            int count = buf.readVarInt();
            if (!valid(container, searchId, query, count)) {
                throw new IllegalArgumentException("Invalid drive search");
            }
            List<AEKey> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                AEKey key = AEKey.STREAM_CODEC.decode(buf);
                if (key == null) {
                    throw new IllegalArgumentException("Invalid drive search key");
                }
                keys.add(key);
            }
            if (buf.isReadable()) {
                throw new IllegalArgumentException("Invalid drive search");
            }
            return new DriveCellSearchPacket(container, searchId, query, List.copyOf(keys));
        } catch (RuntimeException exception) {
            try {
                buf.skipBytes(buf.readableBytes());
            } catch (RuntimeException ignored) {
                // Invalid searches never reach a drive.
            }
            return new DriveCellSearchPacket(-1, 0, "", List.of());
        }
    }

    public static void handle(DriveCellSearchPacket packet, IPayloadContext context) {
        if (!valid(packet.containerId(), packet.searchId(), packet.query(), packet.keys().size())) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof MESTMenu menu
                    && menu.containerId == packet.containerId()
                    && menu.canUsePatternAccess(player)
                    && BUDGETS.tryAcquire(player)) {
                menu.driveCells().search(player, packet.searchId(), packet.query(), packet.keys());
            }
        });
    }
}
