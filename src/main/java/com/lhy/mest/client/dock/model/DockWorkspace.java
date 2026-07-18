package com.lhy.mest.client.dock.model;

import java.util.List;

/** Immutable floating roots stored back-to-front. */
public record DockWorkspace(List<FloatingRoot> roots) {
    public DockWorkspace {
        if (roots == null) {
            throw new NullPointerException("roots");
        }
        roots = List.copyOf(roots);
    }
}
