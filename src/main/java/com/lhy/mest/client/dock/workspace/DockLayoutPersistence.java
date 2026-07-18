package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.util.Optional;

import com.lhy.mest.client.dock.model.DockWorkspace;

public interface DockLayoutPersistence {
    Optional<DockLayoutCodec.DecodedLayout> load() throws IOException;

    void save(DockWorkspace workspace) throws IOException;
}
