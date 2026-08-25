package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.lhy.mest.client.dock.model.DockWorkspace;

/** Three named layout slots plus import/export/backup helpers. */
public final class LayoutPresetBank {
    public static final int SLOT_COUNT = 3;
    public static final int FILE_VERSION = 1;
    public static final String PRESETS_FILE = "presets.json";
    public static final String SHARE_LAYOUT_FILE = "layout.json";
    public static final String SHARE_NAME_FILE = "name.txt";

    public record SlotDocument(DockWorkspace workspace, String name) {
    }

    public static String encodeSlot(DockWorkspace workspace, String name, DockLayoutCodec codec) {
        JsonObject object = JsonParser.parseString(codec.encode(workspace)).getAsJsonObject();
        if (name != null && !name.isBlank()) {
            object.addProperty("name", name);
        }
        return GSON.toJson(object);
    }

    public static SlotDocument decodeSlot(String json, DockLayoutCodec codec) throws DockLayoutFormatException {
        DockWorkspace workspace = codec.decode(json).workspace();
        String name = "";
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("name")) {
                name = sanitizeName(parsed.getAsJsonObject().get("name").getAsString());
            }
        } catch (RuntimeException ignored) {
            // Workspace already decoded; missing name stays empty.
        }
        return new SlotDocument(workspace, name);
    }
    public static final String SHARE_PRESETS_FILE = "presets.json";
    public static final String SHARE_ZIP_FILE = "mest-layout.zip";

    public static String shareSlotFolder(int index) {
        return String.valueOf(Math.floorMod(index, SLOT_COUNT) + 1);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LayoutPresetBank() {
    }

    public record Data(int active, String[] names, DockWorkspace[] workspaces) {
        public Data {
            if (names == null || names.length != SLOT_COUNT) {
                throw new IllegalArgumentException("names");
            }
            if (workspaces == null || workspaces.length != SLOT_COUNT) {
                throw new IllegalArgumentException("workspaces");
            }
            active = Math.floorMod(active, SLOT_COUNT);
            names = names.clone();
            workspaces = workspaces.clone();
        }

        public Data copy() {
            return new Data(active, names, workspaces);
        }

        public Data withActive(int index) {
            return new Data(index, names, workspaces);
        }

        public Data withName(int index, String name) {
            String[] next = names.clone();
            next[Math.floorMod(index, SLOT_COUNT)] = name;
            return new Data(active, next, workspaces);
        }

        public Data withWorkspace(int index, DockWorkspace workspace) {
            DockWorkspace[] next = workspaces.clone();
            next[Math.floorMod(index, SLOT_COUNT)] = Objects.requireNonNull(workspace, "workspace");
            return new Data(active, names, next);
        }
    }

    public static Data create(
            DockWorkspace current,
            DockWorkspace filler,
            String[] defaultNames) {
        String[] names = new String[SLOT_COUNT];
        DockWorkspace[] workspaces = new DockWorkspace[SLOT_COUNT];
        for (int index = 0; index < SLOT_COUNT; index++) {
            names[index] = defaultNames != null && index < defaultNames.length
                    ? sanitizeName(defaultNames[index])
                    : "";
            workspaces[index] = index == 0 ? current : filler;
        }
        return new Data(0, names, workspaces);
    }

    public static Data load(Path file, DockLayoutCodec codec, Data fallback) {
        if (file == null || !Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            Data loaded = read(Files.readString(file, StandardCharsets.UTF_8), codec, fallback);
            return loaded == null ? fallback : loaded;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    public static Data read(String json, DockLayoutCodec codec, Data fallback) throws DockLayoutFormatException {
        if (json == null || json.isBlank()) {
            throw new DockLayoutFormatException("preset document is empty");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new DockLayoutFormatException("invalid layout JSON", e);
        }
        if (!parsed.isJsonObject()) {
            throw new DockLayoutFormatException("preset document must be a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("presets")) {
            return readBank(object, codec, fallback);
        }
        if (object.has("roots")) {
            DockWorkspace workspace = codec.decode(json).workspace();
            Data data = fallback.copy();
            return data.withWorkspace(data.active(), workspace);
        }
        throw new DockLayoutFormatException("unrecognized layout document");
    }

    public static void save(Path file, Data data, DockLayoutCodec codec) throws IOException {
        writeAtomic(file, GSON.toJson(writeBank(data, codec)));
    }

    public static void zipShareSlots(Path shareDir, Path zipFile) throws IOException {
        Path parent = zipFile.getParent();
        if (parent == null) {
            throw new IOException("zip path has no parent: " + zipFile);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "mest-layout-", ".tmp.zip");
        boolean moved = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(
                    Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                for (int index = 0; index < SLOT_COUNT; index++) {
                    Path slotDir = shareDir.resolve(shareSlotFolder(index));
                    addZipFile(zip, slotDir.resolve(SHARE_LAYOUT_FILE), shareSlotFolder(index) + "/" + SHARE_LAYOUT_FILE);
                }
            }
            try {
                Files.move(temporary, zipFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, zipFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static Data readShareZip(Path zipFile, DockLayoutCodec codec, Data fallback)
            throws IOException, DockLayoutFormatException {
        Data data = fallback.copy();
        boolean any = false;
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipEntry(entry.getName());
                if (name == null) {
                    continue;
                }
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                int slot;
                try {
                    slot = Integer.parseInt(name.substring(0, slash)) - 1;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (slot < 0 || slot >= SLOT_COUNT) {
                    continue;
                }
                String file = name.substring(slash + 1);
                byte[] bytes = zip.readAllBytes();
                if (SHARE_LAYOUT_FILE.equals(file)) {
                    SlotDocument document = decodeSlot(new String(bytes, StandardCharsets.UTF_8), codec);
                    data = data.withWorkspace(slot, document.workspace());
                    if (!document.name().isBlank()) {
                        data = data.withName(slot, document.name());
                    }
                    any = true;
                } else if (SHARE_NAME_FILE.equals(file)) {
                    data = data.withName(slot, sanitizeName(new String(bytes, StandardCharsets.UTF_8)));
                }
            }
        }
        if (!any) {
            throw new DockLayoutFormatException("zip does not contain preset layouts");
        }
        return data;
    }

    private static void addZipFile(ZipOutputStream zip, Path file, String entryName) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static String normalizeZipEntry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.startsWith("share/")) {
            name = name.substring("share/".length());
        }
        if (name.contains("..")) {
            return null;
        }
        return name;
    }

    public static void writeAtomic(Path file, String json) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("path has no parent: " + file);
        }
        Files.createDirectories(parent);
        String fileName = file.getFileName().toString();
        String prefix = fileName.length() >= 3 ? fileName : "layout-";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static void clearDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (Files.isRegularFile(child)) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    public static String defaultName(String[] defaults, int index) {
        if (defaults != null && index >= 0 && index < defaults.length && defaults[index] != null
                && !defaults[index].isBlank()) {
            return defaults[index];
        }
        return String.valueOf(index + 1);
    }

    public static String sanitizeName(String name, String fallback) {
        String trimmed = sanitizeName(name);
        return trimmed.isEmpty() ? (fallback == null ? "" : fallback) : trimmed;
    }

    public static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.replaceAll("\\p{Cntrl}", "").strip();
        if (trimmed.length() > 24) {
            trimmed = trimmed.substring(0, 24).strip();
        }
        return trimmed;
    }

    private static Data readBank(JsonObject object, DockLayoutCodec codec, Data fallback)
            throws DockLayoutFormatException {
        int active = object.has("active") ? object.get("active").getAsInt() : 0;
        JsonArray presets = object.getAsJsonArray("presets");
        if (presets == null) {
            throw new DockLayoutFormatException("presets must be an array");
        }
        String[] names = Arrays.copyOf(fallback.names(), SLOT_COUNT);
        DockWorkspace[] workspaces = Arrays.copyOf(fallback.workspaces(), SLOT_COUNT);
        int count = Math.min(SLOT_COUNT, presets.size());
        for (int index = 0; index < count; index++) {
            JsonElement element = presets.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject preset = element.getAsJsonObject();
            if (preset.has("name")) {
                names[index] = sanitizeName(preset.get("name").getAsString(), names[index]);
            }
            if (preset.has("layout") && preset.get("layout").isJsonObject()) {
                workspaces[index] = codec.decode(preset.get("layout").toString()).workspace();
            }
        }
        return new Data(active, names, workspaces);
    }

    private static JsonObject writeBank(Data data, DockLayoutCodec codec) {
        JsonObject object = new JsonObject();
        object.addProperty("version", FILE_VERSION);
        object.addProperty("active", data.active());
        JsonArray presets = new JsonArray();
        for (int index = 0; index < SLOT_COUNT; index++) {
            JsonObject preset = new JsonObject();
            preset.addProperty("name", data.names()[index]);
            preset.add("layout", JsonParser.parseString(codec.encode(data.workspaces()[index])).getAsJsonObject());
            presets.add(preset);
        }
        object.add("presets", presets);
        return object;
    }
}
