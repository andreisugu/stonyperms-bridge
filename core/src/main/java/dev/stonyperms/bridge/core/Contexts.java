package dev.stonyperms.bridge.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

public final class Contexts {
    private Contexts() {
    }

    /** Stable cache key for a context map; ordering does not depend on map iteration. */
    public static String key(Map<String, String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(contexts);
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            joiner.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }
        return joiner.toString();
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
