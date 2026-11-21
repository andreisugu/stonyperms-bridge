package dev.stonyperms.bridge.core;

/** Live state-change event emitted over GET /v1/events SSE. */
public record ChangeEvent(
        long revision,
        String type,
        String uuid,
        String username,
        String group,
        String track,
        String actor,
        long timestamp) {

    public ChangeEvent {
        type = type == null ? "" : type;
        uuid = uuid == null ? "" : uuid;
        username = username == null ? "" : username;
        group = group == null ? "" : group;
        track = track == null ? "" : track;
        actor = actor == null ? "" : actor;
    }
}
