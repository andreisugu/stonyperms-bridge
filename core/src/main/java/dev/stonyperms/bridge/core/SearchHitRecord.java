package dev.stonyperms.bridge.core;

import java.util.Map;

/** Single matched permission or parent inheritance entry returned by GET /v1/search. */
public record SearchHitRecord(
        String targetType,
        String target,
        String uuid,
        String nodeKey,
        boolean value,
        long expiry,
        Map<String, String> contexts) {

    public SearchHitRecord {
        targetType = targetType == null ? "" : targetType;
        target = target == null ? "" : target;
        uuid = uuid == null ? "" : uuid;
        nodeKey = nodeKey == null ? "" : nodeKey;
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
    }
}
