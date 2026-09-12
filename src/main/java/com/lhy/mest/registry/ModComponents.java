package com.lhy.mest.registry;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.lhy.mest.MESplicedterminal;

/** Data components stored on the spliced terminal item. */
public final class ModComponents {
    private ModComponents() {
    }

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MESplicedterminal.MODID);

    /** Pattern cache inventory (36 slots, 4 rows × 9 columns). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> PATTERN_CACHE_INV =
            register("pattern_cache_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TRASH_INV =
            register("trash_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Toolkit inventory: an extension of the player inventory that only holds unstackable items. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TOOLKIT_INV =
            register("toolkit_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Remembered item type per toolkit slot; empty remembered slots prefer matching tools. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TOOLKIT_MEMORY =
            register("toolkit_memory", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Whether the toolkit quick bars are drawn beside the player's hotbar. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TOOLKIT_BAR =
            register("toolkit_bar", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** Shift-click / ME transfer tries toolkit (memory slots first) before other destinations. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TOOLKIT_QUICK_MOVE =
            register("toolkit_quick_move", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** Extra-bar page: 0=left, 1=vanilla hotbar, 2=right. Slot inside the page is Inventory.selected. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TOOLKIT_BAR_PAGE =
            register("toolkit_bar_page", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * Original hotbar parked while an extra toolkit page occupies {@code Inventory.items[0..8]}.
     * Cannot reuse toolkit slots: those reject stackable items.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> HOTBAR_STASH =
            register("hotbar_stash", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    private static <T> DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(
            String name, Consumer<DataComponentType.Builder<T>> configurer) {
        return DATA_COMPONENTS.register(name, () -> {
            DataComponentType.Builder<T> builder = DataComponentType.builder();
            configurer.accept(builder);
            return builder.build();
        });
    }
}
