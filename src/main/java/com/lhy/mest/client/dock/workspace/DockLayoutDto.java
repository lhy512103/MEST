package com.lhy.mest.client.dock.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;

/** Versioned persistence DTO, deliberately separate from the runtime tree. */
public record DockLayoutDto(
        int version,
        List<RootDto> roots,
        Map<String, PolicyDto> policies,
        Map<String, DockSize> restoreSizes) {
    public static final int CURRENT_VERSION = 3;

    public DockLayoutDto(int version, List<RootDto> roots) {
        this(version, roots, Map.of(), Map.of());
    }

    public DockLayoutDto(int version, List<RootDto> roots, Map<String, PolicyDto> policies) {
        this(version, roots, policies, Map.of());
    }

    public DockLayoutDto {
        roots = roots == null ? null : List.copyOf(roots);
        policies = policies == null ? null : Map.copyOf(new LinkedHashMap<>(policies));
        restoreSizes = restoreSizes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(restoreSizes));
    }

    public record RootDto(String rootId, DockRect bounds, NodeDto content) {
    }

    public record PolicyDto(
            boolean visible, boolean movable, boolean resizable, boolean floating, boolean pinned) {
        public PolicyDto(boolean visible, boolean movable, boolean resizable) {
            this(visible, movable, resizable, false, false);
        }

        public PolicyDto(boolean visible, boolean movable, boolean resizable, boolean floating) {
            this(visible, movable, resizable, floating, false);
        }
    }

    public sealed interface NodeDto permits LeafDto, SplitDto {
        String nodeId();
    }

    public record LeafDto(String nodeId, String moduleId, boolean visible) implements NodeDto {
    }

    public record SplitDto(
            String nodeId,
            DockAxis axis,
            double ratio,
            NodeDto first,
            NodeDto second) implements NodeDto {
    }
}