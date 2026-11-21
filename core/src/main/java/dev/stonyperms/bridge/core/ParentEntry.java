package dev.stonyperms.bridge.core;

import java.util.Map;

/** Inherited parent group link stored on a user or group record. */
public record ParentEntry(
        String group,
        long expiry,
        Map<String, String> contexts) {

    public ParentEntry {
        group = group == null ? "" : group;
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
    }

    public boolean permanent() {
        return expiry <= 0;
    }
}
