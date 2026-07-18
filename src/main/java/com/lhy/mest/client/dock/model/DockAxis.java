package com.lhy.mest.client.dock.model;

public enum DockAxis {
    HORIZONTAL,
    VERTICAL;

    public int extent(DockRect rect) {
        return this == HORIZONTAL ? rect.width() : rect.height();
    }

    public int extent(DockSize size) {
        return this == HORIZONTAL ? size.width() : size.height();
    }

    public int crossExtent(DockSize size) {
        return this == HORIZONTAL ? size.height() : size.width();
    }
}
