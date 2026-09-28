package com.lhy.mest.terminal;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;

/** Locates a remote drive's priority through the terminal item, so the terminal's range and power still apply. */
public record DrivePriorityLocator(ItemMenuHostLocator terminal, GlobalPos drive) implements MenuHostLocator {
    @Nullable
    @Override
    public <T> T locate(Player player, Class<T> hostInterface) {
        MESTMenuHost host = terminal.locate(player, MESTMenuHost.class);
        if (host == null) {
            return null;
        }
        var priority = new DrivePriorityHost(host, drive);
        if (!player.level().isClientSide() && !priority.isValid()) {
            return null;
        }
        return hostInterface.isInstance(priority) ? hostInterface.cast(priority) : null;
    }

    public void writeToPacket(FriendlyByteBuf buf) {
        MenuLocators.writeToPacket(buf, terminal);
        buf.writeGlobalPos(drive);
    }

    public static DrivePriorityLocator readFromPacket(FriendlyByteBuf buf) {
        if (!(MenuLocators.readFromPacket(buf) instanceof ItemMenuHostLocator terminal)) {
            throw new IllegalArgumentException("Drive priority locator requires an item terminal");
        }
        return new DrivePriorityLocator(terminal, buf.readGlobalPos());
    }
}
