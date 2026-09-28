package com.lhy.mest.client.dock.model;

/**
 * Clamps a content-block nudge into leftover section slack and snaps it onto the centred pose.
 *
 * <p>The centred pose is {@code slack / 2} on each axis. Crossing that midpoint within
 * {@link #SNAP_DISTANCE} pixels sticks the block there so a player can find the visual centre
 * without pixel hunting, the same way design tools magnetise to guides.
 */
public final class ContentNudge {
    public static final int SNAP_DISTANCE = 4;

    private ContentNudge() {
    }

    public static ContentOffset clamp(int x, int y, int slackX, int slackY) {
        return new ContentOffset(clampAxis(x, slackX), clampAxis(y, slackY));
    }

    public static Snap snap(int x, int y, int slackX, int slackY) {
        AxisSnap horizontal = snapAxis(x, slackX);
        AxisSnap vertical = snapAxis(y, slackY);
        return new Snap(new ContentOffset(horizontal.value(), vertical.value()), horizontal.snapped(), vertical.snapped());
    }

    private static AxisSnap snapAxis(int value, int slack) {
        int clamped = clampAxis(value, slack);
        int centre = slack / 2;
        if (slack > 0 && Math.abs(clamped - centre) <= SNAP_DISTANCE) {
            return new AxisSnap(centre, true);
        }
        return new AxisSnap(clamped, false);
    }

    private static int clampAxis(int value, int slack) {
        if (slack <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(slack, value));
    }

    public record Snap(ContentOffset offset, boolean snapX, boolean snapY) {
    }

    private record AxisSnap(int value, boolean snapped) {
    }
}
