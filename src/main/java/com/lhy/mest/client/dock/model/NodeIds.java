package com.lhy.mest.client.dock.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Utilities for stable layout identifiers. */
public final class NodeIds {
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private NodeIds() {
    }

    public static String random() {
        return UUID.randomUUID().toString();
    }

    public static String deterministic(String namespace, String source) {
        requireValid(namespace, "namespace");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return namespace + ":" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    public static String requireValid(String id, String label) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(label + " must match " + VALID_ID.pattern());
        }
        return id;
    }
}
