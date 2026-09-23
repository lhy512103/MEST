package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.config.MestConfig;
import com.lhy.mest.terminal.ToolkitBarState;
import com.lhy.mest.terminal.ToolkitInternalInventory;

/**
 * Client snapshot of the player's toolkit: the 18 extra-bar cells, plus settings and remembered
 * slots whenever those changed. The page is only sent when the server changed it, so it cannot
 * undo a selection the client is already showing.
 */
public record ToolkitBarSyncPacket(List<ItemStack> stacks, @Nullable Settings settings)
        implements CustomPacketPayload {
    public static final Type<ToolkitBarSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarSyncPacket> STREAM_CODEC =
            StreamCodec.of(ToolkitBarSyncPacket::write, ToolkitBarSyncPacket::read);

    public record Settings(boolean barEnabled, boolean quickMove, @Nullable ToolkitBarState.Bar page,
            List<ItemStack> memory) {
        public Settings {
            memory = List.copyOf(memory);
        }
    }

    private record Sent(List<ItemStack> stacks, int settingsRevision, int pageRevision) {
    }

    private static final Map<ServerPlayer, Sent> LAST_SENT = new WeakHashMap<>();

    public ToolkitBarSyncPacket {
        stacks = List.copyOf(stacks);
    }

    private static void write(RegistryFriendlyByteBuf buf, ToolkitBarSyncPacket packet) {
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    i < packet.stacks.size() ? packet.stacks.get(i) : ItemStack.EMPTY);
        }
        Settings settings = packet.settings;
        buf.writeBoolean(settings != null);
        if (settings == null) {
            return;
        }
        buf.writeBoolean(settings.barEnabled());
        buf.writeBoolean(settings.quickMove());
        buf.writeByte(settings.page() == null ? -1 : settings.page().ordinal());
        buf.writeVarInt(settings.memory().size());
        for (ItemStack remembered : settings.memory()) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, remembered);
        }
    }

    private static ToolkitBarSyncPacket read(RegistryFriendlyByteBuf buf) {
        List<ItemStack> stacks = new ArrayList<>(ToolkitBarState.VISIBLE_TOOLKIT_SLOTS);
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        if (!buf.readBoolean()) {
            return new ToolkitBarSyncPacket(stacks, null);
        }
        boolean barEnabled = buf.readBoolean();
        boolean quickMove = buf.readBoolean();
        int pageIndex = buf.readByte();
        ToolkitBarState.Bar page = pageIndex >= 0 && pageIndex < ToolkitBarState.Bar.values().length
                ? ToolkitBarState.Bar.values()[pageIndex]
                : null;
        int count = buf.readVarInt();
        if (count < 0 || count > MestConfig.TOOLKIT_MAX) {
            throw new IllegalArgumentException("Toolkit memory size out of range: " + count);
        }
        List<ItemStack> memory = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            memory.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return new ToolkitBarSyncPacket(stacks, new Settings(barEnabled, quickMove, page, memory));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void send(ServerPlayer player) {
        send(player, false);
    }

    /** Everything, including the page. For a client that has just (re)created its player. */
    public static void sendFull(ServerPlayer player) {
        LAST_SENT.remove(player);
        send(player, false);
    }

    /**
     * @param force when true, send the cells even if they match the last snapshot. A client equip
     *              prediction can change a toolkit cell without the server snapshot changing, and
     *              the dedupe would otherwise leave that prediction in place.
     */
    public static void send(ServerPlayer player, boolean force) {
        ToolkitInternalInventory toolkit = ToolkitBarState.data(player);
        Sent previous = LAST_SENT.get(player);
        boolean settingsChanged = previous == null || previous.settingsRevision() != toolkit.settingsRevision();
        boolean pageChanged = previous == null || previous.pageRevision() != toolkit.pageRevision();
        if (!force && !settingsChanged && previous != null && sameAsLive(previous.stacks(), player)) {
            return;
        }
        List<ItemStack> stacks = snapshot(player);
        Settings settings = settingsChanged
                ? new Settings(toolkit.barEnabled(), toolkit.quickMove(),
                        pageChanged ? toolkit.page() : null, toolkit.memorySnapshot())
                : null;
        LAST_SENT.put(player, new Sent(stacks, toolkit.settingsRevision(), toolkit.pageRevision()));
        PacketDistributor.sendToPlayer(player, new ToolkitBarSyncPacket(stacks, settings));
    }

    public static void handle(ToolkitBarSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() != null && context.player().level().isClientSide()) {
                ToolkitBarState.applyClientSync(context.player(), packet.stacks(), packet.settings());
            }
        });
    }

    private static List<ItemStack> snapshot(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>(ToolkitBarState.VISIBLE_TOOLKIT_SLOTS);
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            stacks.add(ToolkitBarState.stackAt(player, i).copy());
        }
        return stacks;
    }

    private static boolean sameAsLive(List<ItemStack> sent, ServerPlayer player) {
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            ItemStack previous = i < sent.size() ? sent.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(previous, ToolkitBarState.stackAt(player, i))) {
                return false;
            }
        }
        return true;
    }
}
