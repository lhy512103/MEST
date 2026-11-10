package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Filesystem boundary for layout sharing and backups. Does not own the live workspace, preset
 * persistence, editing state or UI. Callers apply imported snapshots only after saving their backup.
 */
public final class LayoutSharingService {
    private final Path configDirectory;
    private final DockLayoutCodec codec;

    public LayoutSharingService(Path configDirectory, DockLayoutCodec codec) {
        this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Path shareFolder() {
        return configDirectory.resolve("share");
    }

    public Path shareZipFile() {
        return shareFolder().resolve(LayoutPresetBank.SHARE_ZIP_FILE);
    }

    private Path backupFile() {
        return configDirectory.resolve("backup").resolve(LayoutPresetBank.PRESETS_FILE);
    }

    public boolean hasBackup() {
        return Files.isRegularFile(backupFile());
    }

    public void backup(LayoutPresetBank.Data snapshot) throws IOException {
        // Never clear the previous backup: serialization and the atomic write must succeed first.
        LayoutPresetBank.save(backupFile(), snapshot, codec);
    }

    public LayoutPresetBank.Data restoreBackup(LayoutPresetBank.Data fallback) throws IOException {
        return LayoutPresetBank.read(BoundedLayoutInput.readJson(backupFile()), codec, fallback);
    }

    public void exportLayouts(LayoutPresetBank.Data snapshot) throws IOException {
        Path share = shareFolder();
        Files.createDirectories(share);
        Files.deleteIfExists(share.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE));
        Files.deleteIfExists(share.resolve(LayoutPresetBank.SHARE_PRESETS_FILE));
        for (int index = 0; index < LayoutPresetBank.SLOT_COUNT; index++) {
            Path slotDirectory = share.resolve(LayoutPresetBank.shareSlotFolder(index));
            Files.createDirectories(slotDirectory);
            LayoutPresetBank.writeAtomic(slotDirectory.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE),
                    LayoutPresetBank.encodeSlot(snapshot.workspaces()[index], snapshot.names()[index], codec));
            Files.deleteIfExists(slotDirectory.resolve(LayoutPresetBank.SHARE_NAME_FILE));
        }
        LayoutPresetBank.zipShareSlots(share, shareZipFile());
    }

    /** A missing document can include a legacy-file read failure for the caller to report. */
    public record ImportResult(LayoutPresetBank.Data data, IOException readFailure) {
    }

    /**
     * Preserve precedence: numbered folders, ZIP, valid clipboard, legacy bank, legacy layout.
     * Clipboard access is lazy, so an available folder/ZIP never consults the platform clipboard.
     * Invalid clipboard input is ignored; invalid higher-priority files are not silently skipped.
     */
    public ImportResult importLayouts(LayoutPresetBank.Data fallback, Supplier<String> clipboard)
            throws IOException {
        LayoutPresetBank.Data imported = importFromShareFolders(fallback);
        if (imported != null) {
            return new ImportResult(imported, null);
        }
        if (Files.isRegularFile(shareZipFile())) {
            return new ImportResult(importZip(shareZipFile(), fallback), null);
        }
        String clipboardJson = clipboard.get();
        if (clipboardJson != null && !clipboardJson.isBlank()) {
            try {
                return new ImportResult(LayoutPresetBank.read(clipboardJson, codec, fallback), null);
            } catch (IOException | RuntimeException ignored) {
                // Invalid clipboard contents must not prevent importing a share file.
            }
        }
        String json = null;
        try {
            Path presetsFile = shareFolder().resolve(LayoutPresetBank.SHARE_PRESETS_FILE);
            Path layoutFile = shareFolder().resolve(LayoutPresetBank.SHARE_LAYOUT_FILE);
            if (Files.isRegularFile(presetsFile)) {
                json = BoundedLayoutInput.readJson(presetsFile);
            } else if (Files.isRegularFile(layoutFile)) {
                json = BoundedLayoutInput.readJson(layoutFile, BoundedLayoutInput.MAX_LAYOUT_BYTES);
            }
        } catch (IOException e) {
            // Historical UI behavior: unreadable legacy JSON is reported as missing, with a warning.
            return new ImportResult(null, e);
        }
        return new ImportResult(json == null || json.isBlank()
                ? null : LayoutPresetBank.read(json, codec, fallback), null);
    }

    public LayoutPresetBank.Data importZip(Path zipFile, LayoutPresetBank.Data fallback) throws IOException {
        return LayoutPresetBank.readShareZip(zipFile, codec, fallback);
    }

    private LayoutPresetBank.Data importFromShareFolders(LayoutPresetBank.Data fallback) throws IOException {
        Path share = shareFolder();
        if (!Files.isDirectory(share)) {
            return null;
        }
        LayoutPresetBank.Data data = fallback.copy();
        boolean any = false;
        for (int index = 0; index < LayoutPresetBank.SLOT_COUNT; index++) {
            Path slotDirectory = share.resolve(LayoutPresetBank.shareSlotFolder(index));
            Path layoutFile = slotDirectory.resolve(LayoutPresetBank.SHARE_LAYOUT_FILE);
            if (!Files.isRegularFile(layoutFile)) {
                continue;
            }
            any = true;
            LayoutPresetBank.SlotDocument document = LayoutPresetBank.decodeSlot(
                    BoundedLayoutInput.readJson(layoutFile, BoundedLayoutInput.MAX_LAYOUT_BYTES), codec);
            data = data.withWorkspace(index, document.workspace());
            String name = document.name();
            Path nameFile = slotDirectory.resolve(LayoutPresetBank.SHARE_NAME_FILE);
            if (name.isBlank() && Files.isRegularFile(nameFile)) {
                name = LayoutPresetBank.sanitizeName(BoundedLayoutInput.readJson(nameFile, 4096));
            }
            data = data.withName(index, name);
        }
        return any ? data : null;
    }
}
