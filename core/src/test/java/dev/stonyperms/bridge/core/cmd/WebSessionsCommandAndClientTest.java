package dev.stonyperms.bridge.core.cmd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSessionsCommandAndClientTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void handlesEditorApplyEditsTreeAndVerboseCommands() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        executor = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + path + ":" + body);
            switch (path) {
                case "/v1/editor/sessions" -> respond(exchange, 201,
                        "{\"token\":\"tok123\",\"url\":\"http://127.0.0.1:8737/editor?session=tok123\",\"expiresAt\":9999999,\"liveSave\":true}");
                case "/v1/editor/applyedits" -> respond(exchange, 200,
                        "{\"applied\":true,\"revision\":15,\"groupsUpdated\":2,\"groupsDeleted\":0,\"tracksUpdated\":1,\"tracksDeleted\":0,\"usersUpdated\":0,\"usersDeleted\":0}");
                case "/v1/tree/sessions" -> respond(exchange, 201,
                        "{\"token\":\"tree123\",\"url\":\"http://127.0.0.1:8737/tree?session=tree123\",\"expiresAt\":9999999,\"count\":48}");
                case "/v1/verbose/sessions" -> respond(exchange, 201,
                        "{\"token\":\"vb123\",\"url\":\"http://127.0.0.1:8737/verbose?session=vb123\",\"expiresAt\":9999999,\"count\":9}");
                default -> respond(exchange, 404, "{\"error\":\"not_found\"}");
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (StonyPermsClient client = new StonyPermsClient(
                base, "read-token-0123456789", "write-token-0123456789", Duration.ofSeconds(2), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI rwApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, new SnapshotCache(60_000), new PermissionResolver(), true, true),
                    client, executor, true, "paper", ignored -> {}, () -> {});

            FakeWebPlatform platform = new FakeWebPlatform();
            BridgeCommandHandler handler = new BridgeCommandHandler(rwApi, platform);
            FakeWebSender admin = new FakeWebSender();

            // /spb group vip editor
            handler.execute(admin, "spb", new String[]{"group", "vip", "editor"});
            assertTrue(admin.await(1, 5, TimeUnit.SECONDS));
            assertTrue(admin.messages.getFirst().contains("http://127.0.0.1:8737/editor?session=tok123"));
            assertTrue(requests.stream().anyMatch(r -> r.contains("\"targetType\":\"group\"") && r.contains("paper.custom.perm")));

            // /spb applyedits sp1_demo
            admin.reset(1);
            handler.execute(admin, "spb", new String[]{"applyedits", "sp1_demo"});
            assertTrue(admin.await(1, 5, TimeUnit.SECONDS));
            assertTrue(admin.messages.getFirst().contains("rev #15"));

            // /spb tree gate
            admin.reset(1);
            handler.execute(admin, "spb", new String[]{"tree", "gate"});
            assertTrue(admin.await(1, 5, TimeUnit.SECONDS));
            assertTrue(admin.messages.getFirst().contains("http://127.0.0.1:8737/tree?session=tree123"));

            // /spb verbose paste
            admin.reset(1);
            handler.execute(admin, "spb", new String[]{"verbose", "paste"});
            assertTrue(admin.await(1, 5, TimeUnit.SECONDS));
            assertTrue(admin.messages.getFirst().contains("http://127.0.0.1:8737/verbose?session=vb123"));

            List<String> top = handler.tabComplete(admin, new String[]{""});
            assertTrue(top.containsAll(List.of("editor", "applyedits", "tree", "verbose")));
            List<String> groupSubs = handler.tabComplete(admin, new String[]{"group", "vip", ""});
            assertTrue(groupSubs.contains("editor"));
            assertEquals(4, requests.size());
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class FakeWebSender {
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        void reset(int count) {
            messages.clear();
            latch = new CountDownLatch(count);
        }

        boolean await(int count, long time, TimeUnit unit) throws InterruptedException {
            return latch.await(time, unit) && messages.size() >= count;
        }
    }

    private static final class FakeWebPlatform implements CommandPlatform {
        @Override public boolean hasAdminPermission(Object sender) { return true; }
        @Override public boolean hasWritePermission(Object sender) { return true; }
        @Override public String actorName(Object sender) { return "AdminPlayer"; }
        @Override public void sendMessage(Object sender, String message) {
            FakeWebSender s = (FakeWebSender) sender;
            s.messages.add(message);
            s.latch.countDown();
        }
        @Override public List<String> onlinePlayerNames() { return List.of("Steve"); }
        @Override public Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid) {
            return Optional.of(new OnlinePlayerRef(UUID.randomUUID(), "Steve", Map.of("server", "survival")));
        }
        @Override public void requestRefreshAll() {}
        @Override public void requestRefreshPlayer(UUID uuid) {}
        @Override public boolean reloadBridge() { return true; }
        @Override public String statusSummary() { return "ok"; }
        @Override public Map<String, String> defaultContexts() { return Map.of("server", "survival"); }
        @Override public List<String> collectKnownPermissions() { return List.of("paper.custom.perm"); }
        @Override public Map<String, List<String>> collectExtraContexts() { return Map.of("world", List.of("world")); }
    }
}
