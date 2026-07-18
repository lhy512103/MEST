package com.lhy.mest.client.dock.workspace;

import java.util.List;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;

/** Versioned persistence DTO, deliberately separate from the runtime tree. */
public record DockLayoutDto(int version, List<RootDto> roots) {
    public static final int CURRENT_VERSION = 2;

    public DockLayoutDto {
        roots = roots == null ? null : List.copyOf(roots);
    }

    public record RootDto(String rootId, DockRect bounds, NodeDto content) {
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
