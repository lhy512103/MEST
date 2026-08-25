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
    private static boolean encodingVisible;
    private static boolean craftingVisible;

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

    public static void updateAvailability(MESTMenu menu, boolean encodingPanelVisible, boolean craftingPanelVisible) {
        Objects.requireNonNull(menu, "menu");
        updateAvailability(menu, menu.containerId, encodingPanelVisible, craftingPanelVisible);
    }

    public static Target targetFor(MESTMenu menu) {
        Objects.requireNonNull(menu, "menu");
        return targetFor(menu, menu.containerId);
    }

    public static boolean bothRecipeModulesVisible(MESTMenu menu) {
        Objects.requireNonNull(menu, "menu");
        return bothRecipeModulesVisible(menu, menu.containerId);
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
            encodingVisible = false;
            craftingVisible = false;
        }
    }

    static synchronized void updateAvailability(
            Object currentMenuIdentity,
            int currentContainerId,
            boolean encodingPanelVisible,
            boolean craftingPanelVisible) {
        Objects.requireNonNull(currentMenuIdentity, "currentMenuIdentity");
        if (menuIdentity != currentMenuIdentity || containerId != currentContainerId) {
            return;
        }
        encodingVisible = encodingPanelVisible;
        craftingVisible = craftingPanelVisible;
        if (target == Target.PATTERN_ENCODING && !encodingVisible && craftingVisible) {
            target = Target.CRAFTING;
        } else if (target == Target.CRAFTING && !craftingVisible && encodingVisible) {
            target = Target.PATTERN_ENCODING;
        } else if (!craftingVisible && encodingVisible) {
            target = Target.PATTERN_ENCODING;
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
                ? effectiveTarget()
                : Target.CRAFTING;
    }

    static synchronized boolean bothRecipeModulesVisible(Object currentMenuIdentity, int currentContainerId) {
        Objects.requireNonNull(currentMenuIdentity, "currentMenuIdentity");
        return menuIdentity == currentMenuIdentity
                && containerId == currentContainerId
                && encodingVisible
                && craftingVisible;
    }

    private static Target effectiveTarget() {
        if (target == Target.PATTERN_ENCODING && encodingVisible) {
            return Target.PATTERN_ENCODING;
        }
        if (target == Target.CRAFTING && craftingVisible) {
            return Target.CRAFTING;
        }
        if (encodingVisible) {
            return Target.PATTERN_ENCODING;
        }
        if (craftingVisible) {
            return Target.CRAFTING;
        }
        return target;
    }

    static synchronized void clear(Object closingMenuIdentity, int closingContainerId) {
        Objects.requireNonNull(closingMenuIdentity, "closingMenuIdentity");
        if (menuIdentity == closingMenuIdentity && containerId == closingContainerId) {
            menuIdentity = null;
            containerId = -1;
            target = Target.CRAFTING;
            encodingVisible = false;
            craftingVisible = false;
        }
    }
}
