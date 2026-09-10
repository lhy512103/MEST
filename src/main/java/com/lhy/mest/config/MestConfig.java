package com.lhy.mest.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config. Pattern-cache slot count is applied the next time the terminal is opened. */
public final class MestConfig {
    public static final int PATTERN_CACHE_MIN = 18;
    public static final int PATTERN_CACHE_MAX = 9 * 64;
    public static final int PATTERN_CACHE_STEP = 9;
    public static final int TRASH_MIN = 27;
    public static final int TRASH_MAX = 9 * 64;
    public static final int TRASH_STEP = 9;
    public static final int TOOLKIT_MIN = 18;
    public static final int TOOLKIT_MAX = 9 * 64;
    public static final int TOOLKIT_STEP = 9;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.IntValue PATTERN_CACHE_SLOTS = BUILDER
            .comment("样板缓存槽位数。最少 18，之后按 9 递增（一行 9 格）。下次打开终端生效。")
            .translation("gui.mesplicedterminal.config.pattern_cache_slots")
            .defineInRange("patternCacheSlots", 36, PATTERN_CACHE_MIN, PATTERN_CACHE_MAX);
    private static final ModConfigSpec.IntValue TRASH_SLOTS = BUILDER
            .comment("垃圾桶槽位数。最少 27（3×9），按 9 递增。下次打开终端生效。")
            .translation("gui.mesplicedterminal.config.trash_slots")
            .defineInRange("trashSlots", TRASH_MAX, TRASH_MIN, TRASH_MAX);
    private static final ModConfigSpec.IntValue TOOLKIT_SLOTS = BUILDER
            .comment("工具包槽位数。最少 18（2×9），按 9 递增。只存放不可堆叠物品。下次打开终端生效。")
            .translation("gui.mesplicedterminal.config.toolkit_slots")
            .defineInRange("toolkitSlots", 27, TOOLKIT_MIN, TOOLKIT_MAX);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private MestConfig() {}

    public static int patternCacheSlots() {
        int value = SPEC.isLoaded() ? PATTERN_CACHE_SLOTS.get() : 36;
        int rows = Math.max(PATTERN_CACHE_MIN / PATTERN_CACHE_STEP, value / PATTERN_CACHE_STEP);
        return Math.min(PATTERN_CACHE_MAX, rows * PATTERN_CACHE_STEP);
    }

    public static int trashSlots() {
        int value = SPEC.isLoaded() ? TRASH_SLOTS.get() : TRASH_MAX;
        int rows = Math.max(TRASH_MIN / TRASH_STEP, value / TRASH_STEP);
        return Math.min(TRASH_MAX, rows * TRASH_STEP);
    }

    public static int toolkitSlots() {
        int value = SPEC.isLoaded() ? TOOLKIT_SLOTS.get() : 27;
        int rows = Math.max(TOOLKIT_MIN / TOOLKIT_STEP, value / TOOLKIT_STEP);
        return Math.min(TOOLKIT_MAX, rows * TOOLKIT_STEP);
    }
}
