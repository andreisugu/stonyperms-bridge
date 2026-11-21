package dev.stonyperms.bridge.core;

/** Metadata returned by GET /v1/health. */
public record HealthInfo(
        String status,
        int schemaVersion,
        long revision,
        boolean writeEnabled,
        boolean sseEnabled) {

    public HealthInfo {
        status = status == null ? "" : status;
    }
}
