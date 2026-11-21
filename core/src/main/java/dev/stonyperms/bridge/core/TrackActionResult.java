package dev.stonyperms.bridge.core;

/** Result of POST /v1/users/{id}/promote or /demote. */
public record TrackActionResult(
        long revision,
        String fromGroup,
        String toGroup,
        UserRecord user) {

    public TrackActionResult {
        fromGroup = fromGroup == null ? "" : fromGroup;
        toGroup = toGroup == null ? "" : toGroup;
    }
}
