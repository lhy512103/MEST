package com.lhy.mest.api.client;

import org.jetbrains.annotations.Nullable;

import com.lhy.mest.client.dock.ModulePanel;

/** Creates a module's panel once per terminal screen; return {@code null} to leave it out. */
@FunctionalInterface
public interface MestModuleFactory {
    @Nullable
    ModulePanel create(MestModuleContext context);
}
