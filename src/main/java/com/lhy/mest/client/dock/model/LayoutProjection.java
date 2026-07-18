package com.lhy.mest.client.dock.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Cached, immutable geometry derived from a workspace revision. */
public final class LayoutProjection {
    private final List<RootPlacement> roots;
    private final Map<String, DockSize> minimumSizes;
    private final Map<String, DockRect> visibleNodeBounds;
    private final Map<String, LeafPlacement> visibleLeavesByNodeId;
    private final List<LeafPlacement> visibleLeavesInPaintOrder;
    private final List<DividerPlacement> dividers;
    private final Set<String> effectivelyVisibleNodeIds;

    LayoutProjection(
            List<RootPlacement> roots,
            Map<String, DockSize> minimumSizes,
            Map<String, DockRect> visibleNodeBounds,
            Map<String, LeafPlacement> visibleLeavesByNodeId,
            List<LeafPlacement> visibleLeavesInPaintOrder,
            List<DividerPlacement> dividers,
            Set<String> effectivelyVisibleNodeIds) {
        this.roots = List.copyOf(roots);
        this.minimumSizes = immutableMap(minimumSizes);
        this.visibleNodeBounds = immutableMap(visibleNodeBounds);
        this.visibleLeavesByNodeId = immutableMap(visibleLeavesByNodeId);
        this.visibleLeavesInPaintOrder = List.copyOf(visibleLeavesInPaintOrder);
        this.dividers = List.copyOf(dividers);
        this.effectivelyVisibleNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(effectivelyVisibleNodeIds));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public List<RootPlacement> roots() {
        return roots;
    }

    public Map<String, DockSize> minimumSizes() {
        return minimumSizes;
    }

    public Optional<DockRect> boundsFor(String nodeId) {
        return Optional.ofNullable(visibleNodeBounds.get(nodeId));
    }

    public Optional<LeafPlacement> visibleLeaf(String nodeId) {
        return Optional.ofNullable(visibleLeavesByNodeId.get(nodeId));
    }

    public List<LeafPlacement> visibleLeavesInPaintOrder() {
        return visibleLeavesInPaintOrder;
    }

    public List<DividerPlacement> dividers() {
        return dividers;
    }

    public boolean isEffectivelyVisible(String nodeId) {
        return effectivelyVisibleNodeIds.contains(nodeId);
    }

    public record RootPlacement(
            String rootId,
            DockRect bounds,
            DockRect contentBounds,
            DockSize minimumSize,
            boolean effectivelyVisible) {
    }

    public record LeafPlacement(String nodeId, String moduleId, DockRect bounds) {
    }

    public record DividerPlacement(String splitNodeId, DockAxis axis, DockRect bounds) {
    }
}
