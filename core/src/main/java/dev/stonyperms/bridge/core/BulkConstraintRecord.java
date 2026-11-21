package dev.stonyperms.bridge.core;

/** Filter constraint for POST /v1/bulkupdate. */
public record BulkConstraintRecord(String field, String operator, String pattern) {
}
