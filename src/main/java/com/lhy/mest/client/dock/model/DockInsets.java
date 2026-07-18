package com.lhy.mest.client.dock.model;

/** Insets between a floating root frame and its recursive content tree. */
public record DockInsets(int left, int top, int right, int bottom) {
    public static final DockInsets NONE = new DockInsets(0, 0, 0, 0);

    public DockInsets {
        if (left < 0 || top < 0 || right < 0 || bottom < 0) {
            throw new IllegalArgumentException("insets must be non-negative");
        }
    }

    public int horizontal() {
        return left + right;
    }

    public int vertical() {
        return top + bottom;
    }
}
