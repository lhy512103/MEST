package com.lhy.mest.terminal;

import java.util.function.BiConsumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.items.contents.StackDependentSupplier;
import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.util.inv.SupplierInternalInventory;

import de.mari_023.ae2wtlib.api.AE2wtlibComponents;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.wct.WCTMenuHost;

import com.lhy.mest.config.MestConfig;
import com.lhy.mest.registry.ModComponents;

/**
 * Extends {@link WCTMenuHost} so trash / magnet submenus can use ae2wtlib's public menu types
 * (they locate {@code WCTMenuHost}). Pattern cache and encoding stay MEST-specific.
 */
public class MESTMenuHost extends WCTMenuHost
        implements IPatternTerminalMenuHost, IPatternTerminalLogicHost {
    public static int patternCacheSize() {
        return MestConfig.patternCacheSlots();
    }

    private final SupplierInternalInventory<InternalInventory> patternCache;
    private final SupplierInternalInventory<InternalInventory> trash;
    private final SupplierInternalInventory<InternalInventory> toolkit;
    private final PatternEncodingLogic patternEncodingLogic = new PatternEncodingLogic(this);

    public MESTMenuHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
        this.patternCache = new SupplierInternalInventory<>(
                new StackDependentSupplier<>(
                        this::getItemStack,
                        stack -> createInv(player, stack, ModComponents.PATTERN_CACHE_INV.get(), patternCacheSize())));
        this.trash = new SupplierInternalInventory<>(
                new StackDependentSupplier<>(
                        this::getItemStack,
                        stack -> createInv(player, stack, ModComponents.TRASH_INV.get(), MestConfig.trashSlots())));
        this.toolkit = new SupplierInternalInventory<>(
                new StackDependentSupplier<>(
                        this::getItemStack,
                        stack -> createInv(player, stack, ModComponents.TOOLKIT_INV.get(), MestConfig.toolkitSlots())));
        this.patternEncodingLogic.readFromNBT(
                getItemStack().getOrDefault(AE2wtlibComponents.PATTERN_ENCODING_LOGIC, new CompoundTag()),
                player.registryAccess());
    }

    public InternalInventory getPatternCacheInventory() {
        return patternCache;
    }

    public InternalInventory getTrashInventory() {
        return trash;
    }

    public InternalInventory getToolkitInventory() {
        return toolkit;
    }

    public void clearTrash() {
        InternalInventory inventory = trash;
        for (int i = 0; i < inventory.size(); i++) {
            inventory.setItemDirect(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    @Override
    public InternalInventory getSubInventory(net.minecraft.resources.ResourceLocation id) {
        if (INV_TRASH.equals(id)) {
            return trash;
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
