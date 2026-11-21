package dev.stonyperms.bridge.core;

import java.util.List;

/** Paginated response from GET /v1/users. */
public record UsersPage(
        long revision,
        int total,
        int limit,
        int offset,
        List<UserSummary> users) {

    public UsersPage {
        users = users == null ? List.of() : List.copyOf(users);
    }
}
