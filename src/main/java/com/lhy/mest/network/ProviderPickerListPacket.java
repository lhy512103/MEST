package com.lhy.mest.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.lhy.mest.MESplicedterminal;

/** Provider rows for the Plus picker module. Ids use Plus's Ctrl+Q {@code -1 - index} encoding. */
public record ProviderPickerListPacket(
        List<Long> ids, List<Component> names, List<Integer> emptySlots, boolean applyPreset)
        implements CustomPacketPayload {
    public static final Type<ProviderPickerListPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MESplicedterminal.MODID, "provider_picker_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProviderPickerListPacket> STREAM_CODEC =
            StreamCodec.ofMember(ProviderPickerListPacket::write, ProviderPickerListPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        int size = Math.min(ids.size(), Math.min(names.size(), emptySlots.size()));
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeLong(ids.get(i));
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, names.get(i));
            buf.writeVarInt(emptySlots.get(i));
        }
        buf.writeBoolean(applyPreset);
    }

    private static ProviderPickerListPacket read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Long> ids = new ArrayList<>(size);
        List<Component> names = new ArrayList<>(size);
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buf.readLong());
            names.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
            slots.add(buf.readVarInt());
        }
        return new ProviderPickerListPacket(ids, names, slots, buf.readBoolean());
    }
}
