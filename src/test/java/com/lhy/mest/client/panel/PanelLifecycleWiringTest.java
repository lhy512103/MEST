package com.lhy.mest.client.panel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Complements the pure controller tests by checking the host's lifecycle wiring. */
class PanelLifecycleWiringTest {
    private static String screen() throws Exception {
        return Files.readString(Path.of("src/main/java/com/lhy/mest/client/MESTScreen.java"));
    }

    @Test
    void dispatchesClientTickOnlyFromContainerTickNotRender() throws Exception {
        String source = screen();
        int begin = source.indexOf("public void containerTick()");
        int end = source.indexOf("private Component layoutLockMessage", begin);
        assertTrue(begin > 0 && end > begin);
        assertTrue(source.substring(begin, end).contains("panelLifecycle.clientTick();"));
        assertEquals(1, source.split("panelLifecycle\\.clientTick\\(\\);", -1).length - 1);
    }

    @Test
    void synchronizesEffectiveVisibilityAndFocusesOnlyOnMouseSelection() throws Exception {
        String source = screen();
        assertTrue(source.contains("panelLifecycle.attach(panel, dock.isEffectivelyVisible(panel))"));
        assertTrue(source.contains("panelLifecycle.setVisible(panel, dock.isEffectivelyVisible(panel))"));
        int begin = source.indexOf("private boolean mouseClickedInWorkspace");
        int end = source.indexOf("protected boolean hasClickedOutside", begin);
        assertTrue(source.substring(begin, end).contains("panelLifecycle.focusFromMouse(target)"));
        assertEquals(1, source.split("panelLifecycle\\.focusFromMouse", -1).length - 1);
    }

    @Test
    void removalStillRunsTheOriginalCleanupWhenAnExtensionFails() throws Exception {
        String source = screen();
        int begin = source.indexOf("public void removed()");
        int end = source.indexOf("private void removePanelScreenState", begin);
        String removal = source.substring(begin, end);
        assertTrue(removal.contains("panelLifecycle.close();"));
        assertTrue(removal.contains("finally"));
        assertTrue(removal.contains("removePanelScreenState();"));
        assertTrue(source.substring(end).contains("super.removed();"));
    }
}
