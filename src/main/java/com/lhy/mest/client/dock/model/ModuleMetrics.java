package com.lhy.mest.client.dock.model;

import java.util.Objects;

/** Minimum and initial allocation of a leaf module, excluding root-frame insets, plus its defaults. */
public record ModuleMetrics(DockSize minimumSize, DockSize defaultSize, boolean expandVertically,
        ModuleDefaults defaults) {
    public ModuleMetrics(DockSize minimumSize, DockSize defaultSize) {
        this(minimumSize, defaultSize, false);
    }

    public ModuleMetrics(DockSize minimumSize, DockSize defaultSize, boolean expandVertically) {
        this(minimumSize, defaultSize, expandVertically, ModuleDefaults.STANDARD);
    }

    public ModuleMetrics {
        Objects.requireNonNull(minimumSize, "minimumSize");
        Objects.requireNonNull(defaultSize, "defaultSize");
        Objects.requireNonNull(defaults, "defaults");
        if (minimumSize.isEmpty()) {
            throw new IllegalArgumentException("module minimum size must be positive");
        }
        if (defaultSize.width() < minimumSize.width() || defaultSize.height() < minimumSize.height()) {
            throw new IllegalArgumentException("default size must satisfy the minimum size");
        }
    }
}