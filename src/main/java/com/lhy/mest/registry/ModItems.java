package com.lhy.mest.registry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.item.ItemMEST;

/**
 * Item and creative-tab registration for the ME Spliced Terminal.
 */
public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MESplicedterminal.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MESplicedterminal.MODID);

    /**
     * The spliced terminal item. We supply a singleton {@link ItemMEST} instance via the deferred
     * register so the very same object reference is captured by the AE2WTLib terminal definition.
     */
    public static final DeferredItem<ItemMEST> SPLICED_TERMINAL =
            ITEMS.registerItem("spliced_terminal", props -> new ItemMEST(), new Item.Properties().stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MESplicedterminal.MODID))
                    .icon(() -> SPLICED_TERMINAL.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(SPLICED_TERMINAL.get()))
                    .build());
}
