package dev.stonyperms.bridge.core;

import java.util.List;

/** Immutable view of GET /v1/players/{uuid} for one context. */
public record Snapshot(
        int schemaVersion,
        long revision,
        String generatedAt,
        int ttlSeconds,
        UserInfo user,
        List<GroupRef> groups,
        List<Node> nodes) {

    public Snapshot {
        generatedAt = generatedAt == null ? "" : generatedAt;
        user = user == null ? UserInfo.empty() : user;
        groups = groups == null ? List.of() : List.copyOf(groups);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public int effectiveTtlSeconds() {
        return Math.max(1, ttlSeconds);
    }
}
