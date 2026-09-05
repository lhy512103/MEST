package com.lhy.mest.client.dock.model;

/** Non-negative nudge of a panel's content block inside a fixed shell section. */
public record ContentOffset(int x, int y) {
    public static final ContentOffset ZERO = new ContentOffset(0, 0);

    public ContentOffset {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("content offset must be non-negative");
        }
    }

    public boolean isZero() {
        return x == 0 && y == 0;
    }
}
