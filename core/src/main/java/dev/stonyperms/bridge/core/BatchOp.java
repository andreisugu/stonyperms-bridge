package dev.stonyperms.bridge.core;

import java.util.Map;

/** Atomic user mutation step accepted by POST /v1/users/{id}/batch. */
public record BatchOp(
        String op,
        String group,
        String key,
        Object value,
        Integer priority,
        Long durationSeconds,
        Long expiry,
        Map<String, String> contexts,
        Boolean setPrimary) {

    public BatchOp {
        if (op == null || op.isBlank()) {
            throw new IllegalArgumentException("op is required");
        }
        contexts = contexts == null || contexts.isEmpty() ? null : Map.copyOf(contexts);
    }

    public static BatchOp parentAdd(
            String group, long durationSeconds, long expiry, Map<String, String> contexts, boolean setPrimary) {
        return new BatchOp(
                "parent_add", group, null, null, null,
                durationSeconds > 0 ? durationSeconds : null,
                expiry > 0 ? expiry : null,
                contexts,
                setPrimary ? Boolean.TRUE : null);
    }

    public static BatchOp parentSet(
            String group, long durationSeconds, long expiry, Map<String, String> contexts) {
        return new BatchOp(
                "parent_set", group, null, null, null,
                durationSeconds > 0 ? durationSeconds : null,
                expiry > 0 ? expiry : null,
                contexts,
                null);
    }

    public static BatchOp parentAccumulate(
            String group, long durationSeconds, Map<String, String> contexts, boolean setPrimary) {
        return new BatchOp(
                "parent_accumulate", group, null, null, null,
                durationSeconds > 0 ? durationSeconds : null,
                null,
                contexts,
                setPrimary ? Boolean.TRUE : null);
    }

    public static BatchOp parentRemove(String group, Map<String, String> contexts) {
        return new BatchOp("parent_remove", group, null, null, null, null, null, contexts, null);
    }

    public static BatchOp primaryGroupSet(String group) {
        return new BatchOp("primary_group_set", group, null, null, null, null, null, null, null);
    }

    public static BatchOp permissionSet(
            String key, boolean value, long durationSeconds, long expiry, Map<String, String> contexts) {
        return new BatchOp(
                "permission_set", null, key, value, null,
                durationSeconds > 0 ? durationSeconds : null,
                expiry > 0 ? expiry : null,
                contexts,
                null);
    }

    public static BatchOp permissionUnset(String key, Map<String, String> contexts) {
        return new BatchOp("permission_unset", null, key, null, null, null, null, contexts, null);
    }

    public static BatchOp metaSet(String key, String value) {
        return new BatchOp("meta_set", null, key, value == null ? "" : value, null, null, null, null, null);
    }

    public static BatchOp metaUnset(String key) {
        return new BatchOp("meta_unset", null, key, null, null, null, null, null, null);
    }

    public static BatchOp prefixSet(
            int priority, String value, long durationSeconds, long expiry, Map<String, String> contexts) {
        return new BatchOp(
                "prefix_set", null, null, value == null ? "" : value, priority,
                durationSeconds > 0 ? durationSeconds : null,
                expiry > 0 ? expiry : null,
                contexts,
                null);
    }

    public static BatchOp prefixRemove(int priority) {
        return new BatchOp("prefix_remove", null, null, null, priority, null, null, null, null);
    }

    public static BatchOp suffixSet(
            int priority, String value, long durationSeconds, long expiry, Map<String, String> contexts) {
        return new BatchOp(
                "suffix_set", null, null, value == null ? "" : value, priority,
                durationSeconds > 0 ? durationSeconds : null,
                expiry > 0 ? expiry : null,
                contexts,
                null);
    }

    public static BatchOp suffixRemove(int priority) {
        return new BatchOp("suffix_remove", null, null, null, priority, null, null, null, null);
    }
}
