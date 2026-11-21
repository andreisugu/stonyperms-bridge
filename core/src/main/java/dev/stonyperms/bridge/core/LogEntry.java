package dev.stonyperms.bridge.core;

/** Audit log entry returned by GET /v1/logs. */
public record LogEntry(
        long timestamp,
        String actor,
        String targetType,
        String target,
        String action) {

    public LogEntry {
        actor = actor == null ? "" : actor;
        targetType = targetType == null ? "" : targetType;
        target = target == null ? "" : target;
        action = action == null ? "" : action;
    }
}
