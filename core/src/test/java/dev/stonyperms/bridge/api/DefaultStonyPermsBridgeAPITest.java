package dev.stonyperms.bridge.api;

import com.sun.net.httpserver.HttpServer;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.Tri;
import dev.stonyperms.bridge.core.UserInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStonyPermsBridgeAPITest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void servesCachedReadsFiresHooksAndFailsClosedWhenDisabled() throws Exception {
        UUID u1 = UUID.randomUUID();
        AtomicInteger userRefreshes = new AtomicInteger();
        AtomicInteger globalRefreshes = new AtomicInteger();
        AtomicInteger httpCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = path.startsWith("/v1/users/")
                    ? "{\"revision\":9,\"user\":{\"uuid\":\"" + u1 + "\",\"username\":\"Steve\",\"primaryGroup\":\"vip\"}}"
                    : "{\"revision\":10,\"group\":{\"name\":\"vip\",\"weight\":50}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        executor = Executors.newSingleThreadExecutor();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        SnapshotCache cache = new SnapshotCache(60_000);
        Map<String, String> ctx = Map.of("server", "lobby");
        Snapshot snap = new Snapshot(
                1, 5, "", 30,
                new UserInfo(u1.toString(), "Steve", "vip", false, Map.of("tier", "gold"), "&a[VIP] ", "&r"),
                List.of(new GroupRef("vip", "&aVIP", 50), new GroupRef("default", "Default", 0)),
                List.of(new Node("lobby.fly", true, ctx, 0, "group:vip", 50, false)));
        cache.put(new SnapshotCache.Key(u1, Contexts.key(ctx)), snap, "\"rev-5\"", System.currentTimeMillis());

        try (StonyPermsClient roClient = new StonyPermsClient(base, "read-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2));
             StonyPermsClient rwClient = new StonyPermsClient(base, "read-token-0123456789", "write-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {

            BridgeService roService = new BridgeService(roClient, cache, new PermissionResolver(), true, true);
            DefaultStonyPermsBridgeAPI roApi = new DefaultStonyPermsBridgeAPI(
                    roService, roClient, executor, false, "test", ignored -> userRefreshes.incrementAndGet(), globalRefreshes::incrementAndGet);

            assertFalse(roApi.isWriteEnabled());
            assertEquals("vip", roApi.getPrimaryGroup(u1, ctx));
            assertTrue(roApi.inGroup(u1, "VIP", ctx));
            assertEquals("&a[VIP] ", roApi.getPrefix(u1, ctx).orElseThrow());
            assertEquals("gold", roApi.getMeta(u1, "tier", ctx).orElseThrow());
            assertEquals(Tri.TRUE, roApi.checkCached(u1, "lobby.fly", ctx).value());

            CompletionException err = assertThrows(CompletionException.class,
                    () -> roApi.addParent(u1.toString(), "vip", ParentMode.ADD, 0, ctx, false).join());
            assertTrue(err.getCause() instanceof IllegalStateException);
            assertEquals(0, httpCalls.get());

            BridgeService rwService = new BridgeService(rwClient, cache, new PermissionResolver(), true, true);
            DefaultStonyPermsBridgeAPI rwApi = new DefaultStonyPermsBridgeAPI(
                    rwService, rwClient, executor, true, "paynow", ignored -> userRefreshes.incrementAndGet(), globalRefreshes::incrementAndGet);
            assertTrue(rwApi.isWriteEnabled());

            List<UUID> updated = new ArrayList<>();
            List<ChangeEvent> changes = new ArrayList<>();
            AutoCloseable sub1 = rwApi.onUserUpdated((id, s) -> updated.add(id));
            AutoCloseable sub2 = rwApi.onChangeEvent(changes::add);
            rwApi.notifyUserUpdated(u1, snap);
            rwApi.notifyChangeEvent(new ChangeEvent(5, "user_updated", u1.toString(), "Steve", "", "", "paynow", 100));
            sub1.close();
            sub2.close();
            rwApi.notifyUserUpdated(u1, snap);
            assertEquals(1, updated.size());
            assertEquals(1, changes.size());

            MutationResult res = rwApi.addParent(u1.toString(), "vip", ParentMode.ADD, 0, ctx, false).join();
            assertEquals(9L, res.revision());
            assertEquals(1, userRefreshes.get());

            rwApi.patchGroup("vip", 55, null, "admin", null).join();
            assertEquals(1, globalRefreshes.get());
        }
    }
}
