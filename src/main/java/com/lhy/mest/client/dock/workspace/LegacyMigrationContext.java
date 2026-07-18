package com.lhy.mest.client.dock.workspace;

import java.util.Objects;

import com.lhy.mest.client.dock.model.DockInsets;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.ModuleMetrics;

/** Geometry needed to translate the unversioned one-level layout into v2. */
public record LegacyMigrationContext(
        DockInsets rootInsets,
        int dividerThickness,
        int tileStartX,
        int tileStartY,
        int tileStep,
        int viewportWidth,
        int viewportHeight) {
    public LegacyMigrationContext {
        Objects.requireNonNull(rootInsets, "rootInsets");
        if (dividerThickness < 0 || tileStep < 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("invalid legacy migration geometry");
        }
    }

    /** Matches the unversioned v1 title/padding and divider geometry. */
    public static LegacyMigrationContext currentDockDefaults(int viewportWidth, int viewportHeight) {
        return new LegacyMigrationContext(
                new DockInsets(7, 25, 7, 7),
                4,
                8,
                8,
                16,
                viewportWidth,
                viewportHeight);
    }

    public DockRect defaultRootBounds(ModuleMetrics metrics, int tileIndex) {
        int width = metrics.defaultSize().width() + rootInsets.horizontal();
        int height = metrics.defaultSize().height() + rootInsets.vertical();
        int x = tileStartX + tileIndex * tileStep;
        int y = tileStartY + tileIndex * tileStep;
        return clampToViewport(new DockRect(x, y, width, height));
    }

    public DockRect clampToViewport(DockRect bounds) {
        int x = Math.max(0, Math.min(bounds.x(), Math.max(0, viewportWidth - bounds.width())));
        int y = Math.max(0, Math.min(bounds.y(), Math.max(0, viewportHeight - bounds.height())));
        return new DockRect(x, y, bounds.width(), bounds.height());
    }
}
