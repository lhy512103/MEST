package com.lhy.mest.client.dock.model;

import java.util.Objects;

/**
 * One independently movable floating tree. Workspace list order is its Z order.
 *
 * <p>{@code floating} is a window property: an anchored window belongs to the terminal group
 * (toolbar, upgrade column), a floating one stands on its own whatever modules it holds. A pinned
 * window is always floating and stays above unpinned ones. A floating window may also be a
 * combined module ({@code group} 1 to {@link #MAX_GROUPS}), which the terminal shows and hides as
 * a whole; {@code hidden} is that toggle.
 */
public record FloatingRoot(String rootId, DockRect bounds, LayoutNode content, boolean floating, boolean pinned,
        int group, boolean hidden) {
    public static final int MAX_GROUPS = 3;

    public FloatingRoot(String rootId, DockRect bounds, LayoutNode content) {
        this(rootId, bounds, content, false, false);
    }

    public FloatingRoot(String rootId, DockRect bounds, LayoutNode content, boolean floating, boolean pinned) {
        this(rootId, bounds, content, floating, pinned, 0, false);
    }

    public FloatingRoot {
        NodeIds.requireValid(rootId, "rootId");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(content, "content");
        if (bounds.width() == 0 || bounds.height() == 0) {
            throw new IllegalArgumentException("floating root bounds must be positive");
        }
        floating |= pinned;
        if (!floating || group < 1 || group > MAX_GROUPS) {
            group = 0;
        }
        hidden &= group != 0;
    }

    public FloatingRoot withContent(LayoutNode newContent) {
        return new FloatingRoot(rootId, bounds, newContent, floating, pinned, group, hidden);
    }

    public FloatingRoot withBounds(DockRect newBounds) {
        return new FloatingRoot(rootId, newBounds, content, floating, pinned, group, hidden);
    }

    /** Docking back to the terminal also drops the pin and any combined-module slot. */
    public FloatingRoot withMode(boolean newFloating, boolean newPinned) {
        return new FloatingRoot(rootId, bounds, content, newFloating, newFloating && newPinned, group, hidden);
    }

    public FloatingRoot withGroup(int newGroup) {
        return new FloatingRoot(rootId, bounds, content, floating, pinned, newGroup, newGroup != 0 && hidden);
    }

    public FloatingRoot withHidden(boolean newHidden) {
        return new FloatingRoot(rootId, bounds, content, floating, pinned, group, newHidden);
    }
}
