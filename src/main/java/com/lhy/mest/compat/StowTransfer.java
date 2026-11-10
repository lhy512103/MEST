package com.lhy.mest.compat;

import java.util.function.IntConsumer;
import java.util.function.LongUnaryOperator;

/** Keeps held-stack accounting separate from terminal lookup and hotkey dispatch. */
final class StowTransfer {
    private StowTransfer() {}

    static boolean insert(int count, LongUnaryOperator insert, IntConsumer setRemaining) {
        if (count <= 0) {
            return false;
        }
        long inserted = insert.applyAsLong(count);
        if (inserted <= 0) {
            return false;
        }
        setRemaining.accept(count - (int) Math.min(inserted, count));
        return true;
    }
}
