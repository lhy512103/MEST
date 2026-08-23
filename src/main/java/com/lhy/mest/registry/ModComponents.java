package com.lhy.mest.registry;

import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
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

    private static <T> DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(
            String name, Consumer<DataComponentType.Builder<T>> configurer) {
        return DATA_COMPONENTS.register(name, () -> {
            DataComponentType.Builder<T> builder = DataComponentType.builder();
            configurer.accept(builder);
            return builder.build();
        });
    }
}
