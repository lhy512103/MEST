package com.lhy.mest.client.dock.model;

/** An absolute rectangle. Zero-sized rectangles are valid derived layout results. */
public record DockRect(int x, int y, int width, int height) {
    public DockRect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("rectangle size must be non-negative");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public DockRect inset(DockInsets insets) {
        int insetWidth = Math.max(0, width - insets.horizontal());
        int insetHeight = Math.max(0, height - insets.vertical());
        return new DockRect(x + insets.left(), y + insets.top(), insetWidth, insetHeight);
    }
}
