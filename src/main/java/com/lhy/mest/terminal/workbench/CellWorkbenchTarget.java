package com.lhy.mest.terminal.workbench;

import net.minecraft.world.item.ItemStack;

/** A storage cell the workbench edits in place somewhere other than its own slot. Server side only. */
public interface CellWorkbenchTarget {
    /** The cell as it currently sits; may lag behind unsaved storage changes. Never mutate it. */
    ItemStack stack();

    /** The cell after flushing pending storage writes, safe to copy, edit and {@link #write}. */
    ItemStack freshStack();

    void write(ItemStack stack);

    /** False once the cell is gone, swapped or out of reach; the workbench then falls back to its own slot. */
    boolean valid();
}
