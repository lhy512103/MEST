package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.client.gui.AESubScreen;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Incremental, menu-scoped synchronization for the pattern access panel.
 */
public record PatternProviderListPacket(
        int containerId,
        long epoch,
        Operation operation,
        long providerId,
        long revision,
        int chunkIndex,
        int chunkCount,
        int inventorySize,
        long sortOrder,
        @Nullable PatternContainerGroup group,
        Int2ObjectMap<ItemStack> slots) implements CustomPacketPayload {
    public static final Type<PatternProviderListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "pattern_provider_list"));

    /** Keeps every payload well below Minecraft's clientbound custom-payload limit. */
    static final int MAX_SLOTS_PER_PACKET = PatternProviderClientState.MAX_SLOTS_PER_CHUNK;

    private static final StreamCodec<RegistryFriendlyByteBuf, Int2ObjectMap<ItemStack>> SLOTS_CODEC =
            ByteBufCodecs.map(Int2ObjectArrayMap::new,
                    ByteBufCodecs.SHORT.map(Short::intValue, Integer::shortValue),
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    MAX_SLOTS_PER_PACKET);

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternProviderListPacket> STREAM_CODEC =
            StreamCodec.ofMember(PatternProviderListPacket::write, PatternProviderListPacket::read);

    @OnlyIn(Dist.CLIENT)
    private static final ClientState CLIENT_STATE = new ClientState();

    public enum Operation {
        RESET,
        FULL,
        DELTA,
        REMOVE
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static PatternProviderListPacket reset(int containerId, long epoch) {
        return new PatternProviderListPacket(containerId, epoch, Operation.RESET,
                0, 0, 0, 1, 0, 0, null, new Int2ObjectArrayMap<>());
    }

    public static PatternProviderListPacket full(int containerId, long epoch, long providerId, long revision,
            int chunkIndex, int chunkCount, int inventorySize, long sortOrder, PatternContainerGroup group,
            Int2ObjectMap<ItemStack> slots) {
        return new PatternProviderListPacket(containerId, epoch, Operation.FULL,
                providerId, revision, chunkIndex, chunkCount, inventorySize, sortOrder, group, slots);
    }

    public static PatternProviderListPacket delta(int containerId, long epoch, long providerId, long revision,
            int chunkIndex, int chunkCount, Int2ObjectMap<ItemStack> slots) {
        return new PatternProviderListPacket(containerId, epoch, Operation.DELTA,
                providerId, revision, chunkIndex, chunkCount, 0, 0, null, slots);
    }

    public static PatternProviderListPacket remove(int containerId, long epoch, long providerId) {
        return new PatternProviderListPacket(containerId, epoch, Operation.REMOVE,
                providerId, 0, 0, 1, 0, 0, null, new Int2ObjectArrayMap<>());
    }

    private static PatternProviderListPacket read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        long epoch = buf.readVarLong();
        Operation operation = buf.readEnum(Operation.class);
        if (operation == Operation.RESET) {
            return reset(containerId, epoch);
        }

        long providerId = buf.readVarLong();
        if (operation == Operation.REMOVE) {
            return remove(containerId, epoch, providerId);
        }

        long revision = buf.readVarLong();
        int chunkIndex = buf.readVarInt();
        int chunkCount = buf.readVarInt();
        int inventorySize = 0;
        long sortOrder = 0;
        PatternContainerGroup group = null;
        if (operation == Operation.FULL) {
            inventorySize = buf.readVarInt();
            sortOrder = buf.readVarLong();
            group = PatternContainerGroup.readFromPacket(buf);
        }
        var slots = SLOTS_CODEC.decode(buf);
        return new PatternProviderListPacket(containerId, epoch, operation, providerId, revision,
                chunkIndex, chunkCount, inventorySize, sortOrder, group, slots);
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarLong(epoch);
        buf.writeEnum(operation);
        if (operation == Operation.RESET) {
            return;
        }

        buf.writeVarLong(providerId);
        if (operation == Operation.REMOVE) {
            return;
        }

        buf.writeVarLong(revision);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        if (operation == Operation.FULL) {
            buf.writeVarInt(inventorySize);
            buf.writeVarLong(sortOrder);
            if (group == null) {
                throw new IllegalStateException("Full pattern provider update is missing its group");
            }
            group.writeToPacket(buf);
        }
        SLOTS_CODEC.encode(buf, slots);
    }

    public static void handle(PatternProviderListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> handleClient(packet));
    }

    @OnlyIn(Dist.CLIENT)
    public static void beginClientSubscription(MESTMenu menu) {
        CLIENT_STATE.beginSession(menu);
    }

    @OnlyIn(Dist.CLIENT)
    public static void endClientSubscription(MESTMenu menu) {
        CLIENT_STATE.endSession(menu);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(PatternProviderListPacket packet) {
        var minecraft = Minecraft.getInstance();
        MESTScreen screen;
        if (minecraft.screen instanceof MESTScreen currentScreen) {
            screen = currentScreen;
        } else if (minecraft.screen instanceof AESubScreen<?, ?> subScreen
                && subScreen.getParent() instanceof MESTScreen parentScreen) {
            screen = parentScreen;
        } else {
            return;
        }
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof MESTMenu menu)
                || menu.containerId != packet.containerId()) {
            return;
        }

        var result = CLIENT_STATE.apply(packet);
        if (result.requestResync()) {
            PacketDistributor.sendToServer(new Request(menu.containerId, true));
        }
        if (result.entries() != null) {
            screen.updatePatternProviders(result.entries());
        }
    }

    /** Client-side immutable view consumed by {@link com.lhy.mest.client.panel.PatternAccessPanel}. */
    public record Entry(
            long epoch,
            long providerId,
            long revision,
            PatternContainerGroup group,
            int inventorySize,
            long sortOrder,
            Int2ObjectMap<ItemStack> slots) {
    }

    public record Request(int containerId, boolean subscribe) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID,
                        "pattern_provider_list_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                Request::containerId,
                ByteBufCodecs.BOOL,
                Request::subscribe,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Request packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player
                        && player.containerMenu instanceof MESTMenu menu
                        && menu.containerId == packet.containerId()) {
                    menu.getPatternAccessSession().setSubscribed(player, packet.subscribe());
                }
            });
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static final class ClientState {
        private static final Comparator<Entry> ENTRY_ORDER = Comparator
                .comparing((Entry entry) -> entry.group().name().getString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(Entry::sortOrder)
                .thenComparingLong(Entry::providerId);

        private final PatternProviderClientState<ItemStack, PatternContainerGroup> state =
                new PatternProviderClientState<>(new PatternProviderClientState.ValueOps<>() {
                    @Override
                    public ItemStack copy(ItemStack value) {
                        return value.copy();
                    }

                    @Override
                    public boolean isEmpty(ItemStack value) {
                        return value.isEmpty();
                    }

                    @Override
                    public boolean matches(ItemStack left, ItemStack right) {
                        return ItemStack.matches(left, right);
                    }
                });
        @Nullable
        private MESTMenu activeMenu;

        void beginSession(MESTMenu menu) {
            boolean continueExistingEpoch = activeMenu == menu;
            activeMenu = menu;
            state.beginSession(menu.containerId, continueExistingEpoch);
        }

        void endSession(MESTMenu menu) {
            if (activeMenu == menu) {
                activeMenu = null;
                state.endSession();
            }
        }

        ClientApplyResult apply(PatternProviderListPacket packet) {
            var slots = new java.util.HashMap<Integer, ItemStack>();
            for (var entry : packet.slots().int2ObjectEntrySet()) {
                slots.put(entry.getIntKey(), entry.getValue());
            }
            var update = new PatternProviderClientState.Update<>(
                    packet.containerId(),
                    packet.epoch(),
                    PatternProviderClientState.Operation.valueOf(packet.operation().name()),
                    packet.providerId(),
                    packet.revision(),
                    packet.chunkIndex(),
                    packet.chunkCount(),
                    packet.inventorySize(),
                    packet.sortOrder(),
                    packet.group(),
                    slots);
            var result = state.apply(update);
            boolean requestResync = result.outcome() == PatternProviderClientState.Outcome.RESYNC_REQUIRED;
            if (result.outcome() != PatternProviderClientState.Outcome.CHANGED) {
                return new ClientApplyResult(null, requestResync);
            }

            var entries = new ArrayList<Entry>(result.providers().size());
            for (var provider : result.providers()) {
                var providerSlots = new Int2ObjectArrayMap<ItemStack>();
                for (var slot : provider.slots().entrySet()) {
                    providerSlots.put(slot.getKey().intValue(), slot.getValue().copy());
                }
                entries.add(new Entry(provider.epoch(), provider.providerId(), provider.revision(),
                        provider.metadata(), provider.inventorySize(), provider.sortOrder(), providerSlots));
            }
            entries.sort(ENTRY_ORDER);
            return new ClientApplyResult(entries, requestResync);
        }
    }

    private record ClientApplyResult(@Nullable List<Entry> entries, boolean requestResync) {
    }
}
