package com.lhy.mest.integration;

import net.minecraft.client.Minecraft;

import com.lhy.mest.terminal.MESTMenu;

/** Client toggle and visibility for the extra JEI/EMI “pull items” recipe button. */
public final class MestPullItemsSupport {
    private static boolean enabled = true;

    private MestPullItemsSupport() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean shouldShowExtraButton() {
        if (!enabled) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        return player != null
                && player.containerMenu instanceof MESTMenu menu
                && MestRecipeTransferContext.bothRecipeModulesVisible(menu);
    }
}
