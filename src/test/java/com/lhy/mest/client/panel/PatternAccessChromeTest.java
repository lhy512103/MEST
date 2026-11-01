package com.lhy.mest.client.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PatternAccessChromeTest {
    private static final String PANEL = "com.lhy.mest.client.panel.PatternAccessPanel";
    private static final String CHROME = "com.lhy.mest.client.panel.PatternAccessChrome";
    private static final String FALLBACK = "com.lhy.mest.client.panel.DefaultPatternAccessChrome";

    @Test
    void absentModFactoryAndAllFallbackIconsWorkWithoutLoadingTheOptionalAdapter() throws Exception {
        var loader = new WithoutExtendedAeLoader(getClass().getClassLoader());
        Class<?> chromeType = Class.forName(CHROME, true, loader);
        Object chrome = chromeType.getMethod("create", boolean.class).invoke(null, false);
        assertEquals(FALLBACK, chrome.getClass().getName());
        assertNull(chromeType.getMethod("createHighlight").invoke(chrome));

        var background = chromeType.getMethod("buttonBackground", boolean.class, boolean.class);
        assertNotNull(background.invoke(chrome, false, false));
        assertNotNull(background.invoke(chrome, true, false));
        assertNotNull(background.invoke(chrome, false, true));
        var searchIcon = chromeType.getMethod("searchModeIcon", boolean.class, boolean.class);
        assertNotNull(searchIcon.invoke(chrome, true, false));
        assertNotNull(searchIcon.invoke(chrome, false, true));
        assertNotNull(searchIcon.invoke(chrome, true, true));
        assertTrue(loader.optionalLoads.isEmpty(), () -> "Optional classes were resolved: " + loader.optionalLoads);
    }

    @Test
    void panelAndItsButtonHaveNoExtendedAeReferencesIncludingRenderMethodBodies() throws IOException {
        // Inspect compiled code as well as signatures: a reference used only in renderWidget would
        // otherwise stay invisible to a headless test until the first in-game render.
        for (String name : List.of(PANEL, PANEL + "$CompactPatButton", FALLBACK)) {
            String bytecode = new String(classBytes(getClass().getClassLoader(), name), StandardCharsets.ISO_8859_1);
            assertFalse(bytecode.contains("com/glodblock/"), name);
            assertFalse(bytecode.contains("com.glodblock."), name);
            assertFalse(bytecode.contains("com/lhy/mest/compat/extendedae/"), name);
        }
    }

    private static byte[] classBytes(ClassLoader loader, String name) throws IOException {
        String path = name.replace('.', '/') + ".class";
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing compiled class: " + path);
            }
            return stream.readAllBytes();
        }
    }

    /** Parent classpath contains optional compile-time mods, so hide both mod and adapter here. */
    private static final class WithoutExtendedAeLoader extends ClassLoader {
        private final List<String> optionalLoads = new ArrayList<>();

        private WithoutExtendedAeLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.glodblock.") || name.startsWith("com.lhy.mest.compat.extendedae.")) {
                optionalLoads.add(name);
                throw new ClassNotFoundException("ExtendedAE is intentionally absent: " + name);
            }
            if (!name.equals(FALLBACK) && !name.equals(CHROME) && !name.startsWith(CHROME + "$")) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    byte[] bytes = classBytes(getParent(), name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException exception) {
                    throw new ClassNotFoundException(name, exception);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
