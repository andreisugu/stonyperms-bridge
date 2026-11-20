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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StonyPermsClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesSnapshotAndSendsBearerAndContext() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> etag = new AtomicReference<>();

        start(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            etag.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            respond(exchange, 200, """
                    {
                      "schemaVersion": 1,
                      "revision": 4,
                      "generatedAt": "2026-11-20T00:00:00Z",
                      "ttlSeconds": 30,
                      "user": {
                        "uuid": "%s",
                        "username": "Steve",
                        "primaryGroup": "vip",
                        "synthetic": false,
                        "meta": {"rank": "gold"},
                        "prefix": "&aVIP ",
                        "suffix": "&r"
                      },
                      "groups": [{"name": "vip", "displayName": "VIP", "weight": 50}],
                      "nodes": [{"key": "example.*", "value": true, "source": "group:vip", "weight": 50, "user": false}]
                    }
                    """.formatted(id));
        });

        try (StonyPermsClient client = client()) {
            SnapshotSource.FetchResult result = client.fetchSnapshot(
                    id, Map.of("server", "lobby", "world", "hub"), "\"old\"");

            assertFalse(result.notModified());
            assertEquals(4, result.snapshot().revision());
            assertEquals("Steve", result.snapshot().user().username());
            assertEquals("&aVIP ", result.snapshot().user().prefix());
            assertEquals("group:vip", result.snapshot().nodes().getFirst().source());
            assertEquals("Bearer test-token-0123456789", auth.get());
            assertEquals("\"old\"", etag.get());
            assertTrue(query.get().contains("server=lobby"));
            assertTrue(query.get().contains("world=hub"));
        }
    }

    @Test
    void handlesNotModifiedAndAuthFailures() throws Exception {
        UUID id = UUID.randomUUID();
        start(exchange -> {
            if (!"Bearer test-token-0123456789".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{}");
                return;
            }
            exchange.getResponseHeaders().add("ETag", "\"rev-9\"");
            exchange.sendResponseHeaders(304, -1);
        });

        try (StonyPermsClient client = client()) {
            SnapshotSource.FetchResult result = client.fetchSnapshot(id, Map.of(), "\"rev-9\"");
            assertTrue(result.notModified());
            assertEquals("\"rev-9\"", result.etag());
        }

        try (StonyPermsClient bad = new StonyPermsClient(
                URI.create(baseUrl()), "wrong-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            ApiException error = assertThrows(ApiException.class,
                    () -> bad.fetchSnapshot(id, Map.of(), null));
            assertEquals(401, error.status());
            assertFalse(error.getMessage().contains("wrong-token"));
        }
    }

    @Test
    void checkPostsJsonAndParsesResult() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"value":"false","source":"group:blocked","revision":11}
                    """);
        });

        try (StonyPermsClient client = client()) {
            CheckResult result = client.check(id, "example.node", Map.of("server", "lobby"));
            assertEquals(Tri.FALSE, result.tri());
            assertEquals("group:blocked", result.source());
            assertEquals(11, result.revision());
            assertTrue(body.get().contains("\"permission\":\"example.node\""));
            assertTrue(body.get().contains("\"server\":\"lobby\""));
        }
    }


    @Test
    void rejectsSnapshotsWithMissingRequiredFields() throws Exception {
        UUID id = UUID.randomUUID();
        start(exchange -> respond(exchange, 200, """
                {"schemaVersion":1,"revision":1,"ttlSeconds":30,"user":{},"groups":[]}
                """));

        try (StonyPermsClient client = client()) {
            IOException error = assertThrows(IOException.class,
                    () -> client.fetchSnapshot(id, Map.of(), null));
            assertTrue(error.getMessage().contains("nodes"));
        }
    }

    @Test
    void boundsTheWholeExchangeIncludingBodyRead() throws Exception {
        UUID id = UUID.randomUUID();
        start(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        try (StonyPermsClient client = client(Duration.ofMillis(300))) {
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> client.fetchSnapshot(id, Map.of(), null));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMillis < 2_000, "request exceeded its whole-exchange deadline: " + elapsedMillis + "ms");
        }
    }

    private StonyPermsClient client() {
        return client(Duration.ofSeconds(2));
    }

    private StonyPermsClient client(Duration requestTimeout) {
        return new StonyPermsClient(
                URI.create(baseUrl()), "test-token-0123456789", Duration.ofSeconds(1), requestTimeout);
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
