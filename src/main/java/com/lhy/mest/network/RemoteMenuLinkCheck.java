package com.lhy.mest.network;

import java.util.function.BooleanSupplier;

/** A per-menu link lease. Once lost, opening a new menu is required to regain remote access. */
final class RemoteMenuLinkCheck {
    private static final int RECHECK_INTERVAL_TICKS = 20;

    private boolean checked;
    private boolean linked;
    private long lastCheckTick;

    boolean isLinked(long tick, BooleanSupplier check) {
        if (checked && !linked) {
            return false;
        }
        // A dimension's clock can be behind the previous one; never extend the lease on rollback.
        if (!checked || tick < lastCheckTick || tick - lastCheckTick >= RECHECK_INTERVAL_TICKS) {
            linked = check.getAsBoolean();
            lastCheckTick = tick;
            checked = true;
        }
        return linked;
    }
}
