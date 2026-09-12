package com.lhy.mest.terminal;

import net.minecraft.world.entity.player.Player;

/**
 * When an extra toolkit page is selected, {@code Inventory.getSelected} reports the toolkit cell.
 * Some AE2 lookups map the hand stack back onto a player-inventory slot by identity and throw when
 * it is missing; those calls run inside {@link #withVanillaHand(Runnable)}.
 */
public final class ToolkitHand {
    private static final ThreadLocal<Integer> VANILLA = ThreadLocal.withInitial(() -> 0);

    private ToolkitHand() {}

    public static void withVanillaHand(Runnable action) {
        VANILLA.set(VANILLA.get() + 1);
        try {
            action.run();
        } finally {
            int depth = VANILLA.get() - 1;
            if (depth <= 0) {
                VANILLA.remove();
            } else {
                VANILLA.set(depth);
            }
        }
    }

    public static boolean isOverrideActive(Player player) {
        if (VANILLA.get() > 0 || player.isSpectator()) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }
        return ToolkitBarState.isToolkitSelected(player);
    }
}
