package dev.stonyperms.bridge.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StonyPermsClientReadWriteTest {

    private static final String READ_TOKEN = "read-token-0123456789";
    private static final String WRITE_TOKEN = "write-token-0123456789";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readOnlyClientFailsClosedBeforeHttpOnWriteRoutes() throws Exception {
        List<String> hits = new ArrayList<>();
        start(exchange -> {
            hits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 200, "{}");
        });

        try (StonyPermsClient ro4 = new StonyPermsClient(
                URI.create(baseUrl()), READ_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(2));
             StonyPermsClient ro5 = new StonyPermsClient(
                     URI.create(baseUrl()), READ_TOKEN, "   ", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            assertFalse(ro4.hasWriteToken());
            assertFalse(ro5.hasWriteToken());
            assertThrows(IllegalStateException.class, () -> ro4.createGroup("vip", 10, "VIP", "ops", null));
            assertThrows(IllegalStateException.class, () -> ro5.fetchLogs(10, null, null, null));
            assertThrows(IllegalStateException.class, () -> ro4.triggerRemoteReload("ops", null));
            assertTrue(hits.isEmpty(), "read-only client must fail closed before making any HTTP request");
        }
    }

    @Test
    void exercisesExtendedReadEndpointsAndSafeErrorParsing() throws Exception {
        UUID u1 = UUID.randomUUID();
        List<String> auths = new ArrayList<>();
        List<String> userAgents = new ArrayList<>();

        start(exchange -> {
            auths.add(exchange.getRequestHeaders().getFirst("Authorization"));
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            switch (path) {
                case "/v1/health" -> respond(exchange, 200,
                        "{\"status\":\"ok\",\"schemaVersion\":1,\"revision\":12,\"writeEnabled\":true,\"sseEnabled\":true}");
                case "/v1/players/batch" -> respond(exchange, 200, """
                        {
                          "schemaVersion": 1,
                          "revision": 12,
                          "players": {
                            "%s": {
                              "schemaVersion": 1,
                              "revision": 12,
                              "ttlSeconds": 30,
                              "user": {"uuid":"%s","username":"Steve","primaryGroup":"vip","synthetic":false},
                              "groups": [{"name":"vip","displayName":"VIP","weight":50}],
                              "nodes": []
                            }
                          }
                        }
                        """.formatted(u1, u1));
                case "/v1/users" -> respond(exchange, 200, """
                        {"revision":12,"total":1,"limit":10,"offset":0,"users":[
                          {"uuid":"%s","username":"Steve","primaryGroup":"vip","parents":[{"group":"vip","expiry":0}],"nodeCount":2}
                        ]}
                        """.formatted(u1));
                case "/v1/users/by-name/Steve" -> {
                    if (query != null && query.contains("effective=true")) {
                        respond(exchange, 200, """
                                {"schemaVersion":1,"revision":12,"ttlSeconds":30,
                                 "user":{"uuid":"%s","username":"Steve","primaryGroup":"vip","synthetic":false},
                                 "groups":[],"nodes":[]}
                                """.formatted(u1));
                    } else {
                        respond(exchange, 200, """
                                {"revision":12,"user":{"uuid":"%s","username":"Steve","primaryGroup":"vip"}}
                                """.formatted(u1));
                    }
                }
                case "/v1/users/by-name/Missing" ->
                        respond(exchange, 404, "{\"error\":\"user_not_found\",\"message\":\"secret internal path\"}");
                case "/v1/users/BadName" ->
                        respond(exchange, 404, "{\"error\":\"user_not_found\",\"message\":\"do-not-leak-me\"}");
                case "/v1/groups" -> respond(exchange, 200,
                        "{\"revision\":12,\"groups\":[{\"name\":\"vip\",\"displayName\":\"VIP\",\"weight\":50}]}");
                case "/v1/groups/vip%2B" -> respond(exchange, 200,
                        "{\"revision\":12,\"group\":{\"name\":\"vip+\",\"displayName\":\"VIP+\",\"weight\":75}}");
                case "/v1/groups/vip/members" -> respond(exchange, 200, """
                        {"revision":12,"group":"vip","members":[
                          {"uuid":"%s","username":"Steve","primaryGroup":"vip","permanent":true,"expiry":0}
                        ]}
                        """.formatted(u1));
                case "/v1/tracks" -> respond(exchange, 200,
                        "{\"revision\":12,\"tracks\":[{\"name\":\"ranks\",\"groups\":[\"default\",\"vip\"]}]}");
                case "/v1/tracks/ranks" -> respond(exchange, 200,
                        "{\"revision\":12,\"track\":{\"name\":\"ranks\",\"groups\":[\"default\",\"vip\"]}}");
                default -> respond(exchange, 404, "{\"error\":\"not_found\"}");
            }
        });

        try (StonyPermsClient client = rwClient()) {
            assertTrue(client.hasWriteToken());

            HealthInfo health = client.fetchHealth();
            assertEquals("ok", health.status());
            assertTrue(health.writeEnabled());
            assertTrue(health.sseEnabled());
            assertEquals(12L, health.revision());

            Map<UUID, Snapshot> batch = client.fetchSnapshotsBatch(List.of(u1), Map.of("server", "lobby"));
            assertEquals("Steve", batch.get(u1).user().username());

            UsersPage page = client.listUsers("vip", 0, 10);
            assertEquals(1, page.total());
            assertEquals("Steve", page.users().getFirst().username());

            Optional<UserRecord> found = client.resolveUserByName("Steve");
            assertTrue(found.isPresent());
            assertEquals(u1, found.get().uuid());
            assertFalse(client.resolveUserByName("Missing").isPresent());

            Snapshot byName = client.fetchSnapshotByName("Steve", Map.of("server", "lobby"));
            assertEquals("vip", byName.user().primaryGroup());

            ApiException missing = assertThrows(ApiException.class, () -> client.fetchUser("BadName"));
            assertEquals(404, missing.status());
            assertEquals("user_not_found", missing.errorCode());
            assertFalse(missing.getMessage().contains("do-not-leak-me"));

            assertEquals(1, client.fetchGroups().size());
            assertEquals("vip+", client.fetchGroup("vip+").name());
            assertEquals("Steve", client.fetchGroupMembers("vip").getFirst().username());
            assertEquals(1, client.fetchTracks().size());
            assertEquals(List.of("default", "vip"), client.fetchTrack("ranks").groups());

            assertTrue(auths.stream().allMatch(a -> ("Bearer " + READ_TOKEN).equals(a)));
            assertTrue(userAgents.stream().allMatch("stonyperms-bridge/0.2"::equals));
        }
    }

    @Test
    void exercisesWriteAndAdminRoutesWithTokenScopingHeadersAndBodyShapes() throws Exception {
        UUID u1 = UUID.randomUUID();
        record Captured(String method, String path, String query, String auth, String actor, String idemp, String body) {}
        List<Captured> calls = new ArrayList<>();

        start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            calls.add(new Captured(
                    exchange.getRequestMethod(),
                    path,
                    query,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-StonyPerms-Actor"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    body));

            if ("replay-key".equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))) {
                exchange.getResponseHeaders().add("X-Idempotency-Replayed", "true");
            }

            if (path.equals("/v1/users") && exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 201, "{\"revision\":15,\"created\":true,\"user\":{\"uuid\":\"" + u1 + "\",\"username\":\"Steve\",\"primaryGroup\":\"default\"}}");
            } else if (path.equals("/v1/users/" + u1) && exchange.getRequestMethod().equals("DELETE")) {
                respond(exchange, 200, "{\"revision\":16,\"deleted\":true,\"uuid\":\"" + u1 + "\"}");
            } else if (path.endsWith("/promote") || path.endsWith("/demote")) {
                respond(exchange, 200, "{\"revision\":17,\"fromGroup\":\"vip\",\"toGroup\":\"mvp\",\"user\":{\"uuid\":\"" + u1 + "\",\"username\":\"Steve\",\"primaryGroup\":\"mvp\"}}");
            } else if (path.startsWith("/v1/users/")) {
                respond(exchange, 200, "{\"revision\":18,\"user\":{\"uuid\":\"" + u1 + "\",\"username\":\"Steve\",\"primaryGroup\":\"vip\"}}");
            } else if (path.equals("/v1/groups") && exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 201, "{\"revision\":19,\"group\":{\"name\":\"vip+\",\"weight\":50,\"displayName\":\"VIP+\"}}");
            } else if (path.equals("/v1/groups/vip%2B") && exchange.getRequestMethod().equals("DELETE")) {
                respond(exchange, 200, "{\"revision\":20,\"deleted\":true,\"group\":\"vip+\"}");
            } else if (path.startsWith("/v1/groups/")) {
                respond(exchange, 200, "{\"revision\":21,\"group\":{\"name\":\"vip+\",\"weight\":60,\"displayName\":\"VIP+\"}}");
            } else if (path.equals("/v1/tracks") && exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 201, "{\"revision\":22,\"track\":{\"name\":\"ranks\",\"groups\":[\"default\",\"vip\"]}}");
            } else if (path.equals("/v1/tracks/ranks") && exchange.getRequestMethod().equals("PUT")) {
                respond(exchange, 200, "{\"revision\":23,\"track\":{\"name\":\"ranks\",\"groups\":[\"default\",\"vip\",\"admin\"]}}");
            } else if (path.equals("/v1/tracks/ranks") && exchange.getRequestMethod().equals("DELETE")) {
                respond(exchange, 200, "{\"revision\":24,\"deleted\":true,\"track\":\"ranks\"}");
            } else if (path.equals("/v1/logs")) {
                respond(exchange, 200, "{\"revision\":24,\"logs\":[{\"timestamp\":1700000000,\"actor\":\"paynow\",\"targetType\":\"user\",\"target\":\"Steve\",\"action\":\"Added vip\"}]}");
            } else if (path.equals("/v1/reload")) {
                respond(exchange, 200, "{\"revision\":25,\"reloaded\":true}");
            } else {
                respond(exchange, 404, "{\"error\":\"unknown_route\"}");
            }
        });

        try (StonyPermsClient client = rwClient()) {
            MutationResult ensured = client.ensureUser(u1, "Steve", "default", false, "paynow", "replay-key");
            assertTrue(ensured.created());
            assertTrue(ensured.idempotentReplay());
            assertEquals(u1, ensured.user().uuid());

            MutationResult parent = client.setParent(
                    u1.toString(), "vip", ParentMode.ACCUMULATE, 3600L, 0L, Map.of("server", "lobby"), true, "paynow", "k1");
            assertEquals(18L, parent.revision());

            client.removeParent(u1.toString(), "vip", Map.of("server", "lobby"), "paynow", null);
            client.setPrimaryGroup(u1.toString(), "vip", "admin", null);
            client.setPermission(u1.toString(), "fly.use", true, 60L, 0L, Map.of("world", "hub"), "admin", null);
            client.unsetPermission(u1.toString(), "fly.use", Map.of("server", "lobby"), "admin", null);
            client.setMeta(u1.toString(), "rank", "gold", "admin", null);
            client.unsetMeta(u1.toString(), "rank", "admin", null);
            client.setPrefix(u1.toString(), 100, "&a[VIP] ", 0L, 0L, Map.of(), "admin", null);
            client.removePrefix(u1.toString(), 100, "admin", null);
            client.setSuffix(u1.toString(), 50, " &7[S]", 0L, 0L, Map.of(), "admin", null);
            client.removeSuffix(u1.toString(), 50, "admin", null);

            TrackActionResult promoted = client.promote(u1.toString(), "ranks", "staff", null);
            assertEquals("vip", promoted.fromGroup());
            assertEquals("mvp", promoted.toGroup());
            client.demote(u1.toString(), "ranks", "staff", null);
            client.clearUser(u1.toString(), "permissions", "staff", null);
            client.batchUser(u1.toString(), List.of(
                    BatchOp.parentAdd("vip", 0L, 0L, Map.of("server", "lobby"), true),
                    BatchOp.permissionSet("batch.perm", true, 0L, 0L, Map.of())), "paynow", null);
            assertTrue(client.deleteUser(u1.toString(), "staff", null).deleted());

            assertTrue(client.createGroup("vip+", 50, "VIP+", "admin", null).created());
            assertEquals(60, client.patchGroup("vip+", 60, null, "admin", null).group().weight());
            client.setGroupPermission("vip+", "vip.fly", true, 0L, 0L, Map.of(), "admin", null);
            client.unsetGroupPermission("vip+", "vip.fly", Map.of("server", "lobby"), "admin", null);
            client.addGroupParent("vip+", "default", ParentMode.ADD, 0L, 0L, Map.of(), "admin", null);
            client.removeGroupParent("vip+", "default", Map.of("server", "lobby"), "admin", null);
            client.setGroupMeta("vip+", "color", "aqua", "admin", null);
            client.unsetGroupMeta("vip+", "color", "admin", null);
            client.setGroupPrefix("vip+", 10, "[VIP+]", 0L, 0L, Map.of(), "admin", null);
            client.removeGroupPrefix("vip+", 10, "admin", null);
            client.setGroupSuffix("vip+", 10, "[+]", 0L, 0L, Map.of(), "admin", null);
            client.removeGroupSuffix("vip+", 10, "admin", null);
            assertTrue(client.deleteGroup("vip+", "admin", null).deleted());

            assertTrue(client.createTrack("ranks", List.of("default", "vip"), "admin", null).created());
            assertEquals(3, client.putTrack("ranks", List.of("default", "vip", "admin"), "admin", null).track().groups().size());
            assertTrue(client.deleteTrack("ranks", "admin", null).deleted());

            List<LogEntry> logs = client.fetchLogs(10, "user", "Steve", "paynow");
            assertEquals(1, logs.size());
            assertEquals("paynow", logs.getFirst().actor());
            assertTrue(client.triggerRemoteReload("ops", null).reloaded());
        }

        // Every write/admin request used write token, never read token
        assertTrue(calls.stream().allMatch(c -> ("Bearer " + WRITE_TOKEN).equals(c.auth())));
        assertEquals("paynow", calls.getFirst().actor());
        assertEquals("replay-key", calls.getFirst().idemp());

        // Check DELETE permissions uses query params
        Captured unsetPerm = calls.stream()
                .filter(c -> c.method().equals("DELETE") && c.path().endsWith("/permissions") && c.path().startsWith("/v1/users/"))
                .findFirst().orElseThrow();
        assertTrue(unsetPerm.query().contains("key=fly.use"));
        assertTrue(unsetPerm.query().contains("server=lobby"));

        // Check batch parent_add does NOT include "value"
        Captured batchCall = calls.stream()
                .filter(c -> c.path().endsWith("/batch"))
                .findFirst().orElseThrow();
        assertTrue(batchCall.body().contains("\"op\":\"parent_add\""));
        assertFalse(batchCall.body().contains("\"op\":\"parent_add\",\"group\":\"vip\",\"value\""));

        // Check group PATCH and URL-encoded vip+
        Captured patchGroup = calls.stream()
                .filter(c -> c.method().equals("PATCH"))
                .findFirst().orElseThrow();
        assertEquals("/v1/groups/vip%2B", patchGroup.path());
        assertTrue(patchGroup.body().contains("\"weight\":60"));
        assertFalse(patchGroup.body().contains("displayName"));
    }

    @Test
    void rejectsMaliciousErrorCodeFromUnstructuredBody() {
        assertNull(ApiException.parseSafeErrorCode("<html>token=secret</html>"));
        assertNull(ApiException.parseSafeErrorCode("{\"error\":\"spaces not allowed\"}"));
        assertEquals("writes_disabled", ApiException.parseSafeErrorCode("{\"error\":\"writes_disabled\",\"message\":\"ignored\"}"));
    }

    private StonyPermsClient rwClient() {
        return new StonyPermsClient(
                URI.create(baseUrl()), READ_TOKEN, WRITE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException error) {
                respond(exchange, 500, "{}");
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
