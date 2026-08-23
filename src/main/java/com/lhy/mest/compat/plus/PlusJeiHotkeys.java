package com.lhy.mest.compat.plus;

import java.util.Optional;

import mezz.jei.api.ingredients.ITypedIngredient;
import net.neoforged.fml.ModList;

import com.extendedae_plus.client.ModKeybindings;
import com.extendedae_plus.compat.JeiRuntimeCompat;
import com.lhy.mest.client.panel.MEListPanel;

/** Plus "fill AE search from JEI hover" (default F) — only wired for MEStorageScreen by Plus itself. */
public final class PlusJeiHotkeys {
    private PlusJeiHotkeys() {
    }

    public static boolean fillSearchFromHoveredIngredient(MEListPanel panel, int keyCode, int scanCode) {
        if (panel == null || !ModList.get().isLoaded("extendedae_plus")) {
            return false;
        }
        if (!ModKeybindings.FILL_SEARCH_KEY.matches(keyCode, scanCode)) {
            return false;
        }
        Optional<ITypedIngredient<?>> hovered = JeiRuntimeCompat.getIngredientUnderMouse();
        if (hovered.isEmpty()) {
            return false;
        }
        String name = JeiRuntimeCompat.getTypedIngredientDisplayName(hovered.get());
        if (name == null || name.isEmpty()) {
            return false;
        }
        panel.setSearchValue(name);
        return true;
    }
}
