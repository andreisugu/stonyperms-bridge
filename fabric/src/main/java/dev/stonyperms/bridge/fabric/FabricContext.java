package dev.stonyperms.bridge.fabric;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricContext {
    private FabricContext() {
    }

    public static Map<String, String> of(String serverName, String worldName) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (serverName != null && !serverName.isBlank()) {
            contexts.put("server", serverName);
        }
        if (worldName != null && !worldName.isBlank()) {
            contexts.put("world", worldName);
        }
        return contexts;
    }
}
