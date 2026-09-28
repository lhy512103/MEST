package com.lhy.mest.client.dock.model;

/** Runtime interaction permissions for one registered terminal module. */
public record ModuleLayoutPolicy(
        boolean visible,
        boolean movable,
        boolean resizable,
        boolean floating,
        boolean pinned,
        boolean showTerminalButton) {
    public ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable) {
        this(visible, movable, resizable, false, false, true);
    }

    public ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable, boolean floating) {
        this(visible, movable, resizable, floating, false, true);
    }

    public ModuleLayoutPolicy(boolean visible, boolean movable, boolean resizable, boolean floating, boolean pinned) {
        this(visible, movable, resizable, floating, pinned, true);
    }

    public static ModuleLayoutPolicy defaults() {
        return new ModuleLayoutPolicy(true, true, true, false, false, true);
    }

    public static ModuleLayoutPolicy fixed() {
        return new ModuleLayoutPolicy(true, false, false, false, false, true);
    }

    public ModuleLayoutPolicy withVisible(boolean visible) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, pinned, showTerminalButton);
    }

    public ModuleLayoutPolicy withFloating(boolean floating) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, floating && pinned, showTerminalButton);
    }

    public ModuleLayoutPolicy withPinned(boolean pinned) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, floating && pinned, showTerminalButton);
    }

    public ModuleLayoutPolicy withShowTerminalButton(boolean showTerminalButton) {
        return new ModuleLayoutPolicy(visible, movable, resizable, floating, pinned, showTerminalButton);
    }
}
