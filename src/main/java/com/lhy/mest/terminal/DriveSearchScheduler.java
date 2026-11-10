package com.lhy.mest.terminal;

/** Pure, menu-local latest-value mailbox and authoritative-tick scan gate. */
final class DriveSearchScheduler {
    private final long cooldown;
    private int requestedStart = -1;
    private boolean pending;
    private long lastTick = Long.MIN_VALUE;
    private long lastStart = Long.MIN_VALUE;
    private long generation;

    DriveSearchScheduler(long cooldown) {
        if (cooldown < 1) throw new IllegalArgumentException("cooldown");
        this.cooldown = cooldown;
    }

    boolean request(int start) {
        boolean changed = requestedStart != start;
        requestedStart = start;
        pending = true;
        if (changed) generation++;
        return changed;
    }

    void invalidate() {
        generation++;
        if (requestedStart >= 0) pending = true;
    }

    boolean tick(long tick) {
        if (lastTick == tick) return false;
        lastTick = tick;
        return true;
    }

    boolean start(long tick) {
        if (!pending || requestedStart < 0
                || (lastStart != Long.MIN_VALUE && tick >= lastStart && tick - lastStart < cooldown)) return false;
        lastStart = tick;
        pending = false;
        return true;
    }

    int requestedStart() { return requestedStart; }
    long generation() { return generation; }

    void clear() {
        requestedStart = -1;
        pending = false;
        generation++;
        // Do not reset the tick/cooldown guard: close/reopen packets cannot multiply work in one tick.
    }
}
