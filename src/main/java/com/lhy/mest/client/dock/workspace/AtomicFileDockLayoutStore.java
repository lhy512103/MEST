package com.lhy.mest.client.dock.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.lhy.mest.client.dock.model.DockWorkspace;

/** Atomic UTF-8 file persistence with an injected path for client config and tests. */
public final class AtomicFileDockLayoutStore implements DockLayoutPersistence {
    private final Path path;
    private final DockLayoutCodec codec;
    private Path lastQuarantinedPath;

    public AtomicFileDockLayoutStore(Path path, DockLayoutCodec codec) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Path path() {
        return path;
    }

    @Override
    public Optional<DockLayoutCodec.DecodedLayout> load() throws IOException {
        lastQuarantinedPath = null;
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (DockLayoutFormatException | MalformedInputException | RuntimeException e) {
            /*
             * A malformed document must never be silently replaced by defaults at the same path:
             * callers can recover with a fresh layout while the user's original remains available
             * for inspection or manual recovery.
             */
            lastQuarantinedPath = quarantineCorruptFile();
            return Optional.empty();
        }
    }

    /** Returns the most recent quarantine path produced by {@link #load()}, if any. */
    @Override
    public Optional<Path> lastQuarantinedPath() {
        return Optional.ofNullable(lastQuarantinedPath);
    }

    private Path quarantineCorruptFile() throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("layout path has no parent: " + path);
        }
        Files.createDirectories(parent);

        String fileName = path.getFileName().toString();
        Path quarantine;
        do {
            quarantine = parent.resolve(fileName
                    + ".corrupt."
                    + Instant.now().toEpochMilli()
                    + "."
                    + UUID.randomUUID());
        } while (Files.exists(quarantine));

        try {
            try {
                Files.move(path, quarantine, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(path, quarantine);
            }
        } catch (IOException e) {
            // The move is the point at which the original file is removed. If it fails, leave the
            // source untouched and propagate the error so callers do not overwrite it blindly.
            throw new IOException("failed to quarantine corrupt layout " + path, e);
        }
        return quarantine;
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
