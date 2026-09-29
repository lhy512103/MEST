package com.lhy.mest.registry;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
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

    /** Built-in network-tool upgrade inventory on the spliced terminal. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> NETWORK_TOOLKIT_INV =
            register("network_toolkit_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Legacy: the toolkit now lives on the player. Kept so old terminals decode and can be migrated. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TOOLKIT_INV =
            register("toolkit_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Legacy toolkit memory, migrated to the player. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TOOLKIT_MEMORY =
            register("toolkit_memory", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** Legacy extra-bar toggle, migrated to the player. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TOOLKIT_BAR =
            register("toolkit_bar", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** Legacy toolkit quick-move toggle, migrated to the player. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TOOLKIT_QUICK_MOVE =
            register("toolkit_quick_move", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** Legacy extra-bar page, dropped on migration. */
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

    /** The cell sitting in the cell workbench module. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> CELL_WORKBENCH_INV =
            register("cell_workbench_inv", builder -> builder
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** The workbench partition mirror, kept for the next cell in "keep on remove" copy mode. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> CELL_WORKBENCH_CONFIG =
            register("cell_workbench_config", builder -> builder
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG));

    /** True for AE2's "keep on remove" workbench copy mode. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> CELL_WORKBENCH_KEEP =
            register("cell_workbench_keep", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    private static <T> DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(
            String name, Consumer<DataComponentType.Builder<T>> configurer) {
        return DATA_COMPONENTS.register(name, () -> {
            DataComponentType.Builder<T> builder = DataComponentType.builder();
            configurer.accept(builder);
            return builder.build();
        });
    }
}
