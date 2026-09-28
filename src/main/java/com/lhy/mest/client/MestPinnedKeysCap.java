package com.lhy.mest.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.PinnedKeys;

/**
 * Replaces AE2's hardcoded {@link PinnedKeys#MAX_PINNED} of 9 with the ME list's current column
 * count while the spliced terminal is open.
 */
public final class MestPinnedKeysCap {
    private static int visibleColumns = PinnedKeys.MAX_PINNED;

    private MestPinnedKeysCap() {
    }

    public static int limit() {
        return Math.max(1, visibleColumns);
    }

    public static void setVisibleColumns(int cols) {
        visibleColumns = Math.max(1, cols);
        evictToLimit();
    }

    public static void resetToVanilla() {
        visibleColumns = PinnedKeys.MAX_PINNED;
        evictToLimit();
    }

    static void evictToLimit() {
        int cap = limit();
        var keys = PinnedKeys.getPinnedKeys();
        if (keys.size() <= cap) {
            return;
        }
        List<AEKey> ranked = new ArrayList<>(keys);
        ranked.sort(Comparator.comparing(key -> {
            PinnedKeys.PinInfo info = PinnedKeys.getPinInfo(key);
            return info != null ? info.since : Instant.MAX;
        }));
        int overflow = ranked.size() - cap;
        for (int i = 0; i < overflow; i++) {
            PinnedKeys.unpin(ranked.get(i));
        }
    }
}
