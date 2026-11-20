package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;

public final class PaperReapply {
    private PaperReapply() {
    }

    /**
     * Delay until the attachment must be recomputed: the earlier of the
     * snapshot's usable-until boundary and the earliest contributing node
     * expiry, capped so long-lived snapshots are periodically checked.
     */
    public static long nextDelayMillis(
            SnapshotCache.Entry entry,
            Snapshot snapshot,
            long nowMillis,
            long staleGraceMillis,
            boolean useStaleOnError,
            long capMillis) {
        if (entry == null || snapshot == null) {
            return Math.max(1, capMillis);
        }
        long ttlMillis = snapshot.effectiveTtlSeconds() * 1000L;
        long usableUntil = entry.fetchedAtMillis() + ttlMillis
                + (useStaleOnError ? Math.max(0, staleGraceMillis) : 0);
        long earliestExpiry = Long.MAX_VALUE;
        for (Node node : snapshot.nodes()) {
            if (node.expiry() > 0) {
                earliestExpiry = Math.min(earliestExpiry, node.expiry() * 1000L);
            }
        }
        long deadline = Math.min(usableUntil, Math.min(earliestExpiry, nowMillis + Math.max(1, capMillis)));
        return Math.max(1, deadline - nowMillis);
    }
}
