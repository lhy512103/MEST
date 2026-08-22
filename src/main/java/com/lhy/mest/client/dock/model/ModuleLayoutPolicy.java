package com.lhy.mest.client.dock.model;

/** Runtime interaction permissions for one registered terminal module. */
public record ModuleLayoutPolicy(
        boolean visible, boolean movable, boolean resizable, boolean floating, boolean pinned) {
    public ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable) {
        this(visible, movable, resizable, false, false);
    }

    public ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable, boolean floating) {
        this(visible, movable, resizable, floating, false);
    }

    public static ModuleLayoutPolicy defaults() {
        return new ModuleLayoutPolicy(true, true, true, false, false);
    }

    public static ModuleLayoutPolicy fixed() {
        return new ModuleLayoutPolicy(true, false, false, false, false);
    }

    public ModuleLayoutPolicy withVisible(boolean visible) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, pinned);
    }

    public ModuleLayoutPolicy withFloating(boolean floating) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, floating && pinned);
    }

    public ModuleLayoutPolicy withPinned(boolean pinned) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, floating && pinned);
    }
}