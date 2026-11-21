package dev.stonyperms.bridge.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Raw stored StonyPerms user record. */
public record UserRecord(
        UUID uuid,
        String username,
        String primaryGroup,
        List<ParentEntry> parents,
        List<Node> nodes,
        Map<String, String> meta,
        List<PriorityMeta> prefixes,
        List<PriorityMeta> suffixes) {

    public UserRecord {
        username = username == null ? "" : username;
        primaryGroup = primaryGroup == null || primaryGroup.isBlank() ? "default" : primaryGroup;
        parents = parents == null ? List.of() : List.copyOf(parents);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        meta = meta == null || meta.isEmpty() ? Map.of() : Map.copyOf(meta);
        prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
        suffixes = suffixes == null ? List.of() : List.copyOf(suffixes);
    }
}
