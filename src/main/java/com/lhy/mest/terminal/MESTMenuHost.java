package com.lhy.mest.terminal;

import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.api.implementations.blockentities.IViewCellStorage;
import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.items.contents.StackDependentSupplier;
import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.parts.reporting.CraftingTerminalPart;
import appeng.util.inv.SupplierInternalInventory;

import de.mari_023.ae2wtlib.api.AE2wtlibComponents;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

/**
 * Server-side host for the ME Spliced Terminal. Extends {@link WTMenuHost} (single grid connection,
 * ME storage access, wireless power, quantum-bridge linking) and additionally exposes a 3x3 crafting
 * matrix so the menu can act as a crafting terminal. The matrix is stored in the item itself via the
 * {@link AEComponents#CRAFTING_INV} data component, so it travels with the terminal.
 */
public class MESTMenuHost extends WTMenuHost
        implements IViewCellStorage, IPatternTerminalMenuHost, IPatternTerminalLogicHost {
    private final SupplierInternalInventory<InternalInventory> craftingGrid;
    private final PatternEncodingLogic patternEncodingLogic = new PatternEncodingLogic(this);

    public MESTMenuHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
        this.craftingGrid = new SupplierInternalInventory<>(
                new StackDependentSupplier<>(
                        this::getItemStack,
                        stack -> createInv(player, stack, AEComponents.CRAFTING_INV, 9)));
        this.patternEncodingLogic.readFromNBT(
                getItemStack().getOrDefault(AE2wtlibComponents.PATTERN_ENCODING_LOGIC, new CompoundTag()),
                player.registryAccess());
    }

    @Nullable
    @Override
    public InternalInventory getSubInventory(net.minecraft.resources.ResourceLocation id) {
        if (id.equals(CraftingTerminalPart.INV_CRAFTING)) {
            return craftingGrid;
        }
        return super.getSubInventory(id);
    }

    @Override
    public PatternEncodingLogic getLogic() {
        return patternEncodingLogic;
    }

    @Override
    public Level getLevel() {
        return getPlayer().level();
    }

    @Override
    public void markForSave() {
        CompoundTag tag = getItemStack().getOrDefault(AE2wtlibComponents.PATTERN_ENCODING_LOGIC, new CompoundTag());
        patternEncodingLogic.writeToNBT(tag, getPlayer().registryAccess());
        getItemStack().set(AE2wtlibComponents.PATTERN_ENCODING_LOGIC, tag);
    }
}
