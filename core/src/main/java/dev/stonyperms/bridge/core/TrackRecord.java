package dev.stonyperms.bridge.core;

import java.util.List;

/** Promotion track definition. */
public record TrackRecord(
        String name,
        List<String> groups) {

    public TrackRecord {
        name = name == null ? "" : name;
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
