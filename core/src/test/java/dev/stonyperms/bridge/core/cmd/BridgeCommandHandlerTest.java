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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeCommandHandlerTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void enforcesPermissionsAndWriteEnabledGate() throws Exception {
        UUID steveId = UUID.randomUUID();
        executor = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "{\"revision\":1,\"groups\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (StonyPermsClient client = new StonyPermsClient(base, "read-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI roApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, new SnapshotCache(60_000), new PermissionResolver(), true, true),
                    client, executor, false, "test", ignored -> {}, () -> {});
            FakePlatform platform = new FakePlatform(steveId);
            BridgeCommandHandler handler = new BridgeCommandHandler(roApi, platform);

            // Unauthorized sender denied
            FakeSender pleb = new FakeSender("Pleb", false, false);
            handler.execute(pleb, "spb", new String[]{"status"});
            assertTrue(pleb.messages.getFirst().contains("do not have permission"));

            // Admin without write denied write command
            FakeSender adminOnly = new FakeSender("Mod", true, false);
            handler.execute(adminOnly, "spb", new String[]{"user", "Steve", "group", "add", "vip"});
            assertTrue(adminOnly.messages.getFirst().contains("stonyperms.bridge.write"));

            // Write sender blocked when write.enabled=false
            FakeSender writer = new FakeSender("Admin", true, true);
            handler.execute(writer, "spb", new String[]{"user", "Steve", "group", "add", "vip", "30d", "server=survival"});
            assertTrue(writer.messages.getFirst().contains("write.enabled=false"));

            // Tab completion hides write-only commands when write is disabled
            List<String> top = handler.tabComplete(writer, new String[]{""});
            assertTrue(top.contains("status"));
            assertFalse(top.contains("log"));
        }
    }

    @Test
    void payNowAutoEnsuresOnlinePlayerOn404WithoutFabricatingOfflineTypo() throws Exception {
        UUID steveId = UUID.randomUUID();
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger steveEnsured = new AtomicInteger();

        executor = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + path + " " + body);

            if (path.equals("/v1/users") && exchange.getRequestMethod().equals("POST")) {
                steveEnsured.incrementAndGet();
                respond(exchange, 201, "{\"revision\":5,\"created\":true,\"user\":{\"uuid\":\"" + steveId + "\",\"username\":\"Steve\",\"primaryGroup\":\"default\"}}");
            } else if (path.equals("/v1/users/Steve/parents") && steveEnsured.get() == 0) {
                respond(exchange, 404, "{\"error\":\"user_not_found\"}");
            } else if (path.equals("/v1/users/" + steveId + "/parents")) {
                respond(exchange, 200, "{\"revision\":6,\"user\":{\"uuid\":\"" + steveId + "\",\"username\":\"Steve\",\"primaryGroup\":\"default\"}}");
            } else if (path.equals("/v1/users/GhostTypo/parents")) {
                respond(exchange, 404, "{\"error\":\"user_not_found\"}");
            } else {
                respond(exchange, 404, "{\"error\":\"not_found\"}");
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (StonyPermsClient client = new StonyPermsClient(
                base, "read-token-0123456789", "write-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI rwApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, new SnapshotCache(60_000), new PermissionResolver(), true, true),
                    client, executor, true, "console", ignored -> {}, () -> {});
            FakePlatform platform = new FakePlatform(steveId);
            BridgeCommandHandler handler = new BridgeCommandHandler(rwApi, platform);

            FakeSender console = new FakeSender("CONSOLE", true, true);
            handler.execute(console, "spb", new String[]{"user", "Steve", "group", "add", "vip", "30d", "server=survival"});
            assertTrue(console.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(console.messages.getFirst().contains("Updated group 'vip'"), console.messages.toString());
            assertEquals(1, steveEnsured.get());
            assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST /v1/users ")));

            // Offline typo must NOT create offline user
            FakeSender console2 = new FakeSender("CONSOLE", true, true);
            handler.execute(console2, "spb", new String[]{"user", "GhostTypo", "group", "add", "vip"});
            assertTrue(console2.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(console2.messages.getFirst().contains("user_not_found"));
            assertEquals(1, steveEnsured.get());
        }
    }

    @Test
    void executesReadWriteQuotedCommandsAndTabCompletes() throws Exception {
        UUID steveId = UUID.randomUUID();
        List<String> calls = new CopyOnWriteArrayList<>();

        executor = Executors.newSingleThreadExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + ":" + body);
            if (path.equals("/v1/groups") && method.equals("GET")) {
                respond(exchange, 200, "{\"revision\":1,\"groups\":[{\"name\":\"vip\",\"weight\":50,\"displayName\":\"VIP\"}]}");
            } else if (path.equals("/v1/groups") && method.equals("POST")) {
                respond(exchange, 201, "{\"revision\":2,\"group\":{\"name\":\"vip\",\"weight\":50,\"displayName\":\"VIP\"}}");
            } else if (path.startsWith("/v1/groups/")) {
                respond(exchange, 200, "{\"revision\":3,\"group\":{\"name\":\"vip\",\"weight\":60,\"displayName\":\"VIP\"}}");
            } else if (path.startsWith("/v1/users/")) {
                respond(exchange, 200, "{\"revision\":7,\"user\":{\"uuid\":\"" + steveId + "\",\"username\":\"Steve\",\"primaryGroup\":\"vip\"}}");
            } else if (path.equals("/v1/tracks/ranks")) {
                respond(exchange, 200, "{\"revision\":8,\"track\":{\"name\":\"ranks\",\"groups\":[\"default\",\"vip\"]}}");
            } else if (path.equals("/v1/logs")) {
                respond(exchange, 200, "{\"revision\":8,\"logs\":[{\"timestamp\":1700000000,\"actor\":\"console\",\"targetType\":\"user\",\"target\":\"Steve\",\"action\":\"Set prefix\"}]}");
            } else if (path.equals("/v1/reload")) {
                respond(exchange, 200, "{\"revision\":9,\"reloaded\":true}");
            } else {
                respond(exchange, 404, "{\"error\":\"not_found\"}");
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (StonyPermsClient client = new StonyPermsClient(
                base, "read-token-0123456789", "write-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI rwApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, new SnapshotCache(60_000), new PermissionResolver(), true, true),
                    client, executor, true, "console", ignored -> {}, () -> {});
            FakePlatform platform = new FakePlatform(steveId);
            BridgeCommandHandler handler = new BridgeCommandHandler(rwApi, platform);
            FakeSender admin = new FakeSender("Console", true, true);

            // Quoted prefix
            handler.execute(admin, "spb", new String[]{"user", "Steve", "meta", "setprefix", "100", "\"&a[VIP]", "\"", "server=lobby"});
            assertTrue(admin.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(calls.stream().anyMatch(b -> b.contains("&a[VIP] ")));

            // Group create + track append + reload remote + permission settemp + logs
            FakeSender grpSender = new FakeSender("Console", true, true);
            handler.execute(grpSender, "spb", new String[]{"group", "vip", "create", "50", "&aVIP"});
            assertTrue(grpSender.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(grpSender.messages.getFirst().contains("Created group 'vip'"));

            FakeSender permSender = new FakeSender("Console", true, true);
            handler.execute(permSender, "spb", new String[]{"user", "Steve", "permission", "settemp", "fly.use", "1h", "server=lobby"});
            assertTrue(permSender.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(calls.stream().anyMatch(b -> b.contains("\"key\":\"fly.use\"") && b.contains("\"durationSeconds\":3600")));

            FakeSender trkSender = new FakeSender("Console", true, true);
            handler.execute(trkSender, "spb", new String[]{"track", "ranks", "append", "mvp"});
            assertTrue(trkSender.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(calls.stream().anyMatch(b -> b.contains("PUT /v1/tracks/ranks") && b.contains("\"mvp\"")));

            FakeSender reloadSender = new FakeSender("Console", true, true);
            handler.execute(reloadSender, "spb", new String[]{"reload", "remote"});
            assertTrue(reloadSender.awaitMessages(1, 5, TimeUnit.SECONDS));
            assertTrue(reloadSender.messages.getFirst().contains("Remote StonyPerms reload triggered"));

            FakeSender logSender = new FakeSender("Console", true, true);
            handler.execute(logSender, "spb", new String[]{"log", "10"});
            assertTrue(logSender.awaitMessages(2, 5, TimeUnit.SECONDS));
            assertTrue(logSender.messages.stream().anyMatch(m -> m.contains("Set prefix")));

            assertTrue(handler.tabComplete(admin, new String[]{""}).contains("log"));
            assertTrue(handler.tabComplete(admin, new String[]{"user", "Steve", ""}).contains("group"));
            assertTrue(handler.tabComplete(admin, new String[]{"user", "Steve", "group", ""}).contains("accumulate"));
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class FakeSender {
        final String name;
        final boolean admin;
        final boolean write;
        final List<String> messages = new CopyOnWriteArrayList<>();

        FakeSender(String name, boolean admin, boolean write) {
            this.name = name;
            this.admin = admin;
            this.write = write;
        }

        synchronized void addMessage(String msg) {
            messages.add(msg);
            notifyAll();
        }

        synchronized boolean awaitMessages(int count, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (messages.size() < count) {
                long remNs = deadline - System.nanoTime();
                if (remNs <= 0) {
                    return false;
                }
                long waitMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remNs));
                wait(waitMs);
            }
            return true;
        }
    }

    private static final class FakePlatform implements CommandPlatform {
        private final UUID steveId;

        FakePlatform(UUID steveId) {
            this.steveId = steveId;
        }

        @Override
        public boolean hasAdminPermission(Object sender) {
            return sender instanceof FakeSender fs && fs.admin;
        }

        @Override
        public boolean hasWritePermission(Object sender) {
            return sender instanceof FakeSender fs && fs.write;
        }

        @Override
        public String actorName(Object sender) {
            return sender instanceof FakeSender fs ? fs.name : "console";
        }

        @Override
        public void sendMessage(Object sender, String message) {
            if (sender instanceof FakeSender fs) {
                fs.addMessage(message);
            }
        }

        @Override
        public List<String> onlinePlayerNames() {
            return List.of("Steve");
        }

        @Override
        public Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid) {
            if ("Steve".equalsIgnoreCase(nameOrUuid) || steveId.toString().equalsIgnoreCase(nameOrUuid)) {
                return Optional.of(new OnlinePlayerRef(steveId, "Steve", Map.of("server", "lobby")));
            }
            return Optional.empty();
        }

        @Override
        public void requestRefreshAll() {
        }

        @Override
        public void requestRefreshPlayer(UUID uuid) {
        }

        @Override
        public boolean reloadBridge() {
            return true;
        }

        @Override
        public String statusSummary() {
            return "StonyPerms bridge\n  write: enabled";
        }

        @Override
        public Map<String, String> defaultContexts() {
            return Map.of("server", "lobby");
        }
    }
}
