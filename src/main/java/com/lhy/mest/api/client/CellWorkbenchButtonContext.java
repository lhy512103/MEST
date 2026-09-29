package com.lhy.mest.api.client;

import net.minecraft.world.item.ItemStack;

/** What an add-on side bar button of the MEST cell workbench can see and do. */
public interface CellWorkbenchButtonContext {
    /** A copy of the edited cell as the client sees it; empty without a cell. */
    ItemStack cell();

    /** True while the edited cell sits inside an ME drive rather than the workbench slot. */
    boolean isRemote();

    /** True while the current press comes from a right click. */
    boolean isRightClick();

    /** Runs the server action registered under {@code actionId} on the edited cell. */
    void sendAction(String actionId, boolean alternate);
}
