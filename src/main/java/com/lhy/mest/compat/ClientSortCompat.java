package com.lhy.mest.compat;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import dev.terminalmc.clientsort.client.gui.widget.TriggerButton;

/**
 * ClientSort places its buttons at {@code leftPos + imageWidth + offset} (and
 * {@code topPos + slot.y}). MEST slots are already in screen space, so the
 * screen keeps {@code leftPos/topPos = 0} and {@code imageWidth} equal to the
 * player-inventory's right edge; this pass re-applies that after the inventory
 * panel moves.
 */
public final class ClientSortCompat {
    private ClientSortCompat() {
    }

    public static void syncButtons(AbstractContainerScreen<?> screen) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        int imageWidth = screen.getXSize();
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof TriggerButton button)) {
                continue;
            }
            int x;
            if (button.offsetFromSlot) {
                x = left + button.referenceSlot.x;
                x = button.referenceLeft ? x - TriggerButton.WIDTH : x + 16;
            } else {
                x = left + (button.referenceLeft ? 0 : imageWidth);
            }
            button.setX(x + button.offset.x());
            button.setY(top + button.referenceSlot.y + button.offset.y());
        }
    }
}
