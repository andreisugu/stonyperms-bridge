package dev.stonyperms.bridge.core;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One resolved permission node. Source ordering is carried by the snapshot:
 * user nodes come first, then groups in descending weight order.
 */
public record Node(
        String key,
        boolean value,
        Map<String, String> contexts,
        long expiry,
        String source,
        int weight,
        boolean user) {

    public Node {
        key = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
        source = source == null ? "unknown" : source;
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiry > 0 && nowEpochSeconds >= expiry;
    }

    /** Mirrors model.Node.MatchesContext in gate-stonyperms. */
    public boolean matchesContext(Map<String, String> query) {
        if (contexts.isEmpty()) {
            return true;
        }
        if (query == null || query.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : contexts.entrySet()) {
            String actual = query.get(entry.getKey());
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Mirrors model.Node.MatchesPermission (exact, root wildcard, subtree wildcard). */
    public boolean matchesPermission(String permission) {
        if (key.equals(permission)) {
            return true;
        }
        if (key.equals("*")) {
            return true;
        }
        if (key.endsWith(".*")) {
            return permission.startsWith(key.substring(0, key.length() - 1));
        }
        return false;
    }

    public boolean isWildcard() {
        return key.equals("*") || key.endsWith(".*");
    }

    public boolean active(long nowEpochSeconds, Map<String, String> query) {
        return !isExpired(nowEpochSeconds) && matchesContext(query);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Node node)) {
            return false;
        }
        return value == node.value
                && expiry == node.expiry
                && weight == node.weight
                && user == node.user
                && key.equals(node.key)
                && contexts.equals(node.contexts)
                && source.equals(node.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value, contexts, expiry, source, weight, user);
    }
}
