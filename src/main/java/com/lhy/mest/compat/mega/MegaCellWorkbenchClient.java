package com.lhy.mest.compat.mega;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.item.ItemStack;

import appeng.api.storage.StorageCells;

import gripe._90.megacells.client.screen.CompressionCutoffButton;
import gripe._90.megacells.item.cell.BulkCellInventory;

import com.lhy.mest.api.client.CellWorkbenchButton;
import com.lhy.mest.api.client.RegisterCellWorkbenchButtonsEvent;

/** Client half of {@link MegaCellWorkbench}: MEGA's own cutoff button. Only loaded with MEGA. */
public final class MegaCellWorkbenchClient {
    private MegaCellWorkbenchClient() {
    }

    public static void registerButtons(RegisterCellWorkbenchButtonsEvent event) {
        event.register(MegaCellWorkbench.COMPRESSION_CUTOFF, context -> {
            var button = new CompressionCutoffButton(pressed -> context.sendAction(
                    MegaCellWorkbench.COMPRESSION_CUTOFF, context.isRightClick()));
            return new CellWorkbenchButton() {
                @Override
                public AbstractWidget widget() {
                    return button;
                }

                @Override
                public void update(ItemStack cell) {
                    if (StorageCells.getCellInventory(cell, null) instanceof BulkCellInventory bulk
                            && bulk.hasCompressionChain()) {
                        button.setVisibility(bulk.isCompressionEnabled());
                        button.setItem(bulk.getCutoffItem());
                    } else {
                        button.setVisibility(false);
                    }
                }
            };
        });
    }
}
