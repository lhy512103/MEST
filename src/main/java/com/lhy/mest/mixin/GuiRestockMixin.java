package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.util.ReadableNumberConverter;

import de.mari_023.ae2wtlib.api.AE2wtlibComponents;
import de.mari_023.ae2wtlib.wct.CraftingTerminalHandler;

import com.lhy.mest.client.MestRestockClient;
import com.lhy.mest.compat.MestWtlibSupport;

/** Same hotbar overlay as wtlib GuiMixin, but for the spliced terminal. */
@Mixin(Gui.class)
public class GuiRestockMixin {
    @Final
    @Shadow
    private Minecraft minecraft;

    @Inject(
            method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V"),
            cancellable = true)
    private void mest$restockOverlay(
            GuiGraphics guiGraphics,
            int x,
            int y,
            DeltaTracker deltaTracker,
            Player player,
            ItemStack stack,
            int seed,
            CallbackInfo ci) {
        if (minecraft.player == null || minecraft.player.isCreative()) {
            return;
        }
        CraftingTerminalHandler wct = CraftingTerminalHandler.getCraftingTerminalHandler(minecraft.player);
        if (wct.isRestockEnabled()) {
            return;
        }
        ItemStack mest = MestWtlibSupport.mestStack(minecraft.player);
        if (mest.isEmpty() || !mest.getOrDefault(AE2wtlibComponents.RESTOCK, false)) {
            return;
        }
        if (stack.getCount() == 1 || !MestRestockClient.isRestockAble(stack)) {
            return;
        }
        String number = ReadableNumberConverter.format(MestRestockClient.accessibleAmount(stack), 3);
        if (number.startsWith(",")) {
            number = "0" + number;
        }
        guiGraphics.renderItemDecorations(minecraft.font, stack, x, y, number);
        ci.cancel();
    }
}
