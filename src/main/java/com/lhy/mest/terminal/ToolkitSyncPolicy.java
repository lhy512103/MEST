package com.lhy.mest.terminal;

/**
 * Merge rule between the live toolkit cells and the terminal's {@code TOOLKIT_INV} snapshot.
 *
 * <p>The cells are what vanilla mutates in place when the extra bar is the hand: an armor swap
 * calls {@code ItemStack#copyAndClear()} on the hand stack and only then writes the displaced item
 * back. The component snapshot still describes the <i>pre-swap</i> cells during that window, so
 * adopting it would put a second copy of the just-equipped item back into the bar. A cell the
 * toolkit itself wrote therefore wins until the snapshot catches up.
 */
public final class ToolkitSyncPolicy {
    private ToolkitSyncPolicy() {
    }

    /** Whether the component snapshot must overwrite the live cells. */
    public static boolean shouldAdoptFromComponent(boolean localEditPending, boolean componentMatchesItems) {
        return !localEditPending && !componentMatchesItems;
    }

    /** Whether a pending local edit has been confirmed by the snapshot and can be settled. */
    public static boolean isLocalEditSettled(boolean localEditPending, boolean componentMatchesItems) {
        return localEditPending && componentMatchesItems;
    }
}
