package com.lhy.mest.api.client;

import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface CellWorkbenchButtonFactory {
    /** Called once per terminal screen; return null to add nothing. */
    @Nullable
    CellWorkbenchButton create(CellWorkbenchButtonContext context);
}
