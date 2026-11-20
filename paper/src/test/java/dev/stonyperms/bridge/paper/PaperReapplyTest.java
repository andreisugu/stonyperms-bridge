package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.UserInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperReapplyTest {

    @Test
    void usesTheEarlierNodeExpiry() {
        long now = 10_000;
        Snapshot snapshot = snapshot(30, List.of(
                new Node("temp", true, Map.of(), (now + 4_000) / 1000, "user", 0, true),
                new Node("later", true, Map.of(), (now + 20_000) / 1000, "user", 0, true)));
        SnapshotCache.Entry entry = new SnapshotCache.Entry(snapshot, "etag", now);

        long delay = PaperReapply.nextDelayMillis(entry, snapshot, now, 60_000, true, 300_000);

        assertEquals(4_000, delay);
    }

    @Test
    void usesTheSnapshotUsableBoundary() {
        long now = 10_000;
        Snapshot snapshot = snapshot(10, List.of());
        SnapshotCache.Entry entry = new SnapshotCache.Entry(snapshot, "etag", now);

        long delay = PaperReapply.nextDelayMillis(entry, snapshot, now, 5_000, false, 300_000);

        assertEquals(10_000, delay);
    }

    @Test
    void capsLongLivedSnapshots() {
        long now = 10_000;
        Snapshot snapshot = snapshot(3_600, List.of());
        SnapshotCache.Entry entry = new SnapshotCache.Entry(snapshot, "etag", now);

        long delay = PaperReapply.nextDelayMillis(entry, snapshot, now, 0, true, 60_000);

        assertEquals(60_000, delay);
    }

    private static Snapshot snapshot(int ttl, List<Node> nodes) {
        return new Snapshot(1, 1, "", ttl,
                new UserInfo("00000000-0000-0000-0000-000000000001", "Steve", "default", false,
                        Map.of(), "", ""),
                List.of(), nodes);
    }
}
