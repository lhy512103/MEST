package com.lhy.mest.compat.plus;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.Unpooled;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.integration.modules.itemlists.EncodingHelper;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;

import com.extendedae_plus.network.ProvidersListS2CPacket;
import com.extendedae_plus.util.uploadPattern.CtrlQPendingUploadUtil;
import com.extendedae_plus.util.uploadPattern.ExtendedAEPatternUploadUtil;

/**
 * Hands encoded patterns to EAEP's pending-upload + provider-picker path.
 * Plus' encoding-menu packets require {@code PatternEncodingTermMenu}; the Ctrl+Q pending
 * path is the public API that still works with {@code MESTMenu}.
 */
public final class PlusEncodingUpload {
    private PlusEncodingUpload() {
    }

    public static boolean available() {
        return ModList.get().isLoaded("extendedae_plus");
    }

    /** Client: same recipe-type search key Plus JEI mixins write before the provider picker opens. */
    public static void captureRecipeSearchKey(Object rawRecipe) {
        if (!available() || rawRecipe == null) {
            return;
        }
        Recipe<?> recipe = null;
        if (rawRecipe instanceof RecipeHolder<?> holder) {
            recipe = holder.value();
        } else if (rawRecipe instanceof Recipe<?> value) {
            recipe = value;
        }
        if (recipe != null && EncodingHelper.isSupportedCraftingRecipe(recipe)) {
            ExtendedAEPatternUploadUtil.presetCraftingProviderSearchKey();
            return;
        }
        String name = null;
        if (recipe != null) {
            name = ExtendedAEPatternUploadUtil.mapRecipeTypeToSearchKey(recipe);
            if (name == null || name.isBlank()) {
                name = ExtendedAEPatternUploadUtil.deriveSearchKeyFromUnknownRecipe(recipe);
            }
        } else {
            name = ExtendedAEPatternUploadUtil.deriveSearchKeyFromUnknownRecipe(rawRecipe);
        }
        if (name != null && !name.isBlank()) {
            ExtendedAEPatternUploadUtil.setLastProcessingName(name);
        }
    }

    public static void presetSearchKeyForMode(EncodingMode mode) {
        if (!available() || mode == null) {
            return;
        }
        switch (mode) {
            case CRAFTING -> ExtendedAEPatternUploadUtil.presetCraftingProviderSearchKey();
            case SMITHING_TABLE -> ExtendedAEPatternUploadUtil.setLastProcessingName(
                    ExtendedAEPatternUploadUtil.resolveRecipeTypeSearchKey(
                            ResourceLocation.parse("minecraft:smithing"), null));
            case STONECUTTING -> ExtendedAEPatternUploadUtil.setLastProcessingName(
                    ExtendedAEPatternUploadUtil.resolveRecipeTypeSearchKey(
                            ResourceLocation.parse("minecraft:stonecutting"), null));
            default -> {
            }
        }
    }

    public static boolean uploadEncodedToMatrix(ServerPlayer player, RestrictedInputSlot encodedSlot, IGrid grid) {
        if (!available() || encodedSlot == null || grid == null) {
            return false;
        }
        ItemStack stack = encodedSlot.getItem();
        if (!ExtendedAEPatternUploadUtil.uploadPatternToMatrix(player, stack, grid)) {
            return false;
        }
        encodedSlot.set(ItemStack.EMPTY);
        encodedSlot.setChanged();
        return true;
    }

    public static boolean beginAndOpenPicker(ServerPlayer player, RestrictedInputSlot encodedSlot) {
        ItemStack stack = encodedSlot.getItem();
        boolean hasPattern = !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
        List<PatternContainer> containers = CtrlQPendingUploadUtil.listAvailableProvidersFromPlayerNetwork(player);
        if (hasPattern) {
            CtrlQPendingUploadUtil.beginPendingCtrlQUpload(player, stack.copy());
            encodedSlot.remove(1);
            encodedSlot.setChanged();
        }

        List<Long> ids = new ArrayList<>();
        List<Component> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            PatternContainer container = containers.get(i);
            int empty = ExtendedAEPatternUploadUtil.getAvailableSlots(container);
            if (empty <= 0) {
                continue;
            }
            ids.add(-1L - i);
            names.add(ExtendedAEPatternUploadUtil.getProviderDisplayNameComponent(container));
            slots.add(empty);
        }
        if (ids.isEmpty() && hasPattern) {
            CtrlQPendingUploadUtil.returnPendingCtrlQPatternToInventory(player);
        }
        player.connection.send(decodeProvidersList(player, ids, names, slots));
        return true;
    }

    private static ProvidersListS2CPacket decodeProvidersList(
            ServerPlayer player, List<Long> ids, List<Component> names, List<Integer> slots) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        buf.writeVarInt(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            buf.writeLong(ids.get(i));
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, names.get(i));
            buf.writeVarInt(slots.get(i));
        }
        return ProvidersListS2CPacket.STREAM_CODEC.decode(buf);
    }
}
