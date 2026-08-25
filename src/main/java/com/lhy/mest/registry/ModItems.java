package com.lhy.mest.registry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
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

    private static ItemMEST splicedTerminalItem;

    /**
     * Create the item only while the item registry is unfrozen (wtlib's ITEM RegisterEvent).
     * The DeferredRegister later binds this same instance.
     */
    public static ItemMEST splicedTerminalItem() {
        if (splicedTerminalItem == null) {
            splicedTerminalItem = new ItemMEST();
        }
        return splicedTerminalItem;
    }

    public static final DeferredItem<ItemMEST> SPLICED_TERMINAL =
            ITEMS.register("spliced_terminal", ModItems::splicedTerminalItem);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MESplicedterminal.MODID))
                    .icon(() -> SPLICED_TERMINAL.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(SPLICED_TERMINAL.get()))
                    .build());
}
