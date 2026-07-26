package com.lhy.mest.integration;

import java.util.Objects;

import com.lhy.mest.terminal.MESTMenu;

/** Menu-scoped recipe-viewer target selected by the last focused recipe module. */
public final class MestRecipeTransferContext {
    public enum Target {
        CRAFTING,
        PATTERN_ENCODING
    }

    private static final Object LEGACY_IDENTITY = new Object();

    private static Object menuIdentity;
    private static int containerId = -1;
    private static Target target = Target.CRAFTING;

    private MestRecipeTransferContext() {
    }

    public static void beginMenu(MESTMenu menu) {
        Objects.requireNonNull(menu, "menu");
        beginMenu(menu, menu.containerId);
    }

    public static void select(MESTMenu menu, Target newTarget) {
        Objects.requireNonNull(menu, "menu");
        select(menu, menu.containerId, newTarget);
    }

    public static Target targetFor(MESTMenu menu) {
        Objects.requireNonNull(menu, "menu");
        return targetFor(menu, menu.containerId);
    }

    public static void clear(MESTMenu menu) {
        Objects.requireNonNull(menu, "menu");
        clear(menu, menu.containerId);
    }

    /**
     * @deprecated Container IDs are reused. Prefer {@link #beginMenu(MESTMenu)} so stale screens
     *             cannot affect a newer menu with the same ID.
     */
    @Deprecated(forRemoval = true)
    public static synchronized void beginMenu(int newContainerId) {
        beginMenu(LEGACY_IDENTITY, newContainerId);
    }

    /**
     * @deprecated Container IDs are reused. Prefer {@link #select(MESTMenu, Target)}.
     */
    @Deprecated(forRemoval = true)
    public static synchronized void select(int currentContainerId, Target newTarget) {
        if (menuIdentity != LEGACY_IDENTITY || containerId != currentContainerId) {
            beginMenu(LEGACY_IDENTITY, currentContainerId);
        }
        select(LEGACY_IDENTITY, currentContainerId, newTarget);
    }

    /**
     * @deprecated Container IDs are reused. Prefer {@link #targetFor(MESTMenu)}.
     */
    @Deprecated(forRemoval = true)
    public static synchronized Target targetFor(int currentContainerId) {
        return targetFor(LEGACY_IDENTITY, currentContainerId);
    }

    /**
     * @deprecated Container IDs are reused. Prefer {@link #clear(MESTMenu)}.
     */
    @Deprecated(forRemoval = true)
    public static synchronized void clear(int closingContainerId) {
        clear(LEGACY_IDENTITY, closingContainerId);
    }

    static synchronized void beginMenu(Object newMenuIdentity, int newContainerId) {
        Objects.requireNonNull(newMenuIdentity, "newMenuIdentity");
        if (menuIdentity != newMenuIdentity || containerId != newContainerId) {
            menuIdentity = newMenuIdentity;
            containerId = newContainerId;
            target = Target.CRAFTING;
        }
    }

    static synchronized void select(Object currentMenuIdentity, int currentContainerId, Target newTarget) {
        Objects.requireNonNull(currentMenuIdentity, "currentMenuIdentity");
        Objects.requireNonNull(newTarget, "newTarget");
        if (menuIdentity == currentMenuIdentity && containerId == currentContainerId) {
            target = newTarget;
        }
    }

    static synchronized Target targetFor(Object currentMenuIdentity, int currentContainerId) {
        Objects.requireNonNull(currentMenuIdentity, "currentMenuIdentity");
        return menuIdentity == currentMenuIdentity && containerId == currentContainerId
                ? target
                : Target.CRAFTING;
    }

    static synchronized void clear(Object closingMenuIdentity, int closingContainerId) {
        Objects.requireNonNull(closingMenuIdentity, "closingMenuIdentity");
        if (menuIdentity == closingMenuIdentity && containerId == closingContainerId) {
            menuIdentity = null;
            containerId = -1;
            target = Target.CRAFTING;
        }
    }
}
