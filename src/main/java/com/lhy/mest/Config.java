package com.lhy.mest;

import net.neoforged.neoforge.common.ModConfigSpec;

// Common config for the mod. Currently minimal; options will be added as features land.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
