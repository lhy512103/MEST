package com.lhy.mest.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Regression guards for missing optional mods (#8, #10), without starting a game. */
class OptionalClientIsolationTest {
    private static final String[] OPTIONAL_OWNERS = {
            "com/extendedae_plus/", "com/moakiee/ae2lt/", "mezz/jei/", "dev/emi/"
    };

    @Test
    void commonClientEntryPointsDoNotLinkOptionalApiClasses() throws IOException {
        for (String name : new String[] {
                "com.lhy.mest.client.MestWtlibClientEvents",
                "com.lhy.mest.client.MestAddonUpgradeButtons",
                "com.lhy.mest.compat.plus.PlusScreenSupport"}) {
            String constants = new String(classBytes(name), StandardCharsets.ISO_8859_1);
            for (String owner : OPTIONAL_OWNERS) {
                assertFalse(constants.contains(owner), name + " must not link " + owner);
            }
        }
    }

    @Test
    void plusFacadeCanBeVerifiedWhenOptionalLibrariesAreUnavailable() throws Exception {
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader isolated = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                for (String prefix : OPTIONAL_OWNERS) {
                    if (name.startsWith(prefix.replace('/', '.'))) {
                        throw new ClassNotFoundException("Deliberately absent optional API: " + name);
                    }
                }
                if (name.equals("com.lhy.mest.compat.plus.PlusScreenSupport")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try {
                                byte[] bytes = classBytes(name);
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (IOException e) {
                                throw new ClassNotFoundException(name, e);
                            }
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> facade = Class.forName("com.lhy.mest.compat.plus.PlusScreenSupport", false, isolated);
        assertEquals(boolean.class, facade.getDeclaredMethod("loaded").getReturnType());
        assertTrue(facade.getDeclaredMethods().length >= 4);
    }

    @Test
    void optionalScreenAndPacketReferencesStayInTheGuardedHolder() throws IOException {
        String constants = new String(classBytes("com.lhy.mest.compat.plus.PlusScreenSupport$OptionalScreens"),
                StandardCharsets.ISO_8859_1);
        assertTrue(constants.contains("com/extendedae_plus/client/screen/ProviderSelectScreen"));
        assertTrue(constants.contains("com/extendedae_plus/network/CancelPendingPatternC2SPacket"));
    }

    private static byte[] classBytes(String name) throws IOException {
        try (var input = OptionalClientIsolationTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }
}
