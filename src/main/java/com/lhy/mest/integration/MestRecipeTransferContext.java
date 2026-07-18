package com.lhy.mest.integration;

/** Menu-scoped recipe-viewer target selected by the last focused recipe module. */
public final class MestRecipeTransferContext {
    public enum Target {
        CRAFTING,
        PATTERN_ENCODING
    }

    private static int containerId = -1;
    private static Target target = Target.CRAFTING;

    private MestRecipeTransferContext() {
    }

    public static synchronized void beginMenu(int newContainerId) {
        if (containerId != newContainerId) {
            containerId = newContainerId;
            target = Target.CRAFTING;
        }
    }

    public static synchronized void select(int currentContainerId, Target newTarget) {
        if (containerId != currentContainerId) {
            containerId = currentContainerId;
        }
        target = newTarget;
    }

    public static synchronized Target targetFor(int currentContainerId) {
        return containerId == currentContainerId ? target : Target.CRAFTING;
    }

    public static synchronized void clear(int closingContainerId) {
        if (containerId == closingContainerId) {
            containerId = -1;
            target = Target.CRAFTING;
        }
    }
}
