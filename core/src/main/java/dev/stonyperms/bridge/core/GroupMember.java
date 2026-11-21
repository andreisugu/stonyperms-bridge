package dev.stonyperms.bridge.core;

import java.util.Map;
import java.util.UUID;

/** Member summary returned by GET /v1/groups/{name}/members. */
public record GroupMember(
        UUID uuid,
        String username,
        String primaryGroup,
        boolean permanent,
        long expiry,
        Map<String, String> contexts) {

    public GroupMember {
        username = username == null ? "" : username;
        primaryGroup = primaryGroup == null || primaryGroup.isBlank() ? "default" : primaryGroup;
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
    }
}
