package com.lhy.mest.client.dock.model;

/**
 * How a module first appears in a layout that has never seen it. A pinned module is always
 * floating.
 */
public record ModuleDefaults(boolean visible, boolean floating, boolean pinned, boolean showTerminalButton) {
    public static final ModuleDefaults STANDARD = new ModuleDefaults(true, false, false, true);

    public ModuleDefaults {
        floating |= pinned;
    }

    public ModuleLayoutPolicy policy() {
        return new ModuleLayoutPolicy(visible, true, true, floating, pinned, showTerminalButton);
    }
}
