package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lhy.mest.network.PatternCacheActionPacket.Action;

class PatternCacheActionsTest {
    @Test
    void everyScaleActionKeepsItsFactorAndDirection() {
        var service = new RecordingActions();
        for (int factor : new int[] {2, 3, 5, 8, 16, 32}) {
            service.apply(Action.valueOf("TIMES_" + factor), false);
            service.apply(Action.valueOf("DIVIDE_" + factor), true);
            assertEquals(List.of("scale:" + factor + ":false", "scale:" + factor + ":true"), service.calls);
            service.calls.clear();
        }
    }

    @Test
    void ratioAndOutputActionsDispatchExactlyOnce() {
        var service = new RecordingActions();
        service.apply(Action.EQUALS_1, false);
        service.apply(Action.SWAP, true);
        assertEquals(List.of("ratio", "rotate"), service.calls);
    }

    @Test
    void substitutionActionsPreserveBothTheSelectedFlagAndValue() {
        var service = new RecordingActions();
        for (boolean value : new boolean[] {false, true}) {
            service.apply(Action.ITEM_SUBSTITUTION, value);
            service.apply(Action.FLUID_SUBSTITUTION, value);
        }
        assertEquals(List.of("substitute:true:false", "substitute:false:false",
                "substitute:true:true", "substitute:false:true"), service.calls);
    }

    @Test
    void invalidActionDoesNotPartiallyDispatch() {
        var service = new RecordingActions();
        assertThrows(NullPointerException.class, () -> service.apply(null, false));
        assertTrue(service.calls.isEmpty());
    }

    @Test
    void menuRetainsOneBroadcastAfterDelegation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/lhy/mest/terminal/MESTMenu.java"));
        String tail = source.substring(source.indexOf("public void handlePatternCacheAction"));
        assertTrue(tail.contains("new PatternCacheActions(getPatternCacheSlots(), getPlayer().level()).apply(action, value)"));
        assertEquals(1, tail.split("broadcastChanges\\(\\);", -1).length - 1);
    }

    private static final class RecordingActions extends PatternCacheActions {
        private final List<String> calls = new ArrayList<>();

        private RecordingActions() {
            super(List.of(), null); // Dispatch is tested without constructing a world or reimplementing transforms.
        }

        @Override void modifyProcessingPatterns(int scale, boolean divide) {
            calls.add("scale:" + scale + ":" + divide);
        }
        @Override void restoreProcessingRatios() { calls.add("ratio"); }
        @Override void rotateProcessingOutputs() { calls.add("rotate"); }
        @Override void changeCraftingSubstitution(boolean item, boolean value) {
            calls.add("substitute:" + item + ":" + value);
        }
    }
}
