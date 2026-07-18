package com.lhy.mest.client.dock.model;

/** A non-negative two-dimensional size. */
public record DockSize(int width, int height) {
    public static final DockSize ZERO = new DockSize(0, 0);

    public DockSize {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }
}
