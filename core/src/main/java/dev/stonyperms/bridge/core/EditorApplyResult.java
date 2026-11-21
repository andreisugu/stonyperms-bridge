package dev.stonyperms.bridge.core;

/** Result of applying a StonyPermsWeb edit payload or sp1_/sp_ apply code. */
public record EditorApplyResult(
        boolean applied,
        long revision,
        int groupsUpdated,
        int groupsDeleted,
        int tracksUpdated,
        int tracksDeleted,
        int usersUpdated,
        int usersDeleted) {
}
