package com.lhy.mest.inventory;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** Terminal inventories with the vanilla sparse save format and room for 64 rows. */
public final class MestItemContents {
    public static final int MAX_SLOTS = 9 * 64;
    public static final MestItemContents EMPTY = new MestItemContents(List.of());
    public static final Codec<MestItemContents> CODEC = Entry.CODEC.sizeLimitedListOf(MAX_SLOTS)
            .xmap(MestItemContents::fromEntries, MestItemContents::entries);
    public static final StreamCodec<RegistryFriendlyByteBuf, MestItemContents> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SLOTS))
                    .map(MestItemContents::new, contents -> contents.items);

    private final List<ItemStack> items;
    private final int hashCode;

    public MestItemContents(List<ItemStack> items) {
        if (items.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("Inventory exceeds " + MAX_SLOTS + " slots");
        }
        int end = items.size();
        while (end > 0 && items.get(end - 1).isEmpty()) {
            end--;
        }
        var copy = new ArrayList<ItemStack>(end);
        for (int i = 0; i < end; i++) {
            copy.add(items.get(i).copy());
        }
        this.items = List.copyOf(copy);
        this.hashCode = ItemStack.hashStackList(this.items);
    }

    public List<ItemStack> items() {
        return items.stream().map(ItemStack::copy).toList();
    }

    private static MestItemContents fromEntries(List<Entry> entries) {
        int size = entries.stream().mapToInt(Entry::slot).max().orElse(-1) + 1;
        var items = new ArrayList<ItemStack>(java.util.Collections.nCopies(size, ItemStack.EMPTY));
        for (Entry entry : entries) {
            items.set(entry.slot(), entry.item());
        }
        return new MestItemContents(items);
    }

    private List<Entry> entries() {
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                entries.add(new Entry(i, items.get(i)));
            }
        }
        return entries;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MestItemContents contents
                && ItemStack.listMatches(items, contents.items);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private record Entry(int slot, ItemStack item) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, MAX_SLOTS - 1).fieldOf("slot").forGetter(Entry::slot),
                ItemStack.CODEC.fieldOf("item").forGetter(Entry::item)).apply(instance, Entry::new));
    }
}
