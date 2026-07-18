package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

import com.lhy.mest.client.dock.model.DockWorkspace;

/** Atomic UTF-8 file persistence with an injected path for client config and tests. */
public final class AtomicFileDockLayoutStore implements DockLayoutPersistence {
    private final Path path;
    private final DockLayoutCodec codec;

    public AtomicFileDockLayoutStore(Path path, DockLayoutCodec codec) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Path path() {
        return path;
    }

    @Override
    public Optional<DockLayoutCodec.DecodedLayout> load() throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(Files.readString(path, StandardCharsets.UTF_8)));
    }

    @Override
    public void save(DockWorkspace workspace) throws IOException {
        String json = codec.encode(workspace);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("layout path has no parent: " + path);
        }
        Files.createDirectories(parent);

        String fileName = path.getFileName().toString();
        String prefix = fileName.length() >= 3 ? fileName : "layout-";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
