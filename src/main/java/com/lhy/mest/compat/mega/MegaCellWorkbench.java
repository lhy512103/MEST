package com.lhy.mest.compat.mega;

import appeng.api.storage.StorageCells;

import gripe._90.megacells.item.cell.BulkCellInventory;

import com.lhy.mest.api.RegisterCellWorkbenchActionsEvent;

/**
 * MEGA Cells adds its bulk-cell compression cutoff to AE2's workbench through mixins, which do not
 * reach MEST's workbench. This re-adds it on top of MEGA's public cell API. Only loaded with MEGA.
 */
public final class MegaCellWorkbench {
    public static final String COMPRESSION_CUTOFF = "megacells:compression_cutoff";

    private MegaCellWorkbench() {
    }

    public static void registerActions(RegisterCellWorkbenchActionsEvent event) {
        event.register(COMPRESSION_CUTOFF, (player, cell, backwards) -> {
            if (StorageCells.getCellInventory(cell, null) instanceof BulkCellInventory bulk) {
                bulk.switchCompressionCutoff(backwards);
            }
        });
    }
}
