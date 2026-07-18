package com.lhy.mest.network;

/** A fixed quota that resets only when the authoritative server game tick changes. */
final class PerGameTickBudget {
    private final int limit;
    private long gameTick = Long.MIN_VALUE;
    private int used;

    PerGameTickBudget(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    boolean tryAcquire(long currentGameTick) {
        if (gameTick != currentGameTick) {
            gameTick = currentGameTick;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }
}
