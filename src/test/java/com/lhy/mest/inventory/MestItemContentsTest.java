package com.lhy.mest.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.network.connection.ConnectionType;

class MestItemContentsTest {
    @BeforeAll
    static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void lastSlotSurvivesPersistentCodecRoundTrip() {
        var contents = new MestItemContents(withLastSlot(576));
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        JsonElement encoded = MestItemContents.CODEC.encodeStart(ops, contents).getOrThrow();
        var decoded = MestItemContents.CODEC.parse(ops, encoded).getOrThrow();
        assertEquals(contents, decoded);
        assertEquals(576, decoded.items().size());
        assertTrue(decoded.items().get(575).is(Items.STONE));
    }

    @Test
    void oldVanillaSaveRemainsReadableAtTheSameComponentId() {
        var old = ItemContainerContents.fromItems(withLastSlot(256));
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        JsonElement encoded = ItemContainerContents.CODEC.encodeStart(ops, old).getOrThrow();
        var decoded = MestItemContents.CODEC.parse(ops, encoded).getOrThrow();
        assertEquals(256, decoded.items().size());
        assertTrue(decoded.items().get(255).is(Items.STONE));
    }

    @Test
    void all576SlotsSurviveNetworkRoundTrip() {
        var items = new net.neoforged.neoforge.registries.RegistryBuilder<>(
                net.minecraft.core.registries.Registries.ITEM).sync(true).disableRegistrationCheck().create();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            net.minecraft.core.Registry.register(items, entry.getKey().location(), entry.getValue());
        }
        items.freeze();
        var registryAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(items));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess, ConnectionType.NEOFORGE);
        try {
            var contents = new MestItemContents(withLastSlot(576));
            MestItemContents.STREAM_CODEC.encode(buffer, contents);
            assertEquals(contents, MestItemContents.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void contentsAreDetachedFromCallers() {
        var input = new ItemStack(Items.STONE, 12);
        var contents = new MestItemContents(List.of(input));
        input.setCount(1);
        contents.items().getFirst().setCount(2);
        assertEquals(12, contents.items().getFirst().getCount());
    }

    @Test
    void oversizedContainersAndSlotIndicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MestItemContents(withLastSlot(577)));
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var encoded = MestItemContents.CODEC.encodeStart(ops, new MestItemContents(withLastSlot(576))).getOrThrow();
        encoded.getAsJsonArray().get(0).getAsJsonObject().addProperty("slot", 576);
        assertTrue(MestItemContents.CODEC.parse(ops, encoded).error().isPresent());
    }

    @Test
    void slotInventoryWritesTheLastSlotWithoutVanillaContainerConversion() {
        var saved = new AtomicReference<MestItemContents>();
        var inventory = new PersistentItemInventory(576, List.of(), () -> false,
                items -> saved.set(new MestItemContents(items)));
        inventory.setItemDirect(575, new ItemStack(Items.STONE));
        assertTrue(saved.get().items().get(575).is(Items.STONE));
    }

    private static List<ItemStack> withLastSlot(int size) {
        var items = new ArrayList<ItemStack>(Collections.nCopies(size, ItemStack.EMPTY));
        items.set(size - 1, new ItemStack(Items.STONE));
        return items;
    }
}
