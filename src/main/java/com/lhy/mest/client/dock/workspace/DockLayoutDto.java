package com.lhy.mest.client.dock.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lhy.mest.client.dock.model.ContentOffset;
import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;
import com.lhy.mest.client.dock.model.SpliceMode;

/** Versioned persistence DTO, deliberately separate from the runtime tree. */
public record DockLayoutDto(
        int version,
        List<RootDto> roots,
        Map<String, PolicyDto> policies,
        Map<String, DockSize> restoreSizes,
        SpliceMode spliceMode,
        Map<String, ContentOffset> contentOffsets) {
    /** v4 tightened the network-tool panel's placement by 4px; older documents are migrated. */
    public static final int CURRENT_VERSION = 4;
    /** Oldest version this codec still decodes. */
    public static final int MIN_SUPPORTED_VERSION = 2;

    public DockLayoutDto(int version, List<RootDto> roots) {
        this(version, roots, Map.of(), Map.of(), SpliceMode.DEFAULT, Map.of());
    }

    public DockLayoutDto(int version, List<RootDto> roots, Map<String, PolicyDto> policies) {
        this(version, roots, policies, Map.of(), SpliceMode.DEFAULT, Map.of());
    }

    public DockLayoutDto(
            int version,
            List<RootDto> roots,
            Map<String, PolicyDto> policies,
            Map<String, DockSize> restoreSizes) {
        this(version, roots, policies, restoreSizes, SpliceMode.DEFAULT, Map.of());
    }

    public DockLayoutDto(
            int version,
            List<RootDto> roots,
            Map<String, PolicyDto> policies,
            Map<String, DockSize> restoreSizes,
            SpliceMode spliceMode) {
        this(version, roots, policies, restoreSizes, spliceMode, Map.of());
    }

    public DockLayoutDto {
        roots = roots == null ? null : List.copyOf(roots);
        policies = policies == null ? null : Map.copyOf(new LinkedHashMap<>(policies));
        restoreSizes = restoreSizes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(restoreSizes));
        contentOffsets = contentOffsets == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(contentOffsets));
        if (spliceMode == null) {
            spliceMode = SpliceMode.DEFAULT;
        }
    }

    public record RootDto(String rootId, DockRect bounds, NodeDto content) {
    }

    public record PolicyDto(
            boolean visible,
            boolean movable,
            boolean resizable,
            boolean floating,
            boolean pinned,
            boolean showTerminalButton) {
        public PolicyDto(boolean visible, boolean movable, boolean resizable) {
            this(visible, movable, resizable, false, false, true);
        }

        public PolicyDto(boolean visible, boolean movable, boolean resizable, boolean floating) {
            this(visible, movable, resizable, floating, false, true);
        }

        public PolicyDto(boolean visible, boolean movable, boolean resizable, boolean floating, boolean pinned) {
            this(visible, movable, resizable, floating, pinned, true);
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