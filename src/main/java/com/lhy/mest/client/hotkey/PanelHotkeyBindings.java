package com.lhy.mest.client.hotkey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Panel hotkeys by module id, with the JSON form stored in {@code hotkeys.json}. */
public final class PanelHotkeyBindings {
    static final int VERSION = 1;

    private final Map<String, PanelHotkey> bindings = new LinkedHashMap<>();

    public PanelHotkey get(String moduleId) {
        return bindings.getOrDefault(moduleId, PanelHotkey.UNBOUND);
    }

    /** Binding a combo takes it away from any other panel, so one press never means two panels. */
    public void set(String moduleId, PanelHotkey hotkey) {
        if (hotkey.isBound()) {
            bindings.replaceAll((id, other) -> !id.equals(moduleId) && other.sameCombo(hotkey) ? other.cleared() : other);
        }
        if (hotkey.equals(PanelHotkey.UNBOUND)) {
            bindings.remove(moduleId);
        } else {
            bindings.put(moduleId, hotkey);
        }
    }

    /** First panel bound to the pressed combo; {@code outsideTerminal} skips terminal-only ones. */
    public Optional<String> match(int key, int modifiers, boolean outsideTerminal) {
        for (var entry : bindings.entrySet()) {
            PanelHotkey hotkey = entry.getValue();
            if (hotkey.matches(key, modifiers) && !(outsideTerminal && hotkey.terminalOnly())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject panels = new JsonObject();
        bindings.forEach((moduleId, hotkey) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", hotkey.key());
            entry.addProperty("modifiers", hotkey.modifiers());
            entry.addProperty("terminalOnly", hotkey.terminalOnly());
            panels.add(moduleId, entry);
        });
        root.add("panels", panels);
        return root;
    }

    /** Lenient: a malformed entry is skipped rather than losing every other binding. */
    public static PanelHotkeyBindings fromJson(String json) {
        PanelHotkeyBindings result = new PanelHotkeyBindings();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("panels")
                || !parsed.getAsJsonObject().get("panels").isJsonObject()) {
            return result;
        }
        for (var entry : parsed.getAsJsonObject().getAsJsonObject("panels").entrySet()) {
            try {
                JsonObject value = entry.getValue().getAsJsonObject();
                result.set(entry.getKey(), new PanelHotkey(
                        value.get("key").getAsInt(),
                        value.has("modifiers") ? value.get("modifiers").getAsInt() : 0,
                        !value.has("terminalOnly") || value.get("terminalOnly").getAsBoolean()));
            } catch (RuntimeException ignored) {
                // Keep the rest of the file.
            }
        }
        return result;
    }
}
