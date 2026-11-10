package com.lhy.mest.client.dock.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.DockWorkspace;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.WorkspaceValidator;

class BoundedLayoutInputTest {
    @TempDir
    Path directory;

    @Test
    void boundedReaderAcceptsExactLimitAndConsumesOnlyOneExtraByteOnOverflow() throws Exception {
        Path file = directory.resolve("utf8.json");
        Files.writeString(file, "中文", StandardCharsets.UTF_8);
        assertEquals("中文", BoundedLayoutInput.readJson(file, 6));
        assertThrows(DockLayoutFormatException.class, () -> BoundedLayoutInput.readJson(file, 5));

        class EndlessInput extends InputStream {
            int consumed;

            @Override
            public int read() {
                consumed++;
                return ' ';
            }
        }
        var endless = new EndlessInput();
        assertThrows(DockLayoutFormatException.class, () -> BoundedLayoutInput.readBytes(endless, 23));
        assertEquals(24, endless.consumed, "the stream itself is bounded, not just its advertised size");
    }

    @Test
    void boundedReaderRejectsMalformedUtf8() throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThrows(java.nio.charset.MalformedInputException.class, () -> BoundedLayoutInput.readJson(file));
    }

    @Test
    void deepUnknownArraysAreRejectedBeforeAnyTreeParsingAtEveryJsonEntryPoint() {
        String json = "{\"unknown\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}";
        assertDepthFailure(() -> codec().decode(json));
        assertDepthFailure(() -> LayoutPresetBank.decodeSlot(json, codec()));
        assertDepthFailure(() -> LayoutPresetBank.read(json, codec(), fallback()));
    }

    @Test
    void wideUnknownArraysAreRejectedByTokenBudget() {
        String json = "{\"unknown\":[" + "null,".repeat(BoundedLayoutInput.MAX_JSON_TOKENS) + "null]}";
        var failure = assertThrows(DockLayoutFormatException.class,
                () -> LayoutPresetBank.read(json, codec(), fallback()));
        assertTrue(failure.getMessage().contains("token limit"));
        var codecFailure = assertThrows(DockLayoutFormatException.class, () -> codec().decode(json));
        assertTrue(codecFailure.getMessage().contains("token limit"));
    }

    @Test
    void jsonByteBudgetCountsUtf8AndChecksBeforeParsing() {
        String json = "{\"name\":\"" + "中".repeat(BoundedLayoutInput.MAX_LAYOUT_BYTES / 3) + "\"}";
        var failure = assertThrows(DockLayoutFormatException.class, () -> codec().decode(json));
        assertTrue(failure.getMessage().contains("byte limit"));
        String largeBank = " ".repeat(BoundedLayoutInput.MAX_JSON_BYTES + 1);
        assertThrows(DockLayoutFormatException.class,
                () -> LayoutPresetBank.read(largeBank, codec(), fallback()));
    }

    @Test
    void jsonScannerHandlesEscapesBracesAndArraysInsideNames() throws Exception {
        String name = "a\"[{}]\\中文";
        String json = LayoutPresetBank.encodeSlot(WorkspacePersistenceFixtures.workspace(), name, codec());
        var decoded = LayoutPresetBank.decodeSlot(json, codec());
        assertEquals(name, decoded.name());
        assertEquals(WorkspacePersistenceFixtures.workspace(), decoded.workspace());
    }

    @Test
    void oversizedDiskLayoutIsQuarantinedAndBankImportReadIsBounded() throws Exception {
        Path file = directory.resolve("large-layout.json");
        try (OutputStream out = Files.newOutputStream(file)) {
            writeRepeated(out, BoundedLayoutInput.MAX_LAYOUT_BYTES + 1, (byte) ' ');
        }
        var store = new AtomicFileDockLayoutStore(file, codec());
        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(file));
        assertEquals(BoundedLayoutInput.MAX_LAYOUT_BYTES + 1,
                Files.size(store.lastQuarantinedPath().orElseThrow()));

        Path bank = directory.resolve("large-bank.json");
        try (OutputStream out = Files.newOutputStream(bank)) {
            writeRepeated(out, BoundedLayoutInput.MAX_JSON_BYTES + 1, (byte) ' ');
        }
        assertThrows(DockLayoutFormatException.class, () -> BoundedLayoutInput.readJson(bank));
    }

    @Test
    void accepts1024NodesAndThreeFullPresetsInJsonAndZip() throws Exception {
        var roots = new ArrayList<FloatingRoot>();
        String[] modules = new String[WorkspaceValidator.MAX_NODES];
        for (int i = 0; i < modules.length; i++) {
            modules[i] = "module-" + i;
            roots.add(new FloatingRoot("root-" + i, new DockRect(i, i, 80, 60),
                    new LeafNode("leaf-" + i, modules[i], true)));
        }
        var codec = new DockLayoutCodec(WorkspacePersistenceFixtures.catalog(modules),
                WorkspacePersistenceFixtures.migrationContext());
        var workspace = new DockWorkspace(roots);
        // Normalize default policy maps before comparing persistence round trips.
        workspace = codec.decode(codec.encode(workspace)).workspace();
        var bank = new LayoutPresetBank.Data(2, new String[] {"一", "二", "三"},
                new DockWorkspace[] {workspace, workspace, workspace});
        Path file = directory.resolve("presets.json");
        LayoutPresetBank.save(file, bank, codec);
        var decoded = LayoutPresetBank.read(BoundedLayoutInput.readJson(file), codec, bank);
        assertEquals(2, decoded.active());
        for (int i = 0; i < 3; i++) {
            assertEquals(workspace, decoded.workspaces()[i]);
            assertEquals(bank.names()[i], decoded.names()[i]);
        }
        Path share = directory.resolve("share");
        for (int i = 0; i < 3; i++) {
            Path slot = share.resolve(LayoutPresetBank.shareSlotFolder(i));
            Files.createDirectories(slot);
            Files.writeString(slot.resolve("layout.json"), LayoutPresetBank.encodeSlot(workspace, bank.names()[i], codec));
        }
        Path zip = directory.resolve("large-valid.zip");
        LayoutPresetBank.zipShareSlots(share, zip);
        var imported = LayoutPresetBank.readShareZip(zip, codec, bank);
        for (int i = 0; i < 3; i++) {
            assertEquals(workspace, imported.workspaces()[i]);
            assertEquals(bank.names()[i], imported.names()[i]);
        }
    }

    @Test
    void sharePrefixLegacyNamesAndSmallUnknownEntriesRemainCompatible() throws Exception {
        Path zip = directory.resolve("share.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            put(out, "readme.txt", "ignored");
            put(out, "./share/1/layout.json",
                    LayoutPresetBank.encodeSlot(WorkspacePersistenceFixtures.workspace(), "内嵌名称", codec()));
            put(out, "share/2/layout.json", codec().encode(WorkspacePersistenceFixtures.workspace()));
            put(out, "share/2/name.txt", "  Legacy name  \n");
        }
        var imported = LayoutPresetBank.readShareZip(zip, codec(), fallback());
        assertEquals("内嵌名称", imported.names()[0]);
        assertEquals("Legacy name", imported.names()[1]);
        assertEquals(WorkspacePersistenceFixtures.workspace(), imported.workspaces()[0]);
    }

    @Test
    void rejectsOversizedEntriesEvenWhenIgnoredOrMarkedAsDirectories() throws Exception {
        for (String name : List.of("1/layout.json", "1/name.txt", "1/unknown.bin", "unknown.bin",
                "../unsafe.bin", "folder/")) {
            Path zip = directory.resolve("oversized-" + Math.abs(name.hashCode()) + ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry(name));
                writeRepeated(out, BoundedLayoutInput.MAX_ZIP_ENTRY_BYTES + 1, (byte) ' ');
                out.closeEntry();
                put(out, "2/layout.json", codec().encode(WorkspacePersistenceFixtures.workspace()));
            }
            assertThrows(DockLayoutFormatException.class,
                    () -> LayoutPresetBank.readShareZip(zip, codec(), fallback()), name);
        }
    }

    @Test
    void rejectsCumulativeInflationAcrossIndividuallyValidUnknownEntries() throws Exception {
        Path zip = directory.resolve("total.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 3; i++) {
                out.putNextEntry(new ZipEntry("ignored-" + i));
                writeRepeated(out, BoundedLayoutInput.MAX_ZIP_ENTRY_BYTES, (byte) ' ');
                out.closeEntry();
            }
            put(out, "1/layout.json", codec().encode(WorkspacePersistenceFixtures.workspace()));
        }
        assertThrows(DockLayoutFormatException.class,
                () -> LayoutPresetBank.readShareZip(zip, codec(), fallback()));
    }

    @Test
    void countsEmptyUnknownEntriesAndAcceptsTheExactEntryLimit() throws Exception {
        for (int count : new int[] {BoundedLayoutInput.MAX_ZIP_ENTRIES, BoundedLayoutInput.MAX_ZIP_ENTRIES + 1}) {
            Path zip = directory.resolve("entries-" + count + ".zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                put(out, "1/layout.json", codec().encode(WorkspacePersistenceFixtures.workspace()));
                for (int i = 1; i < count; i++) {
                    put(out, "ignored-" + i + "/", "");
                }
            }
            if (count == BoundedLayoutInput.MAX_ZIP_ENTRIES) {
                assertEquals(WorkspacePersistenceFixtures.workspace(),
                        LayoutPresetBank.readShareZip(zip, codec(), fallback()).workspaces()[0]);
            } else {
                var failure = assertThrows(DockLayoutFormatException.class,
                        () -> LayoutPresetBank.readShareZip(zip, codec(), fallback()));
                assertTrue(failure.getMessage().contains("entry count limit"));
            }
        }
    }

    @Test
    void compressedFileLimitAlsoAppliesToTrailingData() throws Exception {
        Path zip = directory.resolve("compressed-limit.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "1/layout.json", codec().encode(WorkspacePersistenceFixtures.workspace()));
        }
        try (OutputStream out = Files.newOutputStream(zip, java.nio.file.StandardOpenOption.APPEND)) {
            writeRepeated(out, BoundedLayoutInput.MAX_ZIP_BYTES + 1, (byte) 0);
        }
        var failure = assertThrows(DockLayoutFormatException.class,
                () -> LayoutPresetBank.readShareZip(zip, codec(), fallback()));
        assertTrue(failure.getMessage().contains("byte limit"));
    }

    private static void assertDepthFailure(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DockLayoutFormatException.class, action);
        assertTrue(failure.getMessage().contains("depth limit"));
    }

    private static DockLayoutCodec codec() {
        return new DockLayoutCodec(WorkspacePersistenceFixtures.catalog(),
                WorkspacePersistenceFixtures.migrationContext());
    }

    private static LayoutPresetBank.Data fallback() {
        var workspace = WorkspacePersistenceFixtures.workspace();
        return LayoutPresetBank.create(workspace, workspace, new String[] {"One", "Two", "Three"});
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeRepeated(OutputStream output, int bytes, byte value) throws Exception {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, value);
        for (int remaining = bytes; remaining > 0;) {
            int count = Math.min(chunk.length, remaining);
            output.write(chunk, 0, count);
            remaining -= count;
        }
    }
}
