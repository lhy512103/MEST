package com.lhy.mest.client.dock.model;

/** Runtime interaction permissions for one registered terminal module. */
public record ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable) {
    public static ModuleLayoutPolicy defaults() {
        return new ModuleLayoutPolicy(true, true, true);
    }

    public static ModuleLayoutPolicy fixed() {
        return new ModuleLayoutPolicy(true, false, false);
    }
}