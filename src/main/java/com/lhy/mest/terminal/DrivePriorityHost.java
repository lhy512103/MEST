package com.lhy.mest.terminal;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGridNode;
import appeng.api.storage.ISubMenuHost;
import appeng.helpers.IPriorityHost;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.menu.ISubMenu;

/** Priority host for a drive on the terminal's grid; ticking and the Back button stay with the terminal. */
public final class DrivePriorityHost extends ItemMenuHost<Item> implements IPriorityHost {
    private final MESTMenuHost terminal;
    private final GlobalPos pos;

    public DrivePriorityHost(MESTMenuHost terminal, GlobalPos pos) {
        super(terminal.getItemStack().getItem(), terminal.getPlayer(), terminal.getLocator());
        this.terminal = terminal;
        this.pos = pos;
    }

    @Nullable
    private IPriorityHost drive() {
        if (!(getPlayer() instanceof ServerPlayer player)) {
            return null;
        }
        var level = player.server.getLevel(pos.dimension());
        if (level == null || !level.hasChunkAt(pos.pos())) {
            return null;
        }
        var block = level.getBlockEntity(pos.pos());
        if (!(block instanceof IPriorityHost priority) || !(block instanceof IChestOrDrive)
                || !(block instanceof IGridConnectedBlockEntity connected) || block.isRemoved()) {
            return null;
        }
        IGridNode node = connected.getGridNode();
        IGridNode access = terminal.getActionableNode();
        if (node == null || access == null || !node.isActive() || node.getGrid() != access.getGrid()) {
            return null;
        }
        return priority;
    }

    @Override
    public int getPriority() {
        var drive = drive();
        return drive == null ? 0 : drive.getPriority();
    }

    @Override
    public void setPriority(int newValue) {
        var drive = drive();
        if (drive != null) {
            drive.setPriority(newValue);
        }
    }

    @Override
    public boolean isValid() {
        return super.isValid() && (isClientSide() || drive() != null);
    }

    @Override
    public void tick() {
        terminal.tick();
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        ((ISubMenuHost) terminal).returnToMainMenu(player, subMenu);
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return getItemStack();
    }
}
