package com.lhy.mest.client.dock.model;

import java.util.Objects;

/** Minimum and initial allocation of a leaf module, excluding root-frame insets. */
public record ModuleMetrics(DockSize minimumSize, DockSize defaultSize, boolean expandVertically) {
    public ModuleMetrics(DockSize minimumSize, DockSize defaultSize) {
        this(minimumSize, defaultSize, false);
    }

    public ModuleMetrics {
        Objects.requireNonNull(minimumSize, "minimumSize");
        Objects.requireNonNull(defaultSize, "defaultSize");
        if (minimumSize.isEmpty()) {
            throw new IllegalArgumentException("module minimum size must be positive");
        }
        if (defaultSize.width() < minimumSize.width() || defaultSize.height() < minimumSize.height()) {
            throw new IllegalArgumentException("default size must satisfy the minimum size");
        }
    }
}