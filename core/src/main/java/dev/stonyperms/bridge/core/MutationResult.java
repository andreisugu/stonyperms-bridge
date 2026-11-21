package dev.stonyperms.bridge.core;

/** Result wrapper returned by write and admin mutations. */
public record MutationResult(
        long revision,
        boolean idempotentReplay,
        UserRecord user,
        GroupRecord group,
        TrackRecord track,
        boolean deleted,
        boolean created,
        boolean reloaded) {

    public static MutationResult forUser(long revision, boolean idempotentReplay, UserRecord user) {
        return new MutationResult(revision, idempotentReplay, user, null, null, false, false, false);
    }

    public static MutationResult forEnsureUser(long revision, boolean idempotentReplay, boolean created, UserRecord user) {
        return new MutationResult(revision, idempotentReplay, user, null, null, false, created, false);
    }

    public static MutationResult forGroup(long revision, boolean idempotentReplay, boolean created, GroupRecord group) {
        return new MutationResult(revision, idempotentReplay, null, group, null, false, created, false);
    }

    public static MutationResult forTrack(long revision, boolean idempotentReplay, boolean created, TrackRecord track) {
        return new MutationResult(revision, idempotentReplay, null, null, track, false, created, false);
    }

    public static MutationResult forDelete(long revision, boolean idempotentReplay, boolean deleted) {
        return new MutationResult(revision, idempotentReplay, null, null, null, deleted, false, false);
    }

    public static MutationResult forReload(long revision, boolean idempotentReplay, boolean reloaded) {
        return new MutationResult(revision, idempotentReplay, null, null, null, false, false, reloaded);
    }
}
