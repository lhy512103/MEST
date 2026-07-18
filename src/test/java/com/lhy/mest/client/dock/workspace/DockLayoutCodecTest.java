package com.lhy.mest.client.dock.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lhy.mest.client.dock.model.LeafNode;
import com.lhy.mest.client.dock.model.SplitNode;

class DockLayoutCodecTest {
    @Test
    void roundTripsV2WithoutLosingRootOrderOrTreeState() throws Exception {
        DockLayoutCodec codec = codec();
        var original = WorkspacePersistenceFixtures.workspace();

        String encoded = codec.encode(original);
        DockLayoutCodec.DecodedLayout decoded = codec.decode(encoded);

        assertEquals(2, decoded.sourceVersion());
        assertFalse(decoded.migrated());
        assertEquals(original, decoded.workspace());
        assertTrue(encoded.indexOf("root-a") < encoded.indexOf("root-bc"));
    }

    @Test
    void migratesLegacyPairsDeterministicallyAndPreservesFileOrder() throws Exception {
        String legacy = """
                {
                  "b+c": {
                    "x": 20,
                    "y": 30,
                    "width": 120,
                    "height": 104,
                    "visible": true,
                    "orientation": "VERTICAL",
                    "split": 40,
                    "children": ["b", "c"]
                  },
                  "a": {
                    "x": 4,
                    "y": 5,
                    "width": 80,
                    "height": 60,
                    "visible": false
                  }
                }
                """;
        DockLayoutCodec codec = codec();

        var first = codec.decode(legacy);
        var second = codec.decode(legacy);

        assertEquals(1, first.sourceVersion());
        assertTrue(first.migrated());
        assertEquals(first.workspace(), second.workspace());
        SplitNode pair = assertInstanceOf(SplitNode.class, first.workspace().roots().getFirst().content());
        assertEquals(0.4, pair.ratio(), 0.000001);
        assertEquals("b", ((LeafNode) pair.first()).moduleId());
        assertEquals("c", ((LeafNode) pair.second()).moduleId());
        LeafNode standalone = assertInstanceOf(
                LeafNode.class,
                first.workspace().roots().get(1).content());
        assertEquals("a", standalone.moduleId());
        assertFalse(standalone.visible());

        assertEquals(first.workspace(), codec.decode(codec.encode(first.workspace())).workspace());
    }

    @Test
    void appendsRegisteredModulesMissingFromLegacyDocument() throws Exception {
        String legacy = """
                {
                  "a": {"x": 4, "y": 5, "width": 80, "height": 60, "visible": true},
                  "b": {"x": 20, "y": 20, "width": 90, "height": 60, "visible": true}
                }
                """;

        var decoded = codec().decode(legacy).workspace();

        assertEquals(3, decoded.roots().size());
        assertEquals("c", ((LeafNode) decoded.roots().getLast().content()).moduleId());
        assertTrue(((LeafNode) decoded.roots().getLast().content()).visible());
    }

    @Test
    void rejectsDuplicateLegacyModulesAndUnknownV2Fields() {
        String duplicateLegacy = """
                {
                  "a+b": {
                    "x": 0, "y": 0, "width": 120, "height": 80,
                    "orientation": "HORIZONTAL", "split": 40, "children": ["a", "b"]
                  },
                  "a": {"x": 0, "y": 0, "width": 80, "height": 60}
                }
                """;
        String unknownV2Field = """
                {"version": 2, "roots": [], "extra": true}
                """;

        assertThrows(DockLayoutFormatException.class, () -> codec().decode(duplicateLegacy));
        assertThrows(DockLayoutFormatException.class, () -> codec().decode(unknownV2Field));
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThrows(
                DockLayoutFormatException.class,
                () -> codec().decode("{\"version\":3,\"roots\":[]}"));
    }

    private static DockLayoutCodec codec() {
        return new DockLayoutCodec(
                WorkspacePersistenceFixtures.catalog(),
                WorkspacePersistenceFixtures.migrationContext());
    }
}
