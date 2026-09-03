package com.lhy.mest.compat;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Maps;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.PlayerSource;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.me.crafting.CraftAmountMenu;

import de.mari_023.ae2wtlib.AE2wtlibConfig;
import de.mari_023.ae2wtlib.api.AE2wtlibComponents;
import de.mari_023.ae2wtlib.api.AE2wtlibTags;
import de.mari_023.ae2wtlib.networking.UpdateRestockPacket;
import de.mari_023.ae2wtlib.api.registration.WTDefinition;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.api.terminal.WUTHandler;
import de.mari_023.ae2wtlib.wct.CraftingTerminalHandler;
import de.mari_023.ae2wtlib.wct.magnet_card.MagnetHandler;
import de.mari_023.ae2wtlib.wct.magnet_card.MagnetMode;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.network.MestRestockAmountPacket;
import com.lhy.mest.terminal.MestMagnetHost;
import com.lhy.mest.terminal.MestTerminal;

/**
 * wtlib pick/restock/magnet run against {@code WTDefinitions.CRAFTING}. MEST is registered as
 * {@code spliced}, so those mixins miss it. This bridge uses the same public APIs against the
 * spliced terminal item.
 */
@EventBusSubscriber(modid = MESplicedterminal.MODID)
public final class MestWtlibSupport {
    private static final ThreadLocal<ItemStack> USE_ORIGINAL = new ThreadLocal<>();

    private MestWtlibSupport() {
    }

    @Nullable
    public static ItemMenuHostLocator findMest(Player player) {
        if (!WTDefinition.exists(MestTerminal.TERMINAL_NAME)) {
            return null;
        }
        return WUTHandler.findTerminal(player, WTDefinition.of(MestTerminal.TERMINAL_NAME));
    }

    public static ItemStack mestStack(Player player) {
        ItemMenuHostLocator locator = findMest(player);
        return locator == null ? ItemStack.EMPTY : locator.locateItem(player);
    }

    public static void pickBlock(ServerPlayer player, ItemStack requested) {
        if (requested.isEmpty() || craftingTerminalOwns(player, "pick")) {
            return;
        }
        ItemStack mest = mestStack(player);
        if (mest.isEmpty() || !mest.getOrDefault(AE2wtlibComponents.PICK_BLOCK, false)) {
            return;
        }
        MEStorage storage = mestStorage(player);
        ItemMenuHostLocator locator = findMest(player);
        if (storage == null || locator == null) {
            return;
        }
        PlayerSource source = new PlayerSource(player);
        Inventory inventory = player.getInventory();
        int targetSlot = inventory.getSuitableHotbarSlot();
        ItemStack toReplace = inventory.getItem(targetSlot);
        AEItemKey replaceKey = AEItemKey.of(toReplace);
        if (!toReplace.isEmpty() && replaceKey != null) {
            long insert = storage.insert(replaceKey, toReplace.getCount(), Actionable.SIMULATE, source);
            if (insert < toReplace.getCount()) {
                return;
            }
        }
        AEItemKey what = AEItemKey.of(requested);
        if (what == null) {
            return;
        }
        int targetAmount = requested.getMaxStackSize();
        long extracted = storage.extract(what, targetAmount, Actionable.SIMULATE, source);
        if (extracted == 0L) {
            if (!mest.getOrDefault(AE2wtlibComponents.CRAFT_IF_MISSING, false)
                    || locator.locate(player, WTMenuHost.class) == null) {
                return;
            }
            var host = locator.locate(player, WTMenuHost.class);
            if (host.getActionableNode() == null || host.getActionableNode().getGrid() == null
                    || host.getActionableNode().getGrid().getCraftingService().getCraftingFor(what).isEmpty()) {
                return;
            }
            CraftAmountMenu.open(player, locator, what, 1);
            return;
        }
        if (!toReplace.isEmpty() && replaceKey != null) {
            long insert = storage.insert(replaceKey, toReplace.getCount(), Actionable.MODULATE, source);
            if (insert < toReplace.getCount()) {
                toReplace.setCount(toReplace.getCount() - (int) insert);
                inventory.setItem(targetSlot, toReplace);
                return;
            }
        }
        extracted = storage.extract(what, targetAmount, Actionable.MODULATE, source);
        if (extracted == 0L) {
            inventory.setItem(targetSlot, ItemStack.EMPTY);
            return;
        }
        ItemStack picked = requested.copy();
        picked.setCount((int) extracted);
        inventory.setItem(targetSlot, picked);
        inventory.selected = targetSlot;
        player.connection.send(new ClientboundSetCarriedItemPacket(inventory.selected));
    }

    /** The spliced terminal the player is carrying, once its wireless link is confirmed live. */
    @Nullable
    private static WTMenuHost linkedHost(Player player) {
        ItemMenuHostLocator locator = findMest(player);
        if (locator == null) {
            return null;
        }
        WTMenuHost host = locator.locate(player, WTMenuHost.class);
        if (host == null) {
            return null;
        }
        host.updateConnectedAccessPoint();
        host.updateLinkStatus();
        return host.getLinkStatus().connected() ? host : null;
    }

    /**
     * True while the player still carries a spliced terminal with a live grid connection. Used to
     * keep remotely opened machine menus from outliving the terminal that opened them.
     */
    public static boolean hasLinkedTerminal(Player player) {
        WTMenuHost host = linkedHost(player);
        if (host == null) {
            return false;
        }
        IGridNode node = host.getActionableNode();
        return node != null && node.isActive() && node.getGrid() != null;
    }

    @Nullable
    private static MEStorage mestStorage(Player player) {
        WTMenuHost host = linkedHost(player);
        if (host == null) {
            return null;
        }
        IGridNode node = host.getActionableNode();
        if (node == null || node.getGrid() == null || node.getGrid().getStorageService() == null) {
            return null;
        }
        return node.getGrid().getStorageService().getInventory();
    }

    private static boolean craftingTerminalOwns(Player player, String component) {
        ItemStack wct = CraftingTerminalHandler.getCraftingTerminalHandler(player).getCraftingTerminal();
        if (wct.isEmpty()) {
            return false;
        }
        if ("magnet".equals(component)) {
            MagnetMode mode = MagnetHandler.getMagnetMode(wct);
            return mode.magnet() || mode.pickupToME();
        }
        if ("restock".equals(component)) {
            return wct.getOrDefault(AE2wtlibComponents.RESTOCK, false);
        }
        return wct.getOrDefault(AE2wtlibComponents.PICK_BLOCK, false);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (craftingTerminalOwns(player, "magnet")) {
            return;
        }
        ItemStack mest = mestStack(player);
        if (mest.isEmpty()) {
            return;
        }
        sendRestockAble(player, mest);
        MagnetMode mode = MagnetHandler.getMagnetMode(mest);
        if (!mode.magnet() || player.isShiftKeyDown()) {
            return;
        }
        MestMagnetHost filter = new MestMagnetHost(player, mest);
        double range = AE2wtlibConfig.CONFIG.magnetCardRange();
        List<ItemEntity> items = player.level().getEntitiesOfClass(
                ItemEntity.class, player.getBoundingBox().inflate(range), EntitySelector.ENTITY_STILL_ALIVE);
        for (ItemEntity entity : items) {
            if (entity.getPersistentData().contains("PreventRemoteMovement")) {
                continue;
            }
            AEItemKey key = AEItemKey.of(entity.getItem());
            if (key == null || !filter.matchesPickup(key)) {
                continue;
            }
            entity.playerTouch(player);
        }
    }

    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide() || craftingTerminalOwns(player, "magnet")) {
            return;
        }
        ItemStack mest = mestStack(player);
        if (mest.isEmpty() || !MagnetHandler.getMagnetMode(mest).pickupToME()) {
            return;
        }
        ItemEntity entity = event.getItemEntity();
        if (player.isShiftKeyDown() || entity.hasPickUpDelay() || entity.getOwner() == player) {
            return;
        }
        MEStorage storage = mestStorage(player);
        if (storage == null) {
            return;
        }
        ItemStack stack = entity.getItem();
        AEItemKey key = AEItemKey.of(stack);
        if (key == null || !new MestMagnetHost(player, mest).matchesInsert(key)) {
            return;
        }
        long inserted = storage.insert(key, stack.getCount(), Actionable.MODULATE, new PlayerSource(player));
        if (inserted <= 0L) {
            return;
        }
        int leftover = (int) (stack.getCount() - inserted);
        stack.setCount(leftover);
        event.setCanPickup(TriState.FALSE);
        if (leftover <= 0) {
            entity.discard();
        }
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (craftingTerminalOwns(player, "restock")) {
            return;
        }
        ItemStack mest = mestStack(player);
        if (mest.isEmpty() || !mest.getOrDefault(AE2wtlibComponents.RESTOCK, false)) {
            return;
        }
        int slot = player.getInventory().selected;
        ItemStack remaining = player.getInventory().getItem(slot);
        ItemStack type = remaining.isEmpty() ? event.getEntity().getItem() : remaining;
        ItemStack now = remaining.isEmpty() ? ItemStack.EMPTY : remaining;
        restock(player, type, now, filled -> player.getInventory().setItem(slot, filled), slot);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void beforeUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        USE_ORIGINAL.set(event.getItemStack().copy());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            USE_ORIGINAL.remove();
            return;
        }
        ItemStack original = USE_ORIGINAL.get();
        USE_ORIGINAL.remove();
        if (original == null) {
            return;
        }
        InteractionHand hand = event.getHand();
        int slot = hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : player.getInventory().selected;
        restock(player, original, player.getItemInHand(hand), stack -> player.setItemInHand(hand, stack), slot);
    }

    /** Same rules as {@code AE2wtlibEvents.restock}: refill {@code now} from ME up to {@code original}'s max. */
    public static void restock(
            ServerPlayer player, ItemStack original, ItemStack now, Consumer<ItemStack> set, int slot) {
        if (player.isCreative() || original.isEmpty() || original.is(AE2wtlibTags.NO_RESTOCK)) {
            return;
        }
        if (craftingTerminalOwns(player, "restock")) {
            return;
        }
        ItemStack mest = mestStack(player);
        if (mest.isEmpty() || !mest.getOrDefault(AE2wtlibComponents.RESTOCK, false)) {
            return;
        }
        MEStorage storage = mestStorage(player);
        if (storage == null) {
            return;
        }
        int count = now.getCount();
        int toAdd = original.getMaxStackSize() - count;
        if (toAdd == 0) {
            return;
        }
        if (!now.isEmpty() && !ItemStack.isSameItemSameComponents(original, now)) {
            return;
        }
        AEItemKey key = AEItemKey.of(original);
        if (key == null) {
            return;
        }
        PlayerSource source = new PlayerSource(player);
        long changed = toAdd > 0
                ? storage.extract(key, toAdd, Actionable.MODULATE, source)
                : -storage.insert(key, -toAdd, Actionable.MODULATE, source);
        ItemStack filled = now.isEmpty() ? original.copy() : now.copy();
        filled.setCount(count + (int) changed);
        set.accept(filled);
        PacketDistributor.sendToPlayer(player, new UpdateRestockPacket(slot, filled));
    }

    private static void sendRestockAble(ServerPlayer player, ItemStack terminal) {
        if (player.isCreative() || !terminal.getOrDefault(AE2wtlibComponents.RESTOCK, false)) {
            return;
        }
        ItemMenuHostLocator locator = findMest(player);
        if (locator == null) {
            return;
        }
        WTMenuHost host = locator.locate(player, WTMenuHost.class);
        if (host == null || host.getActionableNode() == null || host.getActionableNode().getGrid() == null) {
            return;
        }
        KeyCounter list = host.getActionableNode().getGrid().getStorageService().getCachedInventory();
        if (list == null) {
            return;
        }
        HashMap<Item, Long> items = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || items.containsKey(stack.getItem()) || stack.is(AE2wtlibTags.NO_RESTOCK)) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            items.put(stack.getItem(), key == null ? 0L : list.get(key));
        }
        HashMap<Holder<Item>, Long> map = Maps.newHashMapWithExpectedSize(items.size());
        items.forEach((item, count) -> map.put(BuiltInRegistries.ITEM.wrapAsHolder(item), count));
        PacketDistributor.sendToPlayer(player, new MestRestockAmountPacket(map));
    }
}
