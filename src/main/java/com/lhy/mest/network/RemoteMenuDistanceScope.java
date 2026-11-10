package com.lhy.mest.network;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Narrow, synchronous distance exemption. Never changes the real player's position. */
public final class RemoteMenuDistanceScope {
    private static final ThreadLocal<Binding> ACTIVE = new ThreadLocal<>();

    private RemoteMenuDistanceScope() {
    }

    public static boolean matches(Object player, Object level, Object menu, long pos) {
        Binding binding = ACTIVE.get();
        return binding != null && binding.player == player && binding.level == level
                && binding.menu == menu && binding.pos == pos;
    }

    static boolean evaluate(Object player, Object level, Object menu, long pos, BooleanSupplier original) {
        Binding previous = ACTIVE.get();
        ACTIVE.set(new Binding(Objects.requireNonNull(player), Objects.requireNonNull(level),
                Objects.requireNonNull(menu), pos));
        try {
            return original.getAsBoolean();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    private record Binding(Object player, Object level, Object menu, long pos) {
    }
}
