package dev.stonyperms.bridge.core;

public record GroupRef(String name, String displayName, int weight) {
    public GroupRef {
        name = name == null ? "" : name;
        displayName = displayName == null || displayName.isEmpty() ? name : displayName;
    }
}
