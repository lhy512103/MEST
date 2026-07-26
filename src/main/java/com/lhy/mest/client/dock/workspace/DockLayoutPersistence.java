package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import com.lhy.mest.client.dock.model.DockWorkspace;

public interface DockLayoutPersistence {
    Optional<DockLayoutCodec.DecodedLayout> load() throws IOException;

    void save(DockWorkspace workspace) throws IOException;

    /**
     * Reports the file produced when the most recent load quarantined a corrupt document.
     *
     * <p>Persistence implementations that do not support quarantine can keep the default empty
     * result. Exposing the state at the interface boundary lets callers avoid immediately replacing
     * recovered data with defaults without depending on a concrete store implementation.</p>
     */
    default Optional<Path> lastQuarantinedPath() {
        return Optional.empty();
    }
}
