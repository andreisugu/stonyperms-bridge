package dev.stonyperms.bridge.core;

import java.util.Locale;
import java.util.Map;

/** Prioritized, contextual, and optionally temporary metadata entry mirroring model.MetaEntry. */
public record MetaEntry(
        String key,
        String value,
        int priority,
        long expiry,
        Map<String, String> contexts) {

    public MetaEntry {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value;
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : normalizeContexts(contexts);
    }

    public boolean permanent() {
        return expiry <= 0;
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiry > 0 && nowEpochSeconds >= expiry;
    }

    public boolean matchesContext(Map<String, String> query) {
        return matchesContextMap(contexts, query);
    }

    public static boolean matchesContextMap(Map<String, String> entryContexts, Map<String, String> queryContexts) {
        if (entryContexts == null || entryContexts.isEmpty()) {
            return true;
        }
        if (queryContexts == null || queryContexts.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : entryContexts.entrySet()) {
            String actual = lookupContextValue(queryContexts, entry.getKey());
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, String> normalizeContexts(Map<String, String> src) {
        if (src == null || src.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> clean = new java.util.LinkedHashMap<>(src.size());
        for (Map.Entry<String, String> entry : src.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String k = entry.getKey().trim().toLowerCase(Locale.ROOT);
            String v = entry.getValue().trim();
            if (!k.isEmpty()) {
                clean.put(k, v);
            }
        }
        return clean.isEmpty() ? Map.of() : Map.copyOf(clean);
    }

    public static boolean sameContexts(Map<String, String> a, Map<String, String> b) {
        int sizeA = a == null ? 0 : a.size();
        int sizeB = b == null ? 0 : b.size();
        if (sizeA != sizeB) {
            return false;
        }
        if (sizeA == 0) {
            return true;
        }
        for (Map.Entry<String, String> entry : a.entrySet()) {
            String otherVal = lookupContextValue(b, entry.getKey());
            if (otherVal == null || !otherVal.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String lookupContextValue(Map<String, String> map, String key) {
        String direct = map.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }
}
