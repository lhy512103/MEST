package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Source-level wiring checks complement TrashSlotTest without constructing a client screen. */
class TrashVisibilityWiringTest {
    private static final Path JAVA = Path.of("src/main/java/com/lhy/mest");

    @Test
    void menuUsesTheServerGatedSlotAndVisibilityDoesNotClearContents() throws IOException {
        String menu = Files.readString(JAVA.resolve("terminal/MESTMenu.java"));
        assertTrue(menu.contains("new TrashSlot(inventory, slot, this::isTrashOpen)"));
        assertTrue(menu.contains("registerClientAction(ACTION_SET_TRASH_OPEN, Boolean.class, this::setTrashOpen)"));
        String setter = method(menu, "public void setTrashOpen(boolean open)");
        assertTrue(setter.contains("sendClientAction(ACTION_SET_TRASH_OPEN, open)"));
        assertTrue(setter.contains("trashOpen = open"));
        assertFalse(setter.contains("clearTrash"));
    }

    @Test
    void effectiveVisibilityRatherThanLeafPolicyControlsTheServerFlag() throws IOException {
        String screen = Files.readString(JAVA.resolve("client/MESTScreen.java"));
        String sync = method(screen, "private void syncTrashOpen()");
        assertTrue(sync.contains("trashPanel != null && dock.isEffectivelyVisible(trashPanel)"));
        assertTrue(sync.contains("getMenu().isTrashOpen() != open"));
        assertTrue(sync.contains("getMenu().setTrashOpen(open)"));
        assertFalse(sync.contains("closeTrash"));
    }

    @Test
    void presetsGroupsDismissalAndSameFrameSlotPacketsSynchronize() throws IOException {
        String screen = Files.readString(JAVA.resolve("client/MESTScreen.java"));
        for (String signature : new String[] {
                "protected void init()", "private void updateBeforeRenderTimed()",
                "private void toggleCombinedModule(int group)", "private void toggleModule(ModulePanel panel)",
                "private void dismissUnpinnedFloating(ModulePanel panel)",
                "protected void slotClicked(Slot slot, int slotIdx, int mouseButton, ClickType clickType)",
                "private void handleGridInventoryEntryMouseClick(GridInventoryEntry entry, int mouseButton, ClickType clickType)" }) {
            assertTrue(method(screen, signature).contains("syncTrashOpen()"), signature);
        }
        String hotkey = method(screen, "public boolean runHotkey(String id)");
        assertTrue(hotkey.contains("dock.cyclePreset();\n            syncTrashOpen();"));
        assertTrue(hotkey.contains("dock.selectPreset(preset);\n            syncTrashOpen();"));
        String click = method(screen, "public boolean mouseClicked(double mx, double my, int button)");
        assertTrue(click.indexOf("syncTrashOpen()") < click.indexOf("mouseClickedWithTrashSync"));
        assertTrue(click.contains("finally {\n            syncTrashOpen();"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int brace = source.indexOf('{', start);
        int depth = 1;
        int end = brace + 1;
        while (depth > 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        return source.substring(brace, end);
    }
}
