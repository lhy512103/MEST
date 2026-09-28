package com.lhy.mest.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player quota for client packets that do work on the server thread.
 *
 * <p>Entries are dropped wholesale whenever the authoritative game tick advances, so the map can
 * never hold more than the players that acted during the current tick and needs no separate
 * eviction. One player exhausting their quota never consumes another player's.
 */
final class PlayerTickBudgets {
    private final Map<UUID, PerGameTickBudget> budgets = new HashMap<>();
    private final int limit;
    private long currentTick = Long.MIN_VALUE;

    PlayerTickBudgets(int limit) {
        this.limit = limit;
    }

    boolean tryAcquire(ServerPlayer player) {
        long tick = player.serverLevel().getGameTime();
        if (tick != currentTick) {
            currentTick = tick;
            budgets.clear();
        }
        return budgets.computeIfAbsent(player.getUUID(), id -> new PerGameTickBudget(limit)).tryAcquire(tick);
    }
}
