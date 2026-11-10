package com.lhy.mest.client.dock.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutSharingServiceTest {
    @TempDir
    Path directory;

    private final DockLayoutCodec codec = new DockLayoutCodec(
            WorkspacePersistenceFixtures.catalog(), WorkspacePersistenceFixtures.migrationContext());
    private final LayoutPresetBank.Data fallback = LayoutPresetBank.create(
            WorkspacePersistenceFixtures.workspace(), WorkspacePersistenceFixtures.workspace(), null).withActive(2);

    private LayoutSharingService service() {
        return new LayoutSharingService(directory, codec);
    }

    @Test
    void exportRoundTripsThroughFoldersAndZipWithoutReadingClipboard() throws IOException {
        var service = service();
        var snapshot = fallback.withName(0, "Machines").withName(1, "中文").withName(2, "Third");
        service.exportLayouts(snapshot);
        assertTrue(Files.isRegularFile(service.shareZipFile()));
        assertData(snapshot, service.importLayouts(fallback, () -> {
            fail("folders must win without reading the clipboard");
            return null;
        }).data());
        assertData(snapshot, service.importZip(service.shareZipFile(), fallback));
        assertArrayEquals(new String[] {"", "", ""}, fallback.names());
    }

    @Test
    void exportRemovesLegacyFilesButKeepsUnrelatedFiles() throws IOException {
        var service = service();
        Path share = service.shareFolder();
        Files.createDirectories(share.resolve("1"));
        Files.writeString(share.resolve("layout.json"), "legacy");
        Files.writeString(share.resolve("presets.json"), "legacy");
        Files.writeString(share.resolve("1/name.txt"), "old name");
        Files.writeString(share.resolve("notes.txt"), "keep");
        service.exportLayouts(fallback);
        assertFalse(Files.exists(share.resolve("layout.json")));
        assertFalse(Files.exists(share.resolve("presets.json")));
        assertFalse(Files.exists(share.resolve("1/name.txt")));
        assertEquals("keep", Files.readString(share.resolve("notes.txt")));
    }

    @Test
    void partialFolderWinsOverZipAndUsesLegacyNameWithoutChangingOtherSlots() throws IOException {
        var service = service();
        Path slot = service.shareFolder().resolve("2");
        Files.createDirectories(slot);
        Files.writeString(slot.resolve("layout.json"), codec.encode(fallback.workspaces()[1]));
        Files.writeString(slot.resolve("name.txt"), " Legacy\nName ");
        Files.writeString(service.shareZipFile(), "broken lower-priority zip");
        var imported = service.importLayouts(fallback, () -> { throw new AssertionError("clipboard read"); });
        assertNull(imported.readFailure());
        assertData(fallback.withName(1, "LegacyName"), imported.data());
        assertEquals("", fallback.names()[1]);
    }

    @Test
    void embeddedNameWinsOverOversizedLegacyNameFile() throws IOException {
        Path slot = service().shareFolder().resolve("1");
        Files.createDirectories(slot);
        Files.writeString(slot.resolve("layout.json"),
                LayoutPresetBank.encodeSlot(fallback.workspaces()[0], "Embedded", codec));
        Files.writeString(slot.resolve("name.txt"), "x".repeat(4097));
        assertEquals("Embedded", service().importLayouts(fallback, () -> null).data().names()[0]);
    }

    @Test
    void zipWinsOverClipboardAndLegacyJsonWithoutExtractingFiles() throws IOException {
        var source = new LayoutSharingService(directory.resolve("source"), codec);
        source.exportLayouts(fallback.withName(0, "ZIP"));
        var service = service();
        Files.createDirectories(service.shareFolder());
        Files.copy(source.shareZipFile(), service.shareZipFile());
        Files.writeString(service.shareFolder().resolve("presets.json"), "broken");
        assertEquals("ZIP", service.importLayouts(fallback, () -> {
            throw new AssertionError("ZIP must precede clipboard");
        }).data().names()[0]);
        assertFalse(Files.exists(service.shareFolder().resolve("1")));
    }

    @Test
    void validClipboardWinsAndInvalidClipboardFallsBackToBankBeforeSingleLayout() throws IOException {
        var service = service();
        Files.createDirectories(service.shareFolder());
        Path bank = service.shareFolder().resolve("presets.json");
        LayoutPresetBank.save(bank, fallback.withName(0, "Disk").withActive(1), codec);
        String clipboard = Files.readString(bank);
        LayoutPresetBank.save(bank, fallback.withName(0, "Changed disk"), codec);
        Files.writeString(service.shareFolder().resolve("layout.json"), "broken lower priority");
        var fromClipboard = service.importLayouts(fallback, () -> clipboard).data();
        assertEquals("Disk", fromClipboard.names()[0]);
        assertEquals(1, fromClipboard.active());
        assertEquals("Changed disk", service.importLayouts(fallback, () -> "not JSON").data().names()[0]);
    }

    @Test
    void legacySingleLayoutAndMissingInputKeepCompatibility() throws IOException {
        var service = service();
        assertNull(service.importLayouts(fallback, () -> "invalid clipboard").data());
        Files.createDirectories(service.shareFolder());
        Files.writeString(service.shareFolder().resolve("layout.json"), codec.encode(fallback.workspaces()[2]));
        assertData(fallback, service.importLayouts(fallback, () -> null).data());
    }

    @Test
    void invalidFolderAndZipDoNotFallThroughOrOverwriteBackup() throws IOException {
        var service = service();
        service.backup(fallback.withName(0, "Last good"));
        byte[] previous = Files.readAllBytes(backupFile());
        Path slot = service.shareFolder().resolve("1");
        Files.createDirectories(slot);
        Files.writeString(slot.resolve("layout.json"), "broken");
        assertThrows(DockLayoutFormatException.class,
                () -> service.importLayouts(fallback, () -> codec.encode(fallback.workspaces()[0])));
        Path badZip = directory.resolve("invalid.zip");
        Files.writeString(badZip, "broken");
        assertThrows(DockLayoutFormatException.class, () -> service.importZip(badZip, fallback));
        assertArrayEquals(previous, Files.readAllBytes(backupFile()));
    }

    @Test
    void backupRoundTripsAndFailedSerializationDoesNotReplacePreviousBackup() throws IOException {
        var service = service();
        assertFalse(service.hasBackup());
        var saved = fallback.withName(1, "Last good");
        service.backup(saved);
        assertTrue(service.hasBackup());
        assertData(saved, service.restoreBackup(fallback));
        byte[] previous = Files.readAllBytes(backupFile());
        var invalid = fallback.copy();
        invalid.workspaces()[1] = null; // Fail after at least one slot has been serialized.
        assertThrows(RuntimeException.class, () -> service.backup(invalid));
        assertArrayEquals(previous, Files.readAllBytes(backupFile()));
        assertData(saved, service.restoreBackup(fallback));
    }

    @Test
    void backupFilesystemFailurePreservesExistingTargetAndCleansTemporaryFile() throws IOException {
        Path target = backupFile();
        Files.createDirectories(target);
        Path marker = target.resolve("existing-data");
        Files.writeString(marker, "keep");
        assertThrows(IOException.class, () -> service().backup(fallback));
        assertEquals("keep", Files.readString(marker));
        try (var children = Files.list(target.getParent())) {
            assertEquals(1, children.count(), "failed atomic replacement must not leave a temporary file");
        }
    }

    @Test
    void oversizedFolderLayoutAndLegacyNameAreRejectedWithoutTouchingBackup() throws IOException {
        var service = service();
        service.backup(fallback);
        byte[] previous = Files.readAllBytes(backupFile());
        Path slot = service.shareFolder().resolve("1");
        Files.createDirectories(slot);
        Files.writeString(slot.resolve("layout.json"), "x".repeat(BoundedLayoutInput.MAX_LAYOUT_BYTES + 1));
        assertThrows(DockLayoutFormatException.class, () -> service.importLayouts(fallback, () -> null));
        Files.writeString(slot.resolve("layout.json"), codec.encode(fallback.workspaces()[0]));
        Files.writeString(slot.resolve("name.txt"), "x".repeat(4097));
        assertThrows(DockLayoutFormatException.class, () -> service.importLayouts(fallback, () -> null));
        assertArrayEquals(previous, Files.readAllBytes(backupFile()));
    }

    @Test
    void oversizedUnknownZipEntryIsRejectedThroughService() throws IOException {
        Path zip = directory.resolve("oversized.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("ignored.txt"));
            byte[] bytes = new byte[8192];
            int remaining = BoundedLayoutInput.MAX_ZIP_ENTRY_BYTES + 1;
            while (remaining > 0) {
                int count = Math.min(bytes.length, remaining);
                out.write(bytes, 0, count);
                remaining -= count;
            }
            out.closeEntry();
        }
        assertThrows(DockLayoutFormatException.class, () -> service().importZip(zip, fallback));
        assertFalse(service().hasBackup());
    }

    @Test
    void oversizedLegacyJsonIsMissingWithDiagnosticAndDoesNotFallThrough() throws IOException {
        var service = service();
        Files.createDirectories(service.shareFolder());
        Files.writeString(service.shareFolder().resolve("presets.json"),
                "x".repeat(BoundedLayoutInput.MAX_JSON_BYTES + 1));
        Files.writeString(service.shareFolder().resolve("layout.json"), codec.encode(fallback.workspaces()[0]));
        var result = service.importLayouts(fallback, () -> null);
        assertNull(result.data());
        assertInstanceOf(DockLayoutFormatException.class, result.readFailure());
    }

    @Test
    void oversizedBackupIsRejectedWithoutModifyingIt() throws IOException {
        Files.createDirectories(backupFile().getParent());
        Files.writeString(backupFile(), "x".repeat(BoundedLayoutInput.MAX_JSON_BYTES + 1));
        assertThrows(DockLayoutFormatException.class, () -> service().restoreBackup(fallback));
        assertEquals(BoundedLayoutInput.MAX_JSON_BYTES + 1L, Files.size(backupFile()));
    }

    @Test
    void sharingAndBackupNeverRewriteTheLivePresetFile() throws IOException {
        Path livePresets = directory.resolve(LayoutPresetBank.PRESETS_FILE);
        String damaged = "recoverable damaged presets; do not replace";
        Files.writeString(livePresets, damaged);
        var service = service();
        service.exportLayouts(fallback);
        service.importLayouts(fallback, () -> null);
        service.backup(fallback);
        service.restoreBackup(fallback);
        assertEquals(damaged, Files.readString(livePresets));
    }

    private Path backupFile() {
        return directory.resolve("backup/presets.json");
    }

    private static void assertData(LayoutPresetBank.Data expected, LayoutPresetBank.Data actual) {
        assertNotNull(actual);
        assertEquals(expected.active(), actual.active());
        assertArrayEquals(expected.names(), actual.names());
        assertArrayEquals(expected.workspaces(), actual.workspaces());
    }
}
