package com.lhy.mest.client.dock.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;

/** Versioned persistence DTO, deliberately separate from the runtime tree. */
public record DockLayoutDto(int version, List<RootDto> roots, Map<String, PolicyDto> policies) {
    public static final int CURRENT_VERSION = 3;

    public DockLayoutDto(int version, List<RootDto> roots) {
        this(version, roots, Map.of());
    }

    public DockLayoutDto {
        roots = roots == null ? null : List.copyOf(roots);
        policies = policies == null ? null : Map.copyOf(new LinkedHashMap<>(policies));
    }

    public record RootDto(String rootId, DockRect bounds, NodeDto content) {
    }

    public record PolicyDto(boolean visible, boolean movable, boolean resizable) {
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
