package com.lhy.mest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.inventory.Slot;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;

@Mixin(AEBaseMenu.class)
public interface AEBaseMenuAccessor {
    @Invoker("addSlot")
    Slot mest$addSlot(Slot slot, SlotSemantic semantic);
}
