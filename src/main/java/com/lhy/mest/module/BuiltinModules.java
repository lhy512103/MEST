package com.lhy.mest.module;

import java.util.Set;

/** Ids of the modules MEST ships; add-ons may not reuse them. */
public final class BuiltinModules {
    public static final Set<String> IDS = Set.of(
            "me_list", "crafting", "crafting_terminal", "pattern_encoding", "pattern_access",
            "pattern_cache", "inventory", "wireless_settings", "trash", "toolkit", "network_toolkit",
            "hotkeys", "provider_select");

    private BuiltinModules() {
    }
}
