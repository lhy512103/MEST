package com.lhy.mest.client.dock.workspace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LayoutNode;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.ModuleCatalog;
import com.lhy.mest.client.dock.model.NodeIds;
import com.lhy.mest.client.dock.model.SplitNode;
import com.lhy.mest.client.dock.model.WorkspaceValidationException;
import com.lhy.mest.client.dock.model.WorkspaceValidator;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.LeafDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.NodeDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.RootDto;
import com.lhy.mest.client.dock.workspace.DockLayoutDto.SplitDto;

/** Strict v2 codec plus deterministic migration of the current unversioned v1 map. */
public final class DockLayoutCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> V2_FIELDS = Set.of("version", "roots");
    private static final Set<String> ROOT_FIELDS = Set.of("rootId", "bounds", "content");
    private static final Set<String> BOUNDS_FIELDS = Set.of("x", "y", "width", "height");
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
                DockLayoutDto dto = readV2(object);
                return new DecodedLayout(fromDto(dto), DockLayoutDto.CURRENT_VERSION, false);
            }
            return new DecodedLayout(migrateV1(object), 1, true);
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
        return new DockLayoutDto(DockLayoutDto.CURRENT_VERSION, roots);
    }

    public DockWorkspace fromDto(DockLayoutDto dto) throws DockLayoutFormatException {
        if (dto == null) {
            throw new DockLayoutFormatException("layout DTO must not be null");
        }
        if (dto.version() != DockLayoutDto.CURRENT_VERSION) {
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
            DockWorkspace workspace = new DockWorkspace(roots);
            WorkspaceValidator.validateStrict(workspace, catalog);
            return workspace;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DockLayoutFormatException("invalid v2 DTO: " + e.getMessage(), e);
        }
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

    private DockLayoutDto readV2(JsonObject object) throws DockLayoutFormatException {
        requireOnlyFields(object, V2_FIELDS, "document");
        int version = integer(object, "version", "document");
        if (version != DockLayoutDto.CURRENT_VERSION) {
            throw new DockLayoutFormatException("unsupported layout version: " + version);
        }
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
        return new DockLayoutDto(version, roots);
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
        var consumedModules = new HashSet<String>();
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

        for (String moduleId : catalog.moduleIds()) {
            if (consumedModules.add(moduleId)) {
                roots.add(new FloatingRoot(
                        DockWorkspaceDefaults.rootId(moduleId),
                        migrationContext.defaultRootBounds(catalog.metrics(moduleId), index++),
                        new LeafNode(DockWorkspaceDefaults.leafNodeId(moduleId), moduleId, true)));
            }
        }

        DockWorkspace workspace = new DockWorkspace(roots);
        try {
            WorkspaceValidator.validateStrict(workspace, catalog);
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
        if (!catalog.contains(moduleId)) {
            throw new DockLayoutFormatException(path + " references unknown module " + moduleId);
        }
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

    public record DecodedLayout(DockWorkspace workspace, int sourceVersion, boolean migrated) {
        public DecodedLayout {
            Objects.requireNonNull(workspace, "workspace");
        }
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
