package dev.stonyperms.bridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Long-lived SSE subscriber for GET /v1/events with exponential backoff and
 * clean cancellation. Never applies a request timeout to the stream itself.
 */
public final class StonyPermsEventStream implements AutoCloseable {

    private static final String USER_AGENT = "stonyperms-bridge/0.2";

    private final URI eventsUri;
    private final String readToken;
    private final long reconnectMinMs;
    private final long reconnectMaxMs;
    private final Consumer<ChangeEvent> onEvent;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().create();
    private final Object sleepLock = new Object();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong lastEventAtMillis = new AtomicLong(0L);
    private volatile String lastError = "";
    private volatile Thread worker;
    private volatile CompletableFuture<HttpResponse<InputStream>> activeFuture;
    private volatile InputStream activeStream;

    public StonyPermsEventStream(
            URI baseUri,
            String readToken,
            Duration connectTimeout,
            long reconnectMinMs,
            long reconnectMaxMs,
            Consumer<ChangeEvent> onEvent) {
        if (baseUri == null) {
            throw new IllegalArgumentException("baseUri is required");
        }
        if (readToken == null || readToken.isBlank()) {
            throw new IllegalArgumentException("readToken is required");
        }
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.eventsUri = URI.create(base + "/v1/events");
        this.readToken = readToken.trim();
        this.reconnectMinMs = Math.max(50L, reconnectMinMs);
        this.reconnectMaxMs = Math.max(this.reconnectMinMs, reconnectMaxMs);
        this.onEvent = Objects.requireNonNull(onEvent, "onEvent");
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::runLoop, "stonyperms-sse");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
    }

    public boolean connected() {
        return connected.get();
    }

    public long lastEventAtMillis() {
        return lastEventAtMillis.get();
    }

    public String lastError() {
        return lastError;
    }

    private void runLoop() {
        long backoffMs = reconnectMinMs;
        while (!closed.get()) {
            boolean wasConnected = false;
            try {
                wasConnected = streamOnce(() -> {
                    connected.set(true);
                    lastError = "";
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception error) {
                if (!closed.get()) {
                    lastError = safeMessage(error);
                }
            } finally {
                connected.set(false);
                closeActiveStream();
            }

            if (closed.get()) {
                break;
            }
            if (wasConnected) {
                backoffMs = reconnectMinMs;
            }
            long waitMs = jitter(backoffMs);
            backoffMs = Math.min(reconnectMaxMs, Math.max(reconnectMinMs, backoffMs * 2));
            waitForReconnect(waitMs);
        }
    }

    private boolean streamOnce(Runnable onConnected) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(eventsUri)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Authorization", "Bearer " + readToken)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        this.activeFuture = future;
        HttpResponse<InputStream> response = future.get();
        InputStream stream = response.body();
        this.activeStream = stream;

        if (response.statusCode() != 200) {
            String errorBody = "";
            if (stream != null) {
                byte[] buf = stream.readNBytes(4096);
                errorBody = new String(buf, StandardCharsets.UTF_8);
            }
            String code = ApiException.parseSafeErrorCode(errorBody);
            lastError = "HTTP " + response.statusCode() + (code != null ? " (" + code + ")" : "");
            return false;
        }

        boolean sawConnected = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName != null || data.length() > 0) {
                        if (dispatchFrame(eventName, data.toString(), onConnected)) {
                            sawConnected = true;
                        }
                        eventName = null;
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    lastEventAtMillis.set(System.currentTimeMillis());
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = stripFieldPrefix(line, "event:");
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(stripFieldPrefix(line, "data:"));
                }
            }
        }
        return sawConnected;
    }

    private boolean dispatchFrame(String eventName, String payload, Runnable onConnected) {
        lastEventAtMillis.set(System.currentTimeMillis());
        if ("connected".equals(eventName)) {
            onConnected.run();
            return true;
        }
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            ChangeEvent parsed = gson.fromJson(payload, ChangeEvent.class);
            if (parsed == null) {
                return false;
            }
            ChangeEvent event = (parsed.type().isEmpty() && eventName != null && !eventName.isEmpty())
                    ? new ChangeEvent(
                            parsed.revision(),
                            eventName,
                            parsed.uuid(),
                            parsed.username(),
                            parsed.group(),
                            parsed.track(),
                            parsed.actor(),
                            parsed.timestamp())
                    : parsed;
            onEvent.accept(event);
        } catch (RuntimeException ignored) {
            // Ignore malformed SSE payloads rather than tearing down the stream.
        }
        return false;
    }

    private static String stripFieldPrefix(String line, String prefix) {
        String rest = line.substring(prefix.length());
        return rest.startsWith(" ") ? rest.substring(1) : rest;
    }

    private long jitter(long delayMs) {
        long spread = Math.max(1L, delayMs / 5L);
        long delta = ThreadLocalRandom.current().nextLong(-spread, spread + 1L);
        return Math.max(50L, delayMs + delta);
    }

    private void waitForReconnect(long waitMs) {
        synchronized (sleepLock) {
            if (closed.get()) {
                return;
            }
            try {
                sleepLock.wait(waitMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeActiveStream() {
        CompletableFuture<HttpResponse<InputStream>> future = this.activeFuture;
        this.activeFuture = null;
        if (future != null) {
            future.cancel(true);
        }
        InputStream stream = this.activeStream;
        this.activeStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String safeMessage(Exception error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        closeActiveStream();
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
        Thread thread = this.worker;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        http.shutdownNow();
    }
}
