package com.lhy.mest.client.dock.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutPresetRecoveryTest {
    @TempDir
    Path directory;

    private final DockLayoutCodec codec = new DockLayoutCodec(
            WorkspacePersistenceFixtures.catalog(), WorkspacePersistenceFixtures.migrationContext());
    private final LayoutPresetBank.Data fallback = LayoutPresetBank.create(
            WorkspacePersistenceFixtures.workspace(), WorkspacePersistenceFixtures.workspace(), null);

    @Test
    void validPresetsKeepTheirNameAndSelectionWithoutQuarantine() throws IOException {
        Path file = directory.resolve("presets.json");
        LayoutPresetBank.save(file, fallback.withName(1, "Machines").withActive(1), codec);
        var loaded = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
        assertTrue(loaded.loaded());
        assertNull(loaded.quarantinedPath());
        assertEquals("Machines", loaded.data().names()[1]);
        assertEquals(1, loaded.data().active());
    }

    @Test
    void corruptPresetsArePreservedBeforeFallbackMayBeSaved() throws IOException {
        Path file = directory.resolve("presets.json");
        String broken = "{\"presets\":[broken but recoverable by hand";
        Files.writeString(file, broken);
        var loaded = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
        assertFalse(loaded.loaded());
        assertNotNull(loaded.quarantinedPath());
        assertFalse(Files.exists(file));
        assertEquals(broken, Files.readString(loaded.quarantinedPath()));
        LayoutPresetBank.save(file, loaded.data(), codec);
        assertEquals(broken, Files.readString(loaded.quarantinedPath()));
        assertTrue(LayoutPresetBank.loadWithRecovery(file, codec, fallback).loaded());
    }

    @Test
    void invalidUtf8PreservesTheExactOriginalBytes() throws IOException {
        Path file = directory.resolve("presets.json");
        byte[] broken = {(byte) 0xc3, (byte) 0x28};
        Files.write(file, broken);
        var loaded = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
        assertArrayEquals(broken, Files.readAllBytes(loaded.quarantinedPath()));
    }

    @Test
    void failedQuarantinePropagatesInsteadOfAuthorizingOverwrite() throws IOException {
        Path file = directory.resolve("presets.json");
        Files.writeString(file, "broken presets");
        assertThrows(IOException.class, () -> LayoutPresetBank.loadWithRecovery(file, codec, fallback,
                original -> { throw new IOException("simulated quarantine failure"); }));
        assertEquals("broken presets", Files.readString(file));
    }

    @Test
    void malformedEntriesAndUnknownVersionsAreNotSilentlyDropped() throws IOException {
        Path file = directory.resolve("presets.json");
        for (String broken : new String[] {
                "{\"version\":99,\"presets\":[{}]}",
                "{\"presets\":[]}",
                "{\"presets\":[null]}",
                "{\"presets\":[{\"layout\":17}]}"}) {
            Files.writeString(file, broken);
            var loaded = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
            assertFalse(loaded.loaded());
            assertEquals(broken, Files.readString(loaded.quarantinedPath()));
        }
    }

    @Test
    void eachRecoveryUsesADifferentQuarantineFile() throws IOException {
        Path file = directory.resolve("presets.json");
        Files.writeString(file, "first broken file");
        var first = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
        Files.writeString(file, "second broken file");
        var second = LayoutPresetBank.loadWithRecovery(file, codec, fallback);
        assertNotEquals(first.quarantinedPath(), second.quarantinedPath());
        assertEquals("first broken file", Files.readString(first.quarantinedPath()));
        assertEquals("second broken file", Files.readString(second.quarantinedPath()));
    }
}
