package com.lhy.mest.terminal;

/** Hard per-tick operation quotas; the caller additionally enforces a soft wall-clock deadline. */
final class DriveSearchBudget {
    private int cells;
    private int keys;

    DriveSearchBudget(int cells, int keys) {
        if (cells < 1 || keys < 1) throw new IllegalArgumentException("positive quotas required");
        this.cells = cells;
        this.keys = keys;
    }

    boolean cell() {
        if (cells == 0) return false;
        cells--;
        return true;
    }

    boolean key() {
        if (keys == 0) return false;
        keys--;
        return true;
    }
}
