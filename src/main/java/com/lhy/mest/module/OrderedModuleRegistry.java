package com.lhy.mest.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Add-on module entries by id. Iteration order is the id's natural order, so the client and the
 * server build identical lists no matter in which order mods receive the registration event.
 */
public final class OrderedModuleRegistry<T> {
    /** Matches the layout id rules; a namespace such as {@code mymod:panel} is recommended. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._:/-]{0,127}");
    private static final String RESERVED_PREFIX = "action:";

    private final Set<String> reserved;
    private final Map<String, T> entries = new TreeMap<>();
    private boolean frozen;

    public OrderedModuleRegistry(Set<String> reserved) {
        this.reserved = Set.copyOf(reserved);
    }

    public void register(String id, T value) {
        if (frozen) {
            throw new IllegalStateException("MEST module registration is closed; register during the event");
        }
        if (id == null || !VALID_ID.matcher(id).matches() || id.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException("invalid MEST module id: " + id);
        }
        if (reserved.contains(id)) {
            throw new IllegalArgumentException("MEST module id is used by a built-in module: " + id);
        }
        if (value == null) {
            throw new NullPointerException("module " + id);
        }
        if (entries.putIfAbsent(id, value) != null) {
            throw new IllegalArgumentException("MEST module registered twice: " + id);
        }
    }

    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public List<Map.Entry<String, T>> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.entrySet()));
    }
}
