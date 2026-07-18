package com.lhy.mest.client.dock.model;

import java.util.Objects;

/** Geometry constants supplied by the eventual renderer integration. */
public record LayoutStyle(DockInsets rootInsets, int dividerThickness) {
    public LayoutStyle {
        Objects.requireNonNull(rootInsets, "rootInsets");
        if (dividerThickness < 0) {
            throw new IllegalArgumentException("dividerThickness must be non-negative");
        }
    }
}
