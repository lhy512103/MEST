package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.ToolkitBarState;

/** Client snapshot of the 18 extra-bar toolkit cells. */
public record ToolkitBarSyncPacket(List<ItemStack> stacks) implements CustomPacketPayload {
    public static final Type<ToolkitBarSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "toolkit_bar_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolkitBarSyncPacket> STREAM_CODEC =
            StreamCodec.of(ToolkitBarSyncPacket::write, ToolkitBarSyncPacket::read);

    private static final Map<ServerPlayer, List<ItemStack>> LAST_SENT = new WeakHashMap<>();

    public ToolkitBarSyncPacket {
        stacks = List.copyOf(stacks);
    }

    private static void write(RegistryFriendlyByteBuf buf, ToolkitBarSyncPacket packet) {
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    i < packet.stacks.size() ? packet.stacks.get(i) : ItemStack.EMPTY);
        }
    }

    private static ToolkitBarSyncPacket read(RegistryFriendlyByteBuf buf) {
        List<ItemStack> stacks = new ArrayList<>(ToolkitBarState.VISIBLE_TOOLKIT_SLOTS);
        for (int i = 0; i < ToolkitBarState.VISIBLE_TOOLKIT_SLOTS; i++) {
            stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return new ToolkitBarSyncPacket(stacks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void send(ServerPlayer player) {
        send(player, false);
    }

    /**
     * @param force when true, send even if this snapshot matches the last one. A client equip
     *              prediction can change the toolkit cell without the server snapshot changing, and
     *              the dedupe would otherwise leave that prediction in place.
     */
    public static void send(ServerPlayer player, boolean force) {
        List<ItemStack> stacks = snapshot(player);
        if (!force) {
            List<ItemStack> previous = LAST_SENT.get(player);
            if (previous != null && same(previous, stacks)) {
                return;
            }
        }
        LAST_SENT.put(player, stacks);
        PacketDistributor.sendToPlayer(player, new ToolkitBarSyncPacket(stacks));
    }

    public static void handle(ToolkitBarSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() != null && context.player().level().isClientSide()) {
                ToolkitBarState.applyClientHotbar(context.player(), packet.stacks());
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

    private static boolean same(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!ItemStack.matches(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }
}
