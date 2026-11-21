package dev.stonyperms.bridge.core;

import java.util.Map;

/** Prioritized prefix or suffix entry with optional expiry and contexts. */
public record PriorityMeta(
        int priority,
        String value,
        long expiry,
        Map<String, String> contexts) {

    public PriorityMeta {
        value = value == null ? "" : value;
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
    }

    public boolean permanent() {
        return expiry <= 0;
    }
}
