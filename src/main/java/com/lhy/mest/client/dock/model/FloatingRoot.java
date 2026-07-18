package com.lhy.mest.client.dock.model;

import java.util.Objects;

/** One independently movable floating tree. Workspace list order is its Z order. */
public record FloatingRoot(String rootId, DockRect bounds, LayoutNode content) {
    public FloatingRoot {
        NodeIds.requireValid(rootId, "rootId");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(content, "content");
        if (bounds.width() == 0 || bounds.height() == 0) {
            throw new IllegalArgumentException("floating root bounds must be positive");
        }
    }

    public FloatingRoot withContent(LayoutNode newContent) {
        return new FloatingRoot(rootId, bounds, newContent);
    }

    public FloatingRoot withBounds(DockRect newBounds) {
        return new FloatingRoot(rootId, newBounds, content);
    }
}
