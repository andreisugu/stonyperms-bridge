package dev.stonyperms.bridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String responseEtag = response.headers().firstValue("ETag").orElse(null);
        if (response.statusCode() == 304) {
            return new FetchResult(null, responseEtag != null ? responseEtag : etag, true);
        }
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        Snapshot snapshot = gson.fromJson(response.body(), Snapshot.class);
        if (snapshot == null) {
            throw new IOException("StonyPerms API returned an empty snapshot");
        }
        return new FetchResult(snapshot, responseEtag, false);
    }

    @Override
    public long fetchRevision() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                request("/v1/revision").header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        RevisionResponse revision = gson.fromJson(response.body(), RevisionResponse.class);
        if (revision == null) {
            throw new IOException("StonyPerms API returned an empty revision");
        }
        return revision.revision;
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
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode());
        }
        CheckResponse parsed = gson.fromJson(response.body(), CheckResponse.class);
        if (parsed == null) {
            throw new IOException("StonyPerms API returned an empty check result");
        }
        return new CheckResult(parsed.value, parsed.source, parsed.revision);
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
