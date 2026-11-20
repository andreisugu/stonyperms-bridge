package dev.stonyperms.bridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal read-only client for the StonyPerms HTTP API. Uses only the JDK
 * HttpClient and Gson; no platform classes leak into this module.
 */
public final class StonyPermsClient implements SnapshotSource, AutoCloseable {

    private final URI baseUri;
    private final String token;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().create();

    public StonyPermsClient(URI baseUri, String token, Duration connectTimeout, Duration requestTimeout) {
        if (baseUri == null) {
            throw new IllegalArgumentException("baseUri is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUri = URI.create(base);
        this.token = token;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public FetchResult fetchSnapshot(UUID uuid, Map<String, String> contexts, String etag)
            throws IOException, InterruptedException {
        String path = "/v1/players/" + uuid + query(contexts);
        HttpRequest.Builder builder = request(path).header("Accept", "application/json");
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }

        HttpResponse<String> response = send(builder.build());
        String responseEtag = response.headers().firstValue("ETag").orElse(null);
        if (response.statusCode() == 304) {
            return new FetchResult(null, responseEtag != null ? responseEtag : etag, true);
        }
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        Snapshot snapshot = parseSnapshot(response.body());
        return new FetchResult(snapshot, responseEtag, false);
    }

    @Override
    public long fetchRevision() throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                request("/v1/revision").header("Accept", "application/json").build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        JsonObject object = parseObject(response.body(), "revision");
        require(object, "revision", "revision");
        return gson.fromJson(object, RevisionResponse.class).revision;
    }

    @Override
    public CheckResult check(UUID uuid, String permission, Map<String, String> contexts)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuid", uuid.toString());
        body.put("permission", permission);
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        HttpRequest request = request("/v1/check")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        JsonObject object = parseObject(response.body(), "check result");
        require(object, "value", "check result");
        require(object, "source", "check result");
        require(object, "revision", "check result");
        CheckResponse parsed = gson.fromJson(object, CheckResponse.class);
        return new CheckResult(parsed.value, parsed.source, parsed.revision);
    }

    private Snapshot parseSnapshot(String body) throws IOException {
        JsonObject object = parseObject(body, "snapshot");
        require(object, "schemaVersion", "snapshot");
        require(object, "revision", "snapshot");
        require(object, "ttlSeconds", "snapshot");
        requireObject(object, "user", "snapshot");
        requireArray(object, "groups", "snapshot");
        requireArray(object, "nodes", "snapshot");
        Snapshot snapshot = gson.fromJson(object, Snapshot.class);
        if (snapshot == null) {
            throw new IOException("StonyPerms API returned an empty snapshot");
        }
        return snapshot;
    }

    private JsonObject parseObject(String body, String what) throws IOException {
        JsonElement element;
        try {
            element = JsonParser.parseString(body);
        } catch (RuntimeException error) {
            throw new IOException("StonyPerms API returned invalid JSON for " + what, error);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IOException("StonyPerms API returned a non-object " + what);
        }
        return element.getAsJsonObject();
    }

    private static void require(JsonObject object, String field, String what) throws IOException {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IOException("StonyPerms API " + what + " is missing required field '" + field + "'");
        }
    }

    private static void requireArray(JsonObject object, String field, String what) throws IOException {
        require(object, field, what);
        if (!object.get(field).isJsonArray()) {
            throw new IOException("StonyPerms API " + what + " field '" + field + "' must be an array");
        }
    }

    private static void requireObject(JsonObject object, String field, String what) throws IOException {
        require(object, field, what);
        if (!object.get(field).isJsonObject()) {
            throw new IOException("StonyPerms API " + what + " field '" + field + "' must be an object");
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IOException("StonyPerms API request exceeded " + requestTimeout.toMillis() + " ms", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("StonyPerms API request failed", cause);
        } catch (InterruptedException error) {
            future.cancel(true);
            throw error;
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "stonyperms-bridge/0.1");
    }

    private static String query(Map<String, String> contexts) {
        String key = Contexts.key(contexts);
        return key.isEmpty() ? "" : "?" + key;
    }

    private static ApiException failure(int status) {
        String message = switch (status) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not found";
            case 429 -> "rate limited";
            default -> "API request failed";
        };
        return new ApiException(status, message);
    }

    @Override
    public void close() {
        http.close();
    }

    private record RevisionResponse(long revision) {
    }

    private record CheckResponse(String value, String source, long revision) {
    }
}
