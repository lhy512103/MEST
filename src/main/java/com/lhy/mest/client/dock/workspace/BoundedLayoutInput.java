package com.lhy.mest.client.dock.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Resource limits shared by disk persistence and user-supplied layout imports. */
public final class BoundedLayoutInput {
    public static final int MAX_LAYOUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_JSON_BYTES = 3 * MAX_LAYOUT_BYTES;
    public static final int MAX_ZIP_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRY_BYTES = MAX_LAYOUT_BYTES;
    public static final int MAX_ZIP_TOTAL_BYTES = MAX_JSON_BYTES;
    public static final int MAX_ZIP_ENTRIES = 64;
    // Includes wrappers around the existing 64-level layout tree, including a preset bank.
    public static final int MAX_JSON_DEPTH = 128;
    public static final int MAX_JSON_TOKENS = 300_000;

    private BoundedLayoutInput() {
    }

    /** Reads a single layout or three-slot bank without trusting a prior Files.size check. */
    public static String readJson(Path path) throws IOException {
        return readJson(path, MAX_JSON_BYTES);
    }

    /** Reads at most maxBytes UTF-8 bytes; malformed UTF-8 is rejected, not replaced. */
    public static String readJson(Path path, int maxBytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(path, maxBytes))).toString();
    }

    static byte[] readBytes(Path path, int maxBytes) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return readBytes(input, maxBytes);
        }
    }

    /** Does not close the stream. Reads only one extra byte to distinguish exact-limit EOF. */
    static byte[] readBytes(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("negative input limit");
        }
        var output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, (long) maxBytes - total + 1));
            if (count < 0) {
                return output.toByteArray();
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    return output.toByteArray();
                }
                buffer[0] = (byte) single;
                count = 1;
            }
            if (count > maxBytes - total) {
                throw new DockLayoutFormatException("layout input exceeds byte limit " + maxBytes);
            }
            output.write(buffer, 0, count);
            total += count;
        }
    }

    /** Checks every token, including unknown fields, before Gson allocates a JSON tree. */
    static void checkJson(String json, int maxBytes) throws DockLayoutFormatException {
        if (json == null || json.length() > maxBytes) {
            throw new DockLayoutFormatException("layout JSON exceeds byte limit " + maxBytes);
        }
        long bytes = 0;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch < 0x80) {
                bytes++;
            } else if (ch < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch) && i + 1 < json.length()
                    && Character.isLowSurrogate(json.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
            if (bytes > maxBytes) {
                throw new DockLayoutFormatException("layout JSON exceeds byte limit " + maxBytes);
            }
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            // Match JsonParser's historical leniency; this pass limits resources, not the schema.
            reader.setLenient(true);
            int depth = 0;
            int tokens = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++tokens > MAX_JSON_TOKENS) {
                    throw new DockLayoutFormatException("layout JSON exceeds token limit " + MAX_JSON_TOKENS);
                }
                switch (reader.peek()) {
                    case BEGIN_ARRAY, BEGIN_OBJECT -> {
                        if (++depth > MAX_JSON_DEPTH) {
                            throw new DockLayoutFormatException("layout JSON exceeds depth limit " + MAX_JSON_DEPTH);
                        }
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.beginArray();
                        } else {
                            reader.beginObject();
                        }
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> reader.nextName();
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> throw new DockLayoutFormatException("invalid layout JSON token");
                }
            }
        } catch (DockLayoutFormatException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DockLayoutFormatException("invalid layout JSON", e);
        }
    }
}
