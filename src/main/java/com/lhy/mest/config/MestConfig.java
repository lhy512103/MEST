package com.lhy.mest.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config. Pattern-cache slot count is applied the next time the terminal is opened. */
public final class MestConfig {
    public static final int PATTERN_CACHE_MIN = 18;
    public static final int PATTERN_CACHE_MAX = 9 * 64;
    public static final int PATTERN_CACHE_STEP = 9;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.IntValue PATTERN_CACHE_SLOTS = BUILDER
            .comment("Encoded-pattern cache slots. Minimum 18, then in steps of 9 (one extra row).")
            .translation("gui.mesplicedterminal.config.pattern_cache_slots")
            .defineInRange("patternCacheSlots", 36, PATTERN_CACHE_MIN, PATTERN_CACHE_MAX);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private MestConfig() {}

    public static int patternCacheSlots() {
        int value = SPEC.isLoaded() ? PATTERN_CACHE_SLOTS.get() : 36;
        int rows = Math.max(PATTERN_CACHE_MIN / PATTERN_CACHE_STEP, value / PATTERN_CACHE_STEP);
        return Math.min(PATTERN_CACHE_MAX, rows * PATTERN_CACHE_STEP);
    }
}
