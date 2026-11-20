package dev.stonyperms.bridge.core;

import java.util.Map;

public record UserInfo(
        String uuid,
        String username,
        String primaryGroup,
        boolean synthetic,
        Map<String, String> meta,
        String prefix,
        String suffix) {

    public UserInfo {
        uuid = uuid == null ? "" : uuid;
        username = username == null ? "" : username;
        primaryGroup = primaryGroup == null ? "default" : primaryGroup;
        meta = meta == null || meta.isEmpty() ? Map.of() : Map.copyOf(meta);
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
    }

    public static UserInfo empty() {
        return new UserInfo("", "", "default", true, Map.of(), "", "");
    }
}
