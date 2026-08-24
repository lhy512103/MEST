package com.lhy.mest.terminal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.util.ConfigInventory;
import appeng.util.prioritylist.IPartitionList;

import de.mari_023.ae2wtlib.api.AE2wtlibComponents;

/**
 * Magnet filter config stored on the MEST item, using the same wtlib data components as
 * {@code MagnetHost}.
 */
public final class MestMagnetHost {
    public final ConfigInventory pickupConfig = ConfigInventory.configTypes(27)
            .changeListener(this::updatePickupFilter)
            .supportedTypes(AEKeyType.items())
            .build();
    public final ConfigInventory insertConfig = ConfigInventory.configTypes(27)
            .changeListener(this::updateInsertFilter)
            .supportedTypes(AEKeyType.items())
            .build();

    private IPartitionList pickupFilter;
    private IPartitionList insertFilter;
    private final Player player;
    private final ItemStack stack;

    public MestMagnetHost(Player player, ItemStack stack) {
        this.player = player;
        this.stack = stack;
        pickupConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.PICKUP_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
        insertConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.INSERT_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
        pickupFilter = createFilter(pickupConfig);
        insertFilter = createFilter(insertConfig);
    }

    private IPartitionList createFilter(ConfigInventory config) {
        IPartitionList.Builder builder = IPartitionList.builder();
        builder.fuzzyMode(FuzzyMode.IGNORE_ALL);
        for (int i = 0; i < config.size(); i++) {
            builder.add(config.getKey(i));
        }
        return builder.build();
    }

    private void updatePickupFilter() {
        pickupFilter = createFilter(pickupConfig);
        CompoundTag tag = stack.getOrDefault(AE2wtlibComponents.PICKUP_CONFIG, new CompoundTag());
        pickupConfig.writeToChildTag(tag, "", player.registryAccess());
        stack.set(AE2wtlibComponents.PICKUP_CONFIG, tag);
    }

    private void updateInsertFilter() {
        insertFilter = createFilter(insertConfig);
        CompoundTag tag = stack.getOrDefault(AE2wtlibComponents.INSERT_CONFIG, new CompoundTag());
        insertConfig.writeToChildTag(tag, "", player.registryAccess());
        stack.set(AE2wtlibComponents.INSERT_CONFIG, tag);
    }

    public IPartitionList getPickupFilter() {
        return pickupFilter;
    }

    public IncludeExclude getPickupMode() {
        return stack.getOrDefault(AE2wtlibComponents.PICKUP_MODE, IncludeExclude.BLACKLIST);
    }

    public void togglePickupMode() {
        stack.set(AE2wtlibComponents.PICKUP_MODE, toggle(getPickupMode()));
    }

    public IPartitionList getInsertFilter() {
        return insertFilter;
    }

    public IncludeExclude getInsertMode() {
        return stack.getOrDefault(AE2wtlibComponents.INSERT_MODE, IncludeExclude.BLACKLIST);
    }

    public void toggleInsertMode() {
        stack.set(AE2wtlibComponents.INSERT_MODE, toggle(getInsertMode()));
    }

    public void copyUp() {
        pickupConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.INSERT_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
    }

    public void copyDown() {
        insertConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.PICKUP_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
    }

    public void switchInsertPickup() {
        CompoundTag pickupTag = stack.getOrDefault(AE2wtlibComponents.PICKUP_CONFIG, new CompoundTag());
        CompoundTag insertTag = stack.getOrDefault(AE2wtlibComponents.INSERT_CONFIG, new CompoundTag());
        pickupConfig.writeToChildTag(pickupTag, "", player.registryAccess());
        stack.set(AE2wtlibComponents.INSERT_CONFIG, pickupTag);
        insertConfig.writeToChildTag(insertTag, "", player.registryAccess());
        stack.set(AE2wtlibComponents.PICKUP_CONFIG, insertTag);
        pickupConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.PICKUP_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
        insertConfig.readFromChildTag(
                stack.getOrDefault(AE2wtlibComponents.INSERT_CONFIG, new CompoundTag()),
                "",
                player.registryAccess());
    }

    public boolean matchesPickup(AEKey key) {
        return matches(pickupFilter, getPickupMode(), key);
    }

    public boolean matchesInsert(AEKey key) {
        return matches(insertFilter, getInsertMode(), key);
    }

    private static boolean matches(IPartitionList filter, IncludeExclude mode, AEKey key) {
        if (key == null) {
            return false;
        }
        if (filter.isEmpty()) {
            return mode == IncludeExclude.BLACKLIST;
        }
        boolean listed = filter.isListed(key);
        return mode == IncludeExclude.WHITELIST ? listed : !listed;
    }

    private static IncludeExclude toggle(IncludeExclude mode) {
        return mode == IncludeExclude.WHITELIST ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST;
    }
}
