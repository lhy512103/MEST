package com.lhy.mest.api;

/**
 * Adds a module's slots to the terminal menu. Runs once per menu on the server and once on the
 * client, and must add the same slots in the same order on both sides.
 */
@FunctionalInterface
public interface MestModuleSlotProvider {
    void addSlots(MestModuleSlotContext context);
}
