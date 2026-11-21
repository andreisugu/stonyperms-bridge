package dev.stonyperms.bridge.core;

import java.util.List;

/** Response returned by GET /v1/search. */
public record SearchResultRecord(
        long revision,
        String query,
        List<SearchHitRecord> groupHits,
        List<SearchHitRecord> userHits) {

    public SearchResultRecord {
        query = query == null ? "" : query;
        groupHits = groupHits == null ? List.of() : List.copyOf(groupHits);
        userHits = userHits == null ? List.of() : List.copyOf(userHits);
    }
}
