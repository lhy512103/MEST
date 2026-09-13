package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.player.Player;

import appeng.api.inventories.InternalInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.ToolboxMenu;
import appeng.menu.slot.RestrictedInputSlot;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.MESTMenu;
import com.lhy.mest.terminal.MestNetworkToolkitAccess;

/**
 * AE machines only look for a carried {@code NetworkToolItem}. The spliced terminal already stores
 * a larger upgrade inventory; expose it as TOOLBOX slots when the player has no vanilla tool.
 */
@Mixin(ToolboxMenu.class)
public abstract class ToolboxMenuMixin {
    /** One-shot diagnostic: an empty machine toolbox with no terminal in reach. */
    private static boolean mest$warnedNoTerminal;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void mest$attachTerminalNetworkToolkit(AEBaseMenu menu, CallbackInfo ci) {
        if (menu instanceof MESTMenu || !menu.getSlots(SlotSemantics.TOOLBOX).isEmpty()) {
            return;
        }
        Player player = menu.getPlayer();
        InternalInventory inventory = MestNetworkToolkitAccess.inventoryOf(player);
        if (inventory == null) {
            if (!mest$warnedNoTerminal && !menu.isClientSide()) {
                mest$warnedNoTerminal = true;
                MESplicedterminal.LOGGER.info(
                        "MEST: no spliced terminal in the player's inventory, so machine toolboxes stay empty");
            }
            return;
        }
        AEBaseMenuAccessor access = (AEBaseMenuAccessor) menu;
        for (int slot = 0; slot < inventory.size(); slot++) {
            access.mest$addSlot(new RestrictedInputSlot(
                    RestrictedInputSlot.PlacableItemType.UPGRADES, inventory, slot),
                    SlotSemantics.TOOLBOX);
        }
        MESplicedterminal.LOGGER.info(
                "MEST: attached the spliced terminal's network toolkit ({} slots) to {}",
                inventory.size(), menu.getClass().getSimpleName());
    }
}
