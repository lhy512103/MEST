package com.lhy.mest.client.dock.model;

import java.util.Locale;

/**
 * How a composite floating root draws its chrome and allocates space between leaves.
 *
 * <p>Three modes are kept on purpose: {@link #UNIFIED} and {@link #COMPACT} are the long-standing
 * behaviours, {@link #SHELL} is the newer seam-free variant. New modes may be appended; the
 * persisted id is stable so old documents keep loading.
 */
public enum SpliceMode {
    /**
     * Stretch splice. The root keeps its own bounds and leaves are stretched by split ratio to
     * fill it. One generated background covers the whole window, leaves only draw a section title.
     */
    UNIFIED("unified"),
    /**
     * Compact splice. The root shrinks to the leaves' preferred occupancy and nested splits may
     * tuck into an existing L-shaped hole. Each leaf draws its own 9-slice frame and suppresses
     * edges that touch a sibling, so seams depend on exact pixel adjacency.
     */
    COMPACT("compact"),
    /**
     * Outer-shell splice. The root shrinks to the leaves' preferred occupancy, but the allocation
     * is then stretched back over that shrunken rectangle so the leaf union is always a perfect
     * rectangle. A single background is drawn once over the whole shell and sections are separated
     * by a 1px rule, so no seam can appear.
     */
    SHELL("shell");

    /** Mode used when a document predates {@code spliceMode}. */
    public static final SpliceMode DEFAULT = UNIFIED;

    private final String id;

    SpliceMode(String id) {
        this.id = id;
    }

    /** Stable lowercase id used by {@code layout.json}. */
    public String id() {
        return id;
    }

    /** Next mode in the toolbar cycle: unified -&gt; compact -&gt; shell -&gt; unified. */
    public SpliceMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** True when the root bounds follow the leaves instead of stretching them to a fixed frame. */
    public boolean prefersCompactBounds() {
        return this != UNIFIED;
    }

    /** True for the only mode that lets a nested split tuck into an L-shaped hole. */
    public boolean packsContour() {
        return this == COMPACT;
    }

    /** True for the only mode where each leaf paints its own 9-slice frame. */
    public boolean drawsPerLeafFrames() {
        return this == COMPACT;
    }

    /** True when one background is drawn over the whole shell plus 1px section rules. */
    public boolean drawsOuterShell() {
        return this == SHELL;
    }

    /**
     * Resolves a persisted id, falling back to {@link #DEFAULT} for unknown or missing values so a
     * corrupted document degrades instead of failing to load.
     */
    public static SpliceMode fromId(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (SpliceMode mode : values()) {
            if (mode.id.equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return DEFAULT;
    }
}
