package dev.stonyperms.bridge.core;

import java.util.Locale;
import java.util.Map;

/** Inherited parent group link stored on a user or group record. */
public record ParentEntry(
        String group,
        long expiry,
        Map<String, String> contexts) {

    public ParentEntry {
        group = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
    }

    public boolean permanent() {
        return expiry <= 0;
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiry > 0 && nowEpochSeconds >= expiry;
    }

    public boolean matchesContext(Map<String, String> query) {
        return MetaEntry.matchesContextMap(contexts, query);
    }
}
