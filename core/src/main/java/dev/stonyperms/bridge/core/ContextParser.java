package dev.stonyperms.bridge.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses and validates trailing key=value context pairs from CLI arguments. */
public final class ContextParser {

    public static final int MAX_CONTEXT_ENTRIES = 16;
    public static final int MAX_CONTEXT_VALUE_BYTES = 128;
    private static final Pattern CONTEXT_KEY = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private ContextParser() {
    }

    public static Map<String, String> parseTrailing(List<String> args, int startIndex) {
        if (args == null || startIndex >= args.size()) {
            return Map.of();
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        for (int i = startIndex; i < args.size(); i++) {
            String token = args.get(i);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Expected context key=value pair, got: " + token);
            }
            String key = token.substring(0, eq);
            String value = token.substring(eq + 1);
            validateEntry(key, value);
            if (!contexts.containsKey(key) && contexts.size() >= MAX_CONTEXT_ENTRIES) {
                throw new IllegalArgumentException("At most " + MAX_CONTEXT_ENTRIES + " contexts are allowed");
            }
            contexts.put(key, value);
        }
        return Map.copyOf(contexts);
    }

    public static boolean isContextToken(String token) {
        if (token == null) {
            return false;
        }
        int eq = token.indexOf('=');
        return eq > 0 && CONTEXT_KEY.matcher(token.substring(0, eq)).matches();
    }

    public static void validateKey(String key) {
        if (key == null || !CONTEXT_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Context key must match ^[a-z0-9_-]{1,32}$: " + key);
        }
    }

    public static void validateEntry(String key, String value) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Context value cannot be null");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_VALUE_BYTES) {
            throw new IllegalArgumentException("Context value for '" + key + "' exceeds 128 bytes");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                throw new IllegalArgumentException("Context value for '" + key + "' contains control characters");
            }
        }
    }
}
