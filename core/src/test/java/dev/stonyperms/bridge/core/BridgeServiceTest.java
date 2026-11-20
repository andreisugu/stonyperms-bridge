package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeServiceTest {

    @Test
    void missingSnapshotFailsClosedOrOpenAsConfigured() {
        UUID id = UUID.randomUUID();
        BridgeService closed = service(new FakeSource(), true, true);
        assertEquals(Tri.FALSE, closed.resolve(id, Map.of(), "anything").value());

        BridgeService open = service(new FakeSource(), false, true);
        assertEquals(Tri.UNDEFINED, open.resolve(id, Map.of(), "anything").value());
    }

    @Test
    void staleSnapshotHonorsUseStalePolicy() {
        UUID id = UUID.randomUUID();
        SnapshotCache cache = new SnapshotCache(60_000);
        Snapshot snapshot = snapshot(1, 1, List.of(node("x", true)), List.of());
        cache.put(new SnapshotCache.Key(id, ""), snapshot, null, System.currentTimeMillis() - 5_000);

        BridgeService tolerant = new BridgeService(new FakeSource(), cache, new PermissionResolver(), true, true);
        assertEquals(Tri.TRUE, tolerant.resolve(id, Map.of(), "x").value());

        BridgeService strict = new BridgeService(new FakeSource(), cache, new PermissionResolver(), true, false);
        assertEquals(Tri.FALSE, strict.resolve(id, Map.of(), "x").value());
    }

    @Test
    void refreshUsesEtagAndKeepsSnapshotOnNotModified() throws Exception {
        UUID id = UUID.randomUUID();
        FakeSource source = new FakeSource();
        Snapshot snapshot = snapshot(4, 30, List.of(node("x", true)), List.of());
        source.snapshots.add(new SnapshotSource.FetchResult(snapshot, "\"rev-4\"", false));
        source.snapshots.add(new SnapshotSource.FetchResult(null, "\"rev-4\"", true));

        BridgeService service = service(source, true, true);
        service.refresh(id, Map.of());
        assertEquals(Tri.TRUE, service.resolve(id, Map.of(), "x").value());

        service.refresh(id, Map.of());
        assertEquals("\"rev-4\"", source.etags.get(1));
        assertEquals(Tri.TRUE, service.resolve(id, Map.of(), "x").value());
        assertTrue(service.cache().entry(new SnapshotCache.Key(id, "")) != null);
    }

    @Test
    void optionsExposePrefixSuffixAndMeta() {
        UUID id = UUID.randomUUID();
        PermissionsSnapshotFixture fixture = new PermissionsSnapshotFixture(id);
        SnapshotCache cache = new SnapshotCache(0);
        cache.put(new SnapshotCache.Key(id, ""), fixture.snapshot, null, System.currentTimeMillis());
        BridgeService service = new BridgeService(new FakeSource(), cache, new PermissionResolver(), true, true);

        assertEquals("&aVIP ", service.option(id, Map.of(), "prefix").orElseThrow());
        assertEquals("&r", service.option(id, Map.of(), "suffix").orElseThrow());
        assertEquals("gold", service.option(id, Map.of(), "rank").orElseThrow());
        assertFalse(service.option(id, Map.of(), "missing").isPresent());
    }

    private static BridgeService service(FakeSource source, boolean failClosed, boolean useStale) {
        return new BridgeService(source, new SnapshotCache(60_000), new PermissionResolver(), failClosed, useStale);
    }

    private static Snapshot snapshot(int revision, int ttl, List<Node> nodes, List<GroupRef> groups) {
        return new Snapshot(1, revision, "", ttl,
                new UserInfo(UUID.randomUUID().toString(), "Steve", "default", false, Map.of(), "", ""),
                groups, nodes);
    }

    private static Node node(String key, boolean value) {
        return new Node(key, value, Map.of(), 0, "user", 0, true);
    }

    private static final class PermissionsSnapshotFixture {
        private final Snapshot snapshot;

        private PermissionsSnapshotFixture(UUID id) {
            snapshot = new Snapshot(1, 1, "", 30,
                    new UserInfo(id.toString(), "Steve", "vip", false,
                            Map.of("rank", "gold"), "&aVIP ", "&r"),
                    List.of(new GroupRef("vip", "VIP", 50)), List.of());
        }
    }

    private static final class FakeSource implements SnapshotSource {
        private final Deque<FetchResult> snapshots = new ArrayDeque<>();
        private final List<String> etags = new ArrayList<>();
        private long revision;

        @Override
        public FetchResult fetchSnapshot(UUID uuid, Map<String, String> contexts, String etag) throws IOException {
            etags.add(etag);
            FetchResult result = snapshots.poll();
            if (result == null) {
                throw new IOException("no response queued");
            }
            return result;
        }

        @Override
        public long fetchRevision() {
            return revision;
        }

        @Override
        public CheckResult check(UUID uuid, String permission, Map<String, String> contexts) {
            return new CheckResult("true", "test", revision);
        }
    }
}
