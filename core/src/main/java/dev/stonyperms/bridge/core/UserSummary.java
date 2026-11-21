package dev.stonyperms.bridge.core;

import java.util.List;
import java.util.UUID;

/** Summary entry returned in paginated GET /v1/users listings. */
public record UserSummary(
        UUID uuid,
        String username,
        String primaryGroup,
        List<ParentEntry> parents,
        int nodeCount) {

    public UserSummary {
        username = username == null ? "" : username;
        primaryGroup = primaryGroup == null || primaryGroup.isBlank() ? "default" : primaryGroup;
        parents = parents == null ? List.of() : List.copyOf(parents);
    }
}
