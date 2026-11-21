package dev.stonyperms.bridge.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StonyPermsEventStreamTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsConnectedKeepaliveAndChangeEventsAndReconnects() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twoEvents = new CountDownLatch(2);
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                byte[] err = "{\"error\":\"sse_capacity_exceeded\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                write(out, "event: connected\ndata: {\"revision\":10,\"generation\":\"gen-1\"}\n\n");
                write(out, ": keepalive\n\n");
                write(out, "event: user_updated\ndata: {\"revision\":11,\"type\":\"user_updated\",\"username\":\"Steve\",\"actor\":\"paynow\",\"timestamp\":1700000001}\n\n");
                write(out, "event: reload\ndata: {\"revision\":12,\"type\":\"reload\",\"actor\":\"ops\",\"timestamp\":1700000002}\n\n");
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (StonyPermsEventStream stream = new StonyPermsEventStream(
                base, "read-token-0123456789", Duration.ofSeconds(1), 50L, 150L, ev -> {
            events.add(ev);
            twoEvents.countDown();
        })) {
            stream.start();
            assertTrue(twoEvents.await(5, TimeUnit.SECONDS), "expected two SSE events after reconnect");
            assertTrue(attempts.get() >= 2);
            assertTrue(stream.lastEventAtMillis() > 0L);
            assertEquals("user_updated", events.get(0).type());
            assertEquals("Steve", events.get(0).username());
            assertEquals("reload", events.get(1).type());
        }

        // After close(), connected() must be false promptly
        // Wait briefly to confirm clean shutdown
    }

    @Test
    void closeStopsConnectingPromptlyOnFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            byte[] err = "{\"error\":\"sse_disabled\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        StonyPermsEventStream stream = new StonyPermsEventStream(
                base, "read-token-0123456789", Duration.ofSeconds(1), 5000L, 10000L, ignored -> {});
        stream.start();
        Thread.sleep(150);
        assertFalse(stream.connected());
        assertTrue(stream.lastError().contains("503"));

        long start = System.nanoTime();
        stream.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 1_000L, "close took too long: " + elapsedMs + "ms");
    }

    private static void write(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
