package com.lhy.mest.compat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.features.HotkeyAction;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.me.helpers.PlayerSource;

import de.mari_023.ae2wtlib.wct.CraftingTerminalHandler;

/** Adds the spliced terminal to WTLib's existing store-held-stack key. */
public final class MestStowHotkey implements HotkeyAction {
    public static final String HOTKEY_ID = "ae2wtlib_stow";

    @Override
    public boolean run(Player player) {
        // Returning false lets WTLib's existing action handle a live WCT, even if its grid is full.
        // Do not try that action here and then insert its remainder into a different MEST grid.
        CraftingTerminalHandler handler = CraftingTerminalHandler.getCraftingTerminalHandler(player);
        if (handler.inRange() && handler.getTargetGrid() != null) {
            return false;
        }
        if (MestWtlibSupport.mestStack(player).isEmpty()) {
            return false;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty() || held.isNotReplaceableByPickAction(player, player.getInventory().selected)) {
            return false;
        }
        IGrid grid = MestWtlibSupport.mestGrid(player);
        if (grid == null || grid.getStorageService() == null) {
            return false;
        }
        AEItemKey key = AEItemKey.of(held);
        if (key == null) {
            return false;
        }
        return StowTransfer.insert(held.getCount(),
                amount -> grid.getStorageService().getInventory()
                        .insert(key, amount, Actionable.MODULATE, new PlayerSource(player)),
                held::setCount);
    }
}
