package dev.stonyperms.bridge.core;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** The network surface the bridge needs; implemented by StonyPermsClient. */
public interface SnapshotSource {
    FetchResult fetchSnapshot(UUID uuid, Map<String, String> contexts, String etag)
            throws IOException, InterruptedException;

    long fetchRevision() throws IOException, InterruptedException;

    CheckResult check(UUID uuid, String permission, Map<String, String> contexts)
            throws IOException, InterruptedException;

    record FetchResult(Snapshot snapshot, String etag, boolean notModified) {
    }
}
