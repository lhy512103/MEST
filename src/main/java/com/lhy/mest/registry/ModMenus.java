package com.lhy.mest.registry;

import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;

/**
 * Menu type registration. The {@link MenuType} itself is built (unregistered) inside {@link MESTMenu}
 * via AE2's {@code MenuTypeBuilder}; here we bind it into the game's menu registry.
 */
public final class ModMenus {
    private ModMenus() {}

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.MENU, MESplicedterminal.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<MESTMenu>> SPLICED_TERMINAL_MENU =
            MENUS.register("spliced_terminal", () -> MESTMenu.TYPE);
}
