package dev.stonyperms.bridge.core;

/** Result summary returned by POST /v1/bulkupdate. */
public record BulkUpdateSummaryRecord(
        boolean dryRun,
        int groupsChanged,
        int usersChanged,
        int nodesAffected,
        long revision) {
}
