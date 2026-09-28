package com.lhy.mest.network;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.parts.IPartHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

/** World location of a pattern container, used for highlight / open-UI actions. */
public record PatternProviderLoc(BlockPos pos, @Nullable Direction face, ResourceKey<Level> dimension) {
    public ResourceLocation dimensionId() {
        return dimension.location();
    }

    @Nullable
    static PatternProviderLoc from(PatternContainer container) {
        BlockEntity blockEntity = null;
        if (container instanceof PatternProviderLogicHost host) {
            blockEntity = host.getBlockEntity();
        } else if (container instanceof BlockEntity be) {
            blockEntity = be;
        }
        if (blockEntity == null || blockEntity.getLevel() == null) {
            return null;
        }
        Direction face = null;
        if (blockEntity instanceof IPartHost partHost) {
            for (Direction direction : Direction.values()) {
                if (partHost.getPart(direction) == container) {
                    face = direction;
                    break;
                }
            }
        }
        return new PatternProviderLoc(blockEntity.getBlockPos(), face, blockEntity.getLevel().dimension());
    }
}
