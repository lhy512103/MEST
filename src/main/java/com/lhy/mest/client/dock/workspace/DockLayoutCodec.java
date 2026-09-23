package com.lhy.mest.client.dock.workspace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.lhy.mest.client.dock.model.ContentOffset;
import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockSize;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LayoutNode;
import com.lhy.mest.client.dock.model.LayoutTrees;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.ModuleLayoutPolicy;
import com.lhy.mest.client.dock.model.NodeIds;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.model.SpliceMode;
import com.lhy.mest.client.dock.model.WorkspaceValidationException;
import com.lhy.mest.client.dock.model.WorkspaceValidator;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.LeafDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.NodeDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.PolicyDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.RootDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.SplitDto;

/** Strict versioned codec plus deterministic migration of legacy v1 and v2 layouts. */
public final class DockLayoutCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> V2_FIELDS = Set.of("version", "roots");
    /**
     * {@code spliceMode} supersedes the v3 {@code compactSplice} boolean. The old key stays in
     * {@link #V3_FIELDS} so documents written before the third mode still decode.
     */
    private static final Set<String> V3_FIELDS =
            Set.of("version", "roots", "policies", "restoreSizes", "name", "spliceMode", "compactSplice",
                    "contentOffsets");
    private static final Set<String> OFFSET_FIELDS = Set.of("x", "y");
    private static final Set<String> POLICY_FIELDS =
            Set.of("visible", "movable", "resizable", "floating", "pinned", "showTerminalButton");
    private static final Set<String> ROOT_FIELDS = Set.of("rootId", "bounds", "content");
    private static final Set<String> BOUNDS_FIELDS = Set.of("x", "y", "width", "height");
    private static final Set<String> SIZE_FIELDS = Set.of("width", "height");
    private static final Set<String> LEAF_FIELDS = Set.of("type", "nodeId", "moduleId", "visible");
    private static final Set<String> SPLIT_FIELDS = Set.of("type", "nodeId", "axis", "ratio", "first", "second");
    private static final Set<String> V1_ENTRY_FIELDS =
            Set.of("x", "y", "width", "height", "visible", "orientation", "split", "children");

    private final ModuleCatalog catalog;
    private final LegacyMigrationContext migrationContext;

    public DockLayoutCodec(ModuleCatalog catalog, LegacyMigrationContext migrationContext) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.migrationContext = Objects.requireNonNull(migrationContext, "migrationContext");
    }

    public String encode(DockWorkspace workspace) {
        return GSON.toJson(writeDto(toDto(workspace)));
    }

    public DecodedLayout decode(String json) throws DockLayoutFormatException {
        if (json == null || json.isBlank()) {
            throw new DockLayoutFormatException("layout document is empty");
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new DockLayoutFormatException("layout document must be a JSON object");
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("version")) {
                DockLayoutDto dto = readVersioned(object);
                Reconciliation reconciliation = reconcile(fromDtoRaw(dto));
                return new DecodedLayout(
                        reconciliation.workspace(),
                        dto.version(),
                        false,
                        dto.version() != DockLayoutDto.CURRENT_VERSION || reconciliation.changed());
            }
            Reconciliation reconciliation = reconcile(migrateV1(object));
            return new DecodedLayout(reconciliation.workspace(), 1, true, reconciliation.changed());
        } catch (DockLayoutFormatException e) {
            throw e;
        } catch (JsonParseException | ArithmeticException | ClassCastException e) {
            throw new DockLayoutFormatException("invalid layout JSON", e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DockLayoutFormatException("invalid layout: " + e.getMessage(), e);
        }
    }

    public DockLayoutDto toDto(DockWorkspace workspace) {
        WorkspaceValidator.validateStrict(workspace, catalog);
        var roots = new ArrayList<RootDto>();
        for (FloatingRoot root : workspace.roots()) {
            roots.add(new RootDto(root.rootId(), root.bounds(), toDto(root.content())));
        }
        var policies = new java.util.LinkedHashMap<String, PolicyDto>();
        for (String moduleId : catalog.moduleIds()) {
            ModuleLayoutPolicy policy = workspace.policyFor(moduleId);
            policies.put(moduleId, new PolicyDto(
                    policy.visible(),
                    policy.movable(),
                    policy.resizable(),
                    policy.floating(),
                    policy.pinned(),
                    policy.showTerminalButton()));
        }
        return new DockLayoutDto(
                DockLayoutDto.CURRENT_VERSION,
                roots,
                policies,
                workspace.restoreSizes(),
                workspace.spliceMode(),
                workspace.contentOffsets());
    }

    public DockWorkspace fromDto(DockLayoutDto dto) throws DockLayoutFormatException {
        return reconcile(fromDtoRaw(dto)).workspace();
    }

    /**
     * Placement tweaks: an older document may hold the network-tool panel further right than the
     * current default. Only a window holding nothing but that panel is moved; one it was spliced
     * into was arranged by the player, and shifting it would drag every other module along.
     */
    private static void migrateNetworkToolkitPlacement(List<FloatingRoot> roots, int version) {
        int shift = DockWorkspaceDefaults.networkToolkitShiftFrom(version);
        if (shift <= 0) {
            return;
        }
        for (int index = 0; index < roots.size(); index++) {
            FloatingRoot root = roots.get(index);
            if (root.content() instanceof LeafNode leaf
                    && DockWorkspaceDefaults.NETWORK_TOOLKIT_MODULE.equals(leaf.moduleId())) {
                roots.set(index, root.withBounds(
                        DockWorkspaceDefaults.networkToolkitBounds(root.bounds(), shift)));
            }
        }
    }

    private DockWorkspace fromDtoRaw(DockLayoutDto dto) throws DockLayoutFormatException {
        if (dto == null) {
            throw new DockLayoutFormatException("layout DTO must not be null");
        }
        if (dto.version() < DockLayoutDto.MIN_SUPPORTED_VERSION
                || dto.version() > DockLayoutDto.CURRENT_VERSION) {
            throw new DockLayoutFormatException("unsupported layout version: " + dto.version());
        }
        if (dto.roots() == null) {
            throw new DockLayoutFormatException("roots must not be null");
        }

        var budget = new DecodeBudget();
        var roots = new ArrayList<FloatingRoot>();
        try {
            for (RootDto root : dto.roots()) {
                if (root == null || root.bounds() == null || root.content() == null) {
                    throw new DockLayoutFormatException("root DTO fields must not be null");
                }
                roots.add(new FloatingRoot(
                        root.rootId(),
                        root.bounds(),
                        fromDto(root.content(), budget, 1)));
            }
            migrateNetworkToolkitPlacement(roots, dto.version());
            if (dto.version() == 2) {
                DockWorkspace workspace = new DockWorkspace(roots);
                WorkspaceValidator.validateStructure(workspace);
                return workspace;
            }
            var policies = new java.util.LinkedHashMap<String, ModuleLayoutPolicy>();
            if (dto.policies() != null) {
                dto.policies().forEach((moduleId, policy) -> {
                    if (policy != null) {
                        policies.put(moduleId, new ModuleLayoutPolicy(
                                policy.visible(),
                                policy.movable(),
                                policy.resizable(),
                                policy.floating(),
                                policy.pinned(),
                                policy.showTerminalButton()));
                    }
                });
            }
            DockWorkspace workspace = new DockWorkspace(
                    roots,
                    policies,
                    DockWorkspace.retainRestoreSizes(dto.restoreSizes(), roots),
                    dto.spliceMode(),
                    dto.contentOffsets());
            WorkspaceValidator.validateStructure(workspace);
            return workspace;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DockLayoutFormatException("invalid versioned DTO: " + e.getMessage(), e);
        }
    }

    /**
     * Reconciles a structurally valid persisted workspace with the modules registered by the
     * current terminal screen.
     *
     * <p>Module ids are the only stable association between persisted leaves and runtime panels.
     * Unknown leaves are removed (collapsing now-empty split branches), while newly registered
     * modules receive deterministic default roots appended in catalog order. This keeps a single
     * added, removed, or renamed module from invalidating an otherwise usable layout.</p>
     */
    public DockWorkspace reconcileWorkspace(DockWorkspace workspace) throws DockLayoutFormatException {
        return reconcile(workspace).workspace();
    }

    private Reconciliation reconcile(DockWorkspace source) throws DockLayoutFormatException {
        try {
            WorkspaceValidator.validateStructure(source);
        } catch (RuntimeException e) {
            throw new DockLayoutFormatException("invalid persisted layout structure: " + e.getMessage(), e);
        }

        var retainedRoots = new ArrayList<FloatingRoot>();
        for (FloatingRoot root : source.roots()) {
            LayoutNode retained = reconcileNode(root.content());
            if (retained != null) {
                retainedRoots.add(root.withContent(retained));
            }
        }

        var retainedPolicies = new java.util.LinkedHashMap<String, ModuleLayoutPolicy>();
        for (String moduleId : catalog.moduleIds()) {
            retainedPolicies.put(moduleId, source.policyFor(moduleId));
        }
        var result = new DockWorkspace(
                retainedRoots,
                retainedPolicies,
                DockWorkspace.retainRestoreSizes(source.restoreSizes(), retainedRoots),
                source.spliceMode(),
                source.contentOffsets());
        var usedModules = WorkspaceValidator.validateStructure(result);
        var usedIdentifiers = collectIdentifiers(result);
        int defaultIndex = retainedRoots.size();
        for (String moduleId : catalog.moduleIds()) {
            if (usedModules.contains(moduleId)) {
                continue;
            }
            String rootId = uniqueIdentifier(DockWorkspaceDefaults.rootId(moduleId), usedIdentifiers, "root");
            usedIdentifiers.add(rootId);
            String leafId = uniqueIdentifier(DockWorkspaceDefaults.leafNodeId(moduleId), usedIdentifiers, "leaf");
            usedIdentifiers.add(leafId);
            boolean visible = DockWorkspaceDefaults.defaultVisible(moduleId);
            boolean floating = DockWorkspaceDefaults.defaultFloating(moduleId)
                    || DockWorkspaceDefaults.defaultPinned(moduleId);
            boolean pinned = DockWorkspaceDefaults.defaultPinned(moduleId);
            retainedPolicies.put(moduleId, new ModuleLayoutPolicy(
                    visible, true, true, floating, pinned,
                    DockWorkspaceDefaults.defaultShowTerminalButton(moduleId)));
            retainedRoots.add(new FloatingRoot(
                    rootId,
                    DockWorkspaceDefaults.defaultBounds(
                            moduleId,
                            migrationContext.defaultRootBounds(catalog.metrics(moduleId), defaultIndex++)),
                    new LeafNode(leafId, moduleId, visible)));
        }

        result = new DockWorkspace(
                retainedRoots,
                retainedPolicies,
                DockWorkspace.retainRestoreSizes(source.restoreSizes(), retainedRoots),
                source.spliceMode(),
                source.contentOffsets());
        try {
            WorkspaceValidator.validateStrict(result, catalog);
        } catch (RuntimeException e) {
            throw new DockLayoutFormatException("invalid reconciled layout: " + e.getMessage(), e);
        }
        return new Reconciliation(result, !result.equals(source));
    }

    private LayoutNode reconcileNode(LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return catalog.contains(leaf.moduleId()) ? leaf.withVisible(true) : null;
        }
        SplitNode split = (SplitNode) node;
        LayoutNode first = reconcileNode(split.first());
        LayoutNode second = reconcileNode(split.second());
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return split.withChildren(first, second);
    }

    private static Set<String> collectIdentifiers(DockWorkspace workspace) {
        var identifiers = new LinkedHashSet<String>();
        for (FloatingRoot root : workspace.roots()) {
            identifiers.add(root.rootId());
            collectIdentifiers(root.content(), identifiers);
        }
        return identifiers;
    }

    private static void collectIdentifiers(LayoutNode node, Set<String> identifiers) {
        identifiers.add(node.nodeId());
        if (node instanceof SplitNode split) {
            collectIdentifiers(split.first(), identifiers);
            collectIdentifiers(split.second(), identifiers);
        }
    }

    private static String uniqueIdentifier(String preferred, Set<String> used, String namespace) {
        if (!used.contains(preferred)) {
            return preferred;
        }
        int suffix = 1;
        String candidate;
        do {
            candidate = NodeIds.deterministic(namespace, preferred + ":" + suffix++);
        } while (used.contains(candidate));
        return candidate;
    }

    private NodeDto toDto(LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return new LeafDto(leaf.nodeId(), leaf.moduleId(), leaf.visible());
        }
        var split = (SplitNode) node;
        return new SplitDto(
                split.nodeId(),
                split.axis(),
                split.ratio(),
                toDto(split.first()),
                toDto(split.second()));
    }

    private LayoutNode fromDto(NodeDto dto, DecodeBudget budget, int depth) throws DockLayoutFormatException {
        budget.enter(depth);
        if (dto instanceof LeafDto leaf) {
            return new LeafNode(leaf.nodeId(), leaf.moduleId(), leaf.visible());
        }
        if (!(dto instanceof SplitDto split)) {
            throw new DockLayoutFormatException("unsupported node DTO type");
        }
        return new SplitNode(
                split.nodeId(),
                split.axis(),
                split.ratio(),
                fromDto(split.first(), budget, depth + 1),
                fromDto(split.second(), budget, depth + 1));
    }

    private JsonObject writeDto(DockLayoutDto dto) {
        var object = new JsonObject();
        object.addProperty("version", dto.version());
        var roots = new JsonArray();
        for (RootDto root : dto.roots()) {
            var rootObject = new JsonObject();
            rootObject.addProperty("rootId", root.rootId());
            rootObject.add("bounds", writeBounds(root.bounds()));
            rootObject.add("content", writeNode(root.content()));
            roots.add(rootObject);
        }
        object.add("roots", roots);
        var policies = new JsonObject();
        for (var entry : dto.policies().entrySet()) {
            JsonObject policy = new JsonObject();
            policy.addProperty("visible", entry.getValue().visible());
            policy.addProperty("movable", entry.getValue().movable());
            policy.addProperty("resizable", entry.getValue().resizable());
            policy.addProperty("floating", entry.getValue().floating());
            policy.addProperty("pinned", entry.getValue().pinned());
            policy.addProperty("showTerminalButton", entry.getValue().showTerminalButton());
            policies.add(entry.getKey(), policy);
        }
        object.add("policies", policies);
        if (dto.restoreSizes() != null && !dto.restoreSizes().isEmpty()) {
            var restoreSizes = new JsonObject();
            for (var entry : dto.restoreSizes().entrySet()) {
                restoreSizes.add(entry.getKey(), writeSize(entry.getValue()));
            }
            object.add("restoreSizes", restoreSizes);
        }
        if (dto.spliceMode() != SpliceMode.DEFAULT) {
            object.addProperty("spliceMode", dto.spliceMode().id());
        }
        if (dto.contentOffsets() != null && !dto.contentOffsets().isEmpty()) {
            var offsets = new JsonObject();
            for (var entry : dto.contentOffsets().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isZero()) {
                    offsets.add(entry.getKey(), writeOffset(entry.getValue()));
                }
            }
            if (!offsets.entrySet().isEmpty()) {
                object.add("contentOffsets", offsets);
            }
        }
        return object;
    }

    private JsonObject writeBounds(DockRect bounds) {
        var object = new JsonObject();
        object.addProperty("x", bounds.x());
        object.addProperty("y", bounds.y());
        object.addProperty("width", bounds.width());
        object.addProperty("height", bounds.height());
        return object;
    }

    private JsonObject writeSize(DockSize size) {
        var object = new JsonObject();
        object.addProperty("width", size.width());
        object.addProperty("height", size.height());
        return object;
    }

    private JsonObject writeOffset(ContentOffset offset) {
        var object = new JsonObject();
        object.addProperty("x", offset.x());
        object.addProperty("y", offset.y());
        return object;
    }

    private JsonObject writeNode(NodeDto node) {
        var object = new JsonObject();
        if (node instanceof LeafDto leaf) {
            object.addProperty("type", "leaf");
            object.addProperty("nodeId", leaf.nodeId());
            object.addProperty("moduleId", leaf.moduleId());
            object.addProperty("visible", leaf.visible());
            return object;
        }
        var split = (SplitDto) node;
        object.addProperty("type", "split");
        object.addProperty("nodeId", split.nodeId());
        object.addProperty("axis", split.axis().name());
        object.addProperty("ratio", split.ratio());
        object.add("first", writeNode(split.first()));
        object.add("second", writeNode(split.second()));
        return object;
    }

    private DockLayoutDto readVersioned(JsonObject object) throws DockLayoutFormatException {
        int version = integer(object, "version", "document");
        if (version < DockLayoutDto.MIN_SUPPORTED_VERSION || version > DockLayoutDto.CURRENT_VERSION) {
            throw new DockLayoutFormatException("unsupported layout version: " + version);
        }
        requireOnlyFields(object, version == 2 ? V2_FIELDS : V3_FIELDS, "document");
        JsonArray rootArray = array(object, "roots", "document");
        var roots = new ArrayList<RootDto>();
        var budget = new DecodeBudget();
        for (int i = 0; i < rootArray.size(); i++) {
            String path = "roots[" + i + "]";
            JsonObject root = object(rootArray.get(i), path);
            requireOnlyFields(root, ROOT_FIELDS, path);
            roots.add(new RootDto(
                    string(root, "rootId", path),
                    readBounds(objectField(root, "bounds", path), path + ".bounds"),
                    readNode(objectField(root, "content", path), path + ".content", budget, 1)));
        }
        if (version == 2) {
            return new DockLayoutDto(version, roots);
        }
        JsonObject policyObject = objectField(object, "policies", "document");
        var policies = new java.util.LinkedHashMap<String, PolicyDto>();
        for (var entry : policyObject.entrySet()) {
            String path = "policies." + entry.getKey();
            JsonObject policy = object(entry.getValue(), path);
            requireOnlyFields(policy, POLICY_FIELDS, path);
            policies.put(entry.getKey(), new PolicyDto(
                    bool(policy, "visible", path),
                    bool(policy, "movable", path),
                    bool(policy, "resizable", path),
                    policy.has("floating") && bool(policy, "floating", path),
                    policy.has("pinned") && bool(policy, "pinned", path),
                    policy.has("showTerminalButton")
                            ? bool(policy, "showTerminalButton", path)
                            : DockWorkspaceDefaults.defaultShowTerminalButton(entry.getKey())));
        }
        return new DockLayoutDto(
                version,
                roots,
                policies,
                readRestoreSizes(object),
                readSpliceMode(object),
                readContentOffsets(object));
    }

    /**
     * Reads the splice mode, mapping the legacy {@code compactSplice} boolean onto
     * {@link SpliceMode#COMPACT}. Unknown ids fall back to {@link SpliceMode#DEFAULT} so a
     * hand-edited document still opens.
     */
    private SpliceMode readSpliceMode(JsonObject document) throws DockLayoutFormatException {
        if (document.has("spliceMode")) {
            return SpliceMode.fromId(string(document, "spliceMode", "document"));
        }
        if (document.has("compactSplice")) {
            return optionalBoolean(document, "compactSplice", false, "document")
                    ? SpliceMode.COMPACT
                    : SpliceMode.UNIFIED;
        }
        return SpliceMode.DEFAULT;
    }

    private DockRect readBounds(JsonObject object, String path) throws DockLayoutFormatException {
        requireOnlyFields(object, BOUNDS_FIELDS, path);
        int x = integer(object, "x", path);
        int y = integer(object, "y", path);
        int width = integer(object, "width", path);
        int height = integer(object, "height", path);
        if (width <= 0 || height <= 0) {
            throw new DockLayoutFormatException(path + " width and height must be positive");
        }
        return new DockRect(x, y, width, height);
    }

    private Map<String, ContentOffset> readContentOffsets(JsonObject document) throws DockLayoutFormatException {
        if (!document.has("contentOffsets")) {
            return Map.of();
        }
        JsonObject object = objectField(document, "contentOffsets", "document");
        var offsets = new java.util.LinkedHashMap<String, ContentOffset>();
        for (var entry : object.entrySet()) {
            String path = "contentOffsets." + entry.getKey();
            JsonObject offset = object(entry.getValue(), path);
            requireOnlyFields(offset, OFFSET_FIELDS, path);
            int x = integer(offset, "x", path);
            int y = integer(offset, "y", path);
            if (x < 0 || y < 0) {
                throw new DockLayoutFormatException(path + " x and y must be non-negative");
            }
            if (x != 0 || y != 0) {
                offsets.put(entry.getKey(), new ContentOffset(x, y));
            }
        }
        return offsets;
    }

    private Map<String, DockSize> readRestoreSizes(JsonObject document) throws DockLayoutFormatException {
        if (!document.has("restoreSizes")) {
            return Map.of();
        }
        JsonObject object = objectField(document, "restoreSizes", "document");
        var restoreSizes = new java.util.LinkedHashMap<String, DockSize>();
        for (var entry : object.entrySet()) {
            String path = "restoreSizes." + entry.getKey();
            JsonObject size = object(entry.getValue(), path);
            requireOnlyFields(size, SIZE_FIELDS, path);
            int width = integer(size, "width", path);
            int height = integer(size, "height", path);
            if (width <= 0 || height <= 0) {
                throw new DockLayoutFormatException(path + " width and height must be positive");
            }
            restoreSizes.put(entry.getKey(), new DockSize(width, height));
        }
        return restoreSizes;
    }

    private NodeDto readNode(JsonObject object, String path, DecodeBudget budget, int depth)
            throws DockLayoutFormatException {
        budget.enter(depth);
        String type = string(object, "type", path);
        if (type.equals("leaf")) {
            requireOnlyFields(object, LEAF_FIELDS, path);
            return new LeafDto(
                    string(object, "nodeId", path),
                    string(object, "moduleId", path),
                    bool(object, "visible", path));
        }
        if (type.equals("split")) {
            requireOnlyFields(object, SPLIT_FIELDS, path);
            DockAxis axis;
            try {
                axis = DockAxis.valueOf(string(object, "axis", path));
            } catch (IllegalArgumentException e) {
                throw new DockLayoutFormatException(path + ".axis is invalid", e);
            }
            return new SplitDto(
                    string(object, "nodeId", path),
                    axis,
                    decimal(object, "ratio", path),
                    readNode(objectField(object, "first", path), path + ".first", budget, depth + 1),
                    readNode(objectField(object, "second", path), path + ".second", budget, depth + 1));
        }
        throw new DockLayoutFormatException(path + ".type is unsupported: " + type);
    }

    private DockWorkspace migrateV1(JsonObject object) throws DockLayoutFormatException {
        var roots = new ArrayList<FloatingRoot>();
        var consumedModules = new LinkedHashSet<String>();
        int index = 0;
        for (var entry : object.entrySet()) {
            String path = "v1[" + entry.getKey() + "]";
            JsonObject panel = object(entry.getValue(), path);
            requireOnlyFields(panel, V1_ENTRY_FIELDS, path);
            DockRect bounds = migrationContext.clampToViewport(readLegacyBounds(panel, path));
            boolean visible = optionalBoolean(panel, "visible", true, path);

            boolean hasOrientation = panel.has("orientation");
            boolean hasSplit = panel.has("split");
            boolean hasChildren = panel.has("children");
            boolean composite = hasOrientation || hasSplit || hasChildren;
            LayoutNode content;
            if (composite) {
                if (!(hasOrientation && hasSplit && hasChildren)) {
                    throw new DockLayoutFormatException(path + " has incomplete composite fields");
                }
                DockAxis axis;
                try {
                    axis = DockAxis.valueOf(string(panel, "orientation", path));
                } catch (IllegalArgumentException e) {
                    throw new DockLayoutFormatException(path + ".orientation is invalid", e);
                }
                JsonArray children = array(panel, "children", path);
                if (children.size() != 2) {
                    throw new DockLayoutFormatException(path + ".children must contain exactly two module ids");
                }
                String firstModule = string(children.get(0), path + ".children[0]");
                String secondModule = string(children.get(1), path + ".children[1]");
                if (firstModule.equals(secondModule)) {
                    throw new DockLayoutFormatException(path + " repeats the same child module");
                }
                consumeLegacyModule(firstModule, consumedModules, path);
                consumeLegacyModule(secondModule, consumedModules, path);

                int available = Math.max(
                        1,
                        axis.extent(bounds.inset(migrationContext.rootInsets()))
                                - migrationContext.dividerThickness());
                int legacySplit = integer(panel, "split", path);
                double ratio = Math.max(0.000001, Math.min(0.999999, (double) legacySplit / available));
                content = new SplitNode(
                        NodeIds.deterministic("split", "v1:" + index + ":" + entry.getKey()),
                        axis,
                        ratio,
                        new LeafNode(DockWorkspaceDefaults.leafNodeId(firstModule), firstModule, visible),
                        new LeafNode(DockWorkspaceDefaults.leafNodeId(secondModule), secondModule, visible));
            } else {
                String moduleId = entry.getKey();
                consumeLegacyModule(moduleId, consumedModules, path);
                content = new LeafNode(DockWorkspaceDefaults.leafNodeId(moduleId), moduleId, visible);
            }
            roots.add(new FloatingRoot(
                    NodeIds.deterministic("root", "v1:" + index + ":" + entry.getKey()),
                    bounds,
                    content));
            index++;
        }

        DockWorkspace workspace = new DockWorkspace(roots);
        try {
            WorkspaceValidator.validateStructure(workspace);
        } catch (WorkspaceValidationException e) {
            throw new DockLayoutFormatException("invalid migrated v1 layout: " + e.getMessage(), e);
        }
        return workspace;
    }

    private DockRect readLegacyBounds(JsonObject panel, String path) throws DockLayoutFormatException {
        int width = integer(panel, "width", path);
        int height = integer(panel, "height", path);
        if (width <= 0 || height <= 0) {
            throw new DockLayoutFormatException(path + " width and height must be positive");
        }
        return new DockRect(integer(panel, "x", path), integer(panel, "y", path), width, height);
    }

    private void consumeLegacyModule(String moduleId, Set<String> consumed, String path)
            throws DockLayoutFormatException {
        if (!consumed.add(moduleId)) {
            throw new DockLayoutFormatException(path + " duplicates module " + moduleId);
        }
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed, String path)
            throws DockLayoutFormatException {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new DockLayoutFormatException(path + " contains unknown field " + field);
            }
        }
    }

    private static JsonObject objectField(JsonObject parent, String field, String path)
            throws DockLayoutFormatException {
        JsonElement element = required(parent, field, path);
        return object(element, path + "." + field);
    }

    private static JsonObject object(JsonElement element, String path) throws DockLayoutFormatException {
        if (!element.isJsonObject()) {
            throw new DockLayoutFormatException(path + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String field, String path) throws DockLayoutFormatException {
        JsonElement element = required(parent, field, path);
        if (!element.isJsonArray()) {
            throw new DockLayoutFormatException(path + "." + field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String string(JsonObject parent, String field, String path) throws DockLayoutFormatException {
        return string(required(parent, field, path), path + "." + field);
    }

    private static String string(JsonElement element, String path) throws DockLayoutFormatException {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new DockLayoutFormatException(path + " must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new DockLayoutFormatException(path + " must not be blank");
        }
        return value;
    }

    private static boolean bool(JsonObject parent, String field, String path) throws DockLayoutFormatException {
        JsonElement element = required(parent, field, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new DockLayoutFormatException(path + "." + field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static boolean optionalBoolean(
            JsonObject parent,
            String field,
            boolean defaultValue,
            String path) throws DockLayoutFormatException {
        return parent.has(field) ? bool(parent, field, path) : defaultValue;
    }

    private static int integer(JsonObject parent, String field, String path) throws DockLayoutFormatException {
        JsonElement element = required(parent, field, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new DockLayoutFormatException(path + "." + field + " must be an integer");
        }
        try {
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new DockLayoutFormatException(path + "." + field + " must be a 32-bit integer", e);
        }
    }

    private static double decimal(JsonObject parent, String field, String path) throws DockLayoutFormatException {
        JsonElement element = required(parent, field, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new DockLayoutFormatException(path + "." + field + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new DockLayoutFormatException(path + "." + field + " must be finite");
        }
        return value;
    }

    private static JsonElement required(JsonObject parent, String field, String path)
            throws DockLayoutFormatException {
        JsonElement element = parent.get(field);
        if (element == null || element.isJsonNull()) {
            throw new DockLayoutFormatException(path + " is missing " + field);
        }
        return element;
    }

    public record DecodedLayout(
            DockWorkspace workspace,
            int sourceVersion,
            boolean migrated,
            boolean reconciled) {
        public DecodedLayout(DockWorkspace workspace, int sourceVersion, boolean migrated) {
            this(workspace, sourceVersion, migrated, false);
        }

        public DecodedLayout {
            Objects.requireNonNull(workspace, "workspace");
        }

        public boolean needsRewrite() {
            return migrated || reconciled;
        }
    }

    private record Reconciliation(DockWorkspace workspace, boolean changed) {
    }

    private static final class DecodeBudget {
        private int nodes;

        private void enter(int depth) throws DockLayoutFormatException {
            if (depth > WorkspaceValidator.MAX_DEPTH) {
                throw new DockLayoutFormatException(
                        "layout exceeds maximum depth " + WorkspaceValidator.MAX_DEPTH);
            }
            if (++nodes > WorkspaceValidator.MAX_NODES) {
                throw new DockLayoutFormatException(
                        "layout exceeds maximum node count " + WorkspaceValidator.MAX_NODES);
            }
        }
    }
}
