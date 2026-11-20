package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class SnapshotCacheTest {

    private static final UUID ID = UUID.randomUUID();
    private static final SnapshotCache.Key KEY = new SnapshotCache.Key(ID, "");

    @Test
    void freshThenStaleThenMissing() {
        SnapshotCache cache = new SnapshotCache(5_000);
        Snapshot snapshot = snapshot(2);
        cache.put(KEY, snapshot, "\"etag\"", 1_000);

        assertEquals(SnapshotCache.State.FRESH, cache.lookup(KEY, 1_000).state());
        assertEquals(SnapshotCache.State.FRESH, cache.lookup(KEY, 31_000).state());
        assertEquals(SnapshotCache.State.STALE, cache.lookup(KEY, 31_001).state());
        assertEquals(SnapshotCache.State.STALE, cache.lookup(KEY, 36_000).state());
        assertEquals(SnapshotCache.State.MISSING, cache.lookup(KEY, 36_001).state());
        assertEquals(0, cache.size());
    }

    @Test
    void olderRevisionDoesNotOverwriteNewer() {
        SnapshotCache cache = new SnapshotCache(5_000);
        Snapshot newer = snapshot(9);
        cache.put(KEY, newer, "new", 1_000);
        cache.put(KEY, snapshot(3), "old", 2_000);

        assertSame(newer, cache.lookup(KEY, 2_000).snapshot());
        assertEquals("new", cache.lookup(KEY, 2_000).etag());
    }

    @Test
    void touchKeepsSnapshotAndExtendsFreshness() {
        SnapshotCache cache = new SnapshotCache(5_000);
        Snapshot snapshot = snapshot(1);
        cache.put(KEY, snapshot, "etag", 1_000);
        cache.touch(KEY, 40_000);

        SnapshotCache.Lookup lookup = cache.lookup(KEY, 40_000);
        assertEquals(SnapshotCache.State.FRESH, lookup.state());
        assertSame(snapshot, lookup.snapshot());
    }

    @Test
    void invalidateRemovesAllContextsForPlayer() {
        SnapshotCache cache = new SnapshotCache(5_000);
        cache.put(new SnapshotCache.Key(ID, "server=lobby"), snapshot(1), null, 1_000);
        cache.put(new SnapshotCache.Key(UUID.randomUUID(), ""), snapshot(1), null, 1_000);
        cache.invalidate(ID);

        assertFalse(cache.lookup(new SnapshotCache.Key(ID, "server=lobby"), 1_000).present());
        assertEquals(1, cache.size());
    }

    private static Snapshot snapshot(long revision) {
        return new Snapshot(1, revision, "", 30,
                new UserInfo(ID.toString(), "Steve", "default", false, Map.of(), "", ""),
                List.of(), List.of());
    }
}
