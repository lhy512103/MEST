package com.lhy.mest.terminal;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;

import appeng.menu.locator.ItemMenuHostLocator;

/**
 * Resolves an AE2 item menu against a toolkit cell instead of {@code Inventory.items[selected]}.
 *
 * <p>WCWT's {@code getSelected} override made {@code MenuLocators.forHand} search the player
 * inventory by identity and either throw or silently open the vanilla hotbar item. Routing those
 * two factory methods here keeps wrenches, network tools and similar IMenuItems on the toolkit
 * stack.
 */
public record ToolkitItemLocator(int toolkitIndex, @Nullable BlockHitResult hit)
        implements ItemMenuHostLocator {
    public ToolkitItemLocator(int toolkitIndex) {
        this(toolkitIndex, null);
    }

    @Nullable
    public static ItemMenuHostLocator forHand(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !ToolkitBarState.isToolkitSelected(player)) {
            return null;
        }
        int index = ToolkitBarState.toolkitIndex(player);
        if (!ToolkitBarState.isValidToolkitIndex(index)
                || ToolkitBarState.stackAt(player, index).isEmpty()) {
            return null;
        }
        return new ToolkitItemLocator(index);
    }

    @Nullable
    public static ItemMenuHostLocator forUse(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return null;
        }
        ItemMenuHostLocator locator = forHand(player, context.getHand());
        if (!(locator instanceof ToolkitItemLocator toolkit)) {
            return null;
        }
        return new ToolkitItemLocator(toolkit.toolkitIndex(), hitResult(context));
    }

    @Override
    public ItemStack locateItem(Player player) {
        return ToolkitBarState.stackAt(player, toolkitIndex);
    }

    @Override
    public @Nullable BlockHitResult hitResult() {
        return hit;
    }

    public void writeToPacket(FriendlyByteBuf buf) {
        buf.writeVarInt(toolkitIndex);
        buf.writeBoolean(hit != null);
        if (hit != null) {
            buf.writeBlockHitResult(hit);
        }
    }

    public static ToolkitItemLocator readFromPacket(FriendlyByteBuf buf) {
        int index = buf.readVarInt();
        BlockHitResult hit = buf.readBoolean() ? buf.readBlockHitResult() : null;
        return new ToolkitItemLocator(index, hit);
    }

    private static BlockHitResult hitResult(UseOnContext context) {
        return new BlockHitResult(
                context.getClickLocation(),
                context.getClickedFace(),
                context.getClickedPos(),
                context.isInside());
    }
}
