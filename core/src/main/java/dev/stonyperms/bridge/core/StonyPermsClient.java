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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP client for the StonyPerms Gate API (read-tier + optional scoped write-tier).
 * Uses only the JDK HttpClient and Gson; no platform classes leak into this module.
 */
public final class StonyPermsClient implements StonyPermsBackend {

    private static final String USER_AGENT = "stonyperms-bridge/0.2";

    private final URI baseUri;
    private final String readToken;
    private final String writeToken;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public StonyPermsClient(URI baseUri, String readToken, Duration connectTimeout, Duration requestTimeout) {
        this(baseUri, readToken, null, connectTimeout, requestTimeout);
    }

    public StonyPermsClient(
            URI baseUri,
            String readToken,
            String writeToken,
            Duration connectTimeout,
            Duration requestTimeout) {
        if (baseUri == null) {
            throw new IllegalArgumentException("baseUri is required");
        }
        if (readToken == null || readToken.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUri = URI.create(base);
        this.readToken = readToken.trim();
        this.writeToken = (writeToken == null || writeToken.isBlank()) ? null : writeToken.trim();
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean hasWriteToken() {
        return writeToken != null && !writeToken.isBlank();
    }

    public URI baseUri() {
        return baseUri;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    // -------------------------------------------------------------------------
    // Read-tier endpoints
    // -------------------------------------------------------------------------

    public HealthInfo fetchHealth() throws IOException, InterruptedException {
        HttpResponse<String> response = send(readRequest("/v1/health").GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "health");
        require(object, "status", "health");
        require(object, "schemaVersion", "health");
        require(object, "revision", "health");
        return gson.fromJson(object, HealthInfo.class);
    }

    @Override
    public FetchResult fetchSnapshot(UUID uuid, Map<String, String> contexts, String etag)
            throws IOException, InterruptedException {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        String path = "/v1/players/" + uuid + contextQuery(contexts);
        HttpRequest.Builder builder = readRequest(path).GET();
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }

        HttpResponse<String> response = send(builder.build());
        String responseEtag = response.headers().firstValue("ETag").orElse(null);
        if (response.statusCode() == 304) {
            return new FetchResult(null, responseEtag != null ? responseEtag : etag, true);
        }
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        Snapshot snapshot = parseSnapshot(parseObject(response.body(), "snapshot"));
        return new FetchResult(snapshot, responseEtag, false);
    }

    public Map<UUID, Snapshot> fetchSnapshotsBatch(List<UUID> uuids, Map<String, String> contexts)
            throws IOException, InterruptedException {
        if (uuids == null || uuids.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuids", uuids.stream().map(UUID::toString).toList());
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        HttpRequest request = readRequest("/v1/players/batch")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "players batch");
        requireObject(object, "players", "players batch");
        JsonObject playersObj = object.getAsJsonObject("players");
        Map<UUID, Snapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : playersObj.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("StonyPerms API players batch entry is not an object");
            }
            UUID id = UUID.fromString(entry.getKey());
            result.put(id, parseSnapshot(entry.getValue().getAsJsonObject()));
        }
        return Map.copyOf(result);
    }

    @Override
    public long fetchRevision() throws IOException, InterruptedException {
        HttpResponse<String> response = send(readRequest("/v1/revision").GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "revision");
        require(object, "revision", "revision");
        return gson.fromJson(object, RevisionResponse.class).revision;
    }

    @Override
    public CheckResult check(UUID uuid, String permission, Map<String, String> contexts)
            throws IOException, InterruptedException {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuid", uuid.toString());
        body.put("permission", permission);
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        HttpRequest request = readRequest("/v1/check")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "check result");
        require(object, "value", "check result");
        require(object, "source", "check result");
        require(object, "revision", "check result");
        CheckResponse parsed = gson.fromJson(object, CheckResponse.class);
        return new CheckResult(parsed.value, parsed.source, parsed.revision);
    }

    public UsersPage listUsers(String groupFilter, int offset, int limit) throws IOException, InterruptedException {
        StringJoiner query = new StringJoiner("&");
        if (groupFilter != null && !groupFilter.isBlank()) {
            query.add("group=" + Contexts.encode(groupFilter.trim()));
        }
        if (offset >= 0) {
            query.add("offset=" + offset);
        }
        if (limit > 0) {
            query.add("limit=" + limit);
        }
        String suffix = query.length() == 0 ? "" : "?" + query;
        HttpResponse<String> response = send(readRequest("/v1/users" + suffix).GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "users list");
        requireArray(object, "users", "users list");
        return gson.fromJson(object, UsersPage.class);
    }

    public UserRecord fetchUser(String idOrName) throws IOException, InterruptedException {
        if (idOrName == null || idOrName.isBlank()) {
            throw new IllegalArgumentException("user identifier is required");
        }
        HttpResponse<String> response = send(
                readRequest("/v1/users/" + encodePathSegment(idOrName.trim())).GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        return parseUserRecordResponse(response.body()).user;
    }

    public Optional<UserRecord> resolveUserByName(String username) throws IOException, InterruptedException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        HttpResponse<String> response = send(
                readRequest("/v1/users/by-name/" + encodePathSegment(username.trim())).GET().build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        return Optional.of(parseUserRecordResponse(response.body()).user);
    }

    public Snapshot fetchSnapshotByName(String username, Map<String, String> contexts)
            throws IOException, InterruptedException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String ctx = Contexts.key(contexts);
        String query = ctx.isEmpty() ? "?effective=true" : "?effective=true&" + ctx;
        HttpResponse<String> response = send(
                readRequest("/v1/users/by-name/" + encodePathSegment(username.trim()) + query).GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        return parseSnapshot(parseObject(response.body(), "snapshot"));
    }

    public List<GroupRecord> fetchGroups() throws IOException, InterruptedException {
        HttpResponse<String> response = send(readRequest("/v1/groups").GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "groups");
        requireArray(object, "groups", "groups");
        GroupsResponse parsed = gson.fromJson(object, GroupsResponse.class);
        return parsed.groups == null ? List.of() : List.copyOf(parsed.groups);
    }

    public GroupRecord fetchGroup(String name) throws IOException, InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("group name is required");
        }
        HttpResponse<String> response = send(
                readRequest("/v1/groups/" + encodePathSegment(name.trim())).GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        return parseGroupResponse(response.body()).group;
    }

    public List<GroupMember> fetchGroupMembers(String name) throws IOException, InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("group name is required");
        }
        HttpResponse<String> response = send(
                readRequest("/v1/groups/" + encodePathSegment(name.trim()) + "/members").GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "group members");
        requireArray(object, "members", "group members");
        GroupMembersResponse parsed = gson.fromJson(object, GroupMembersResponse.class);
        return parsed.members == null ? List.of() : List.copyOf(parsed.members);
    }

    public List<TrackRecord> fetchTracks() throws IOException, InterruptedException {
        HttpResponse<String> response = send(readRequest("/v1/tracks").GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        JsonObject object = parseObject(response.body(), "tracks");
        requireArray(object, "tracks", "tracks");
        TracksResponse parsed = gson.fromJson(object, TracksResponse.class);
        return parsed.tracks == null ? List.of() : List.copyOf(parsed.tracks);
    }

    public TrackRecord fetchTrack(String name) throws IOException, InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("track name is required");
        }
        HttpResponse<String> response = send(
                readRequest("/v1/tracks/" + encodePathSegment(name.trim())).GET().build());
        if (response.statusCode() != 200) {
            throw failure(response.statusCode(), response.body());
        }
        return parseTrackResponse(response.body()).track;
    }

    // -------------------------------------------------------------------------
    // Write & admin endpoints (always require writeToken; fail closed before HTTP)
    // -------------------------------------------------------------------------

    public MutationResult ensureUser(
            UUID uuid,
            String username,
            String primaryGroup,
            boolean createOffline,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        if (uuid != null) {
            body.put("uuid", uuid.toString());
        }
        if (username != null && !username.isBlank()) {
            body.put("username", username.trim());
        }
        if (primaryGroup != null && !primaryGroup.isBlank()) {
            body.put("primaryGroup", primaryGroup.trim());
        }
        body.put("createOffline", createOffline);
        HttpRequest request = writeRequest("/v1/users", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        JsonObject obj = parseObject(response.body(), "ensure user");
        requireObject(obj, "user", "ensure user");
        EnsureUserResponse parsed = gson.fromJson(obj, EnsureUserResponse.class);
        return MutationResult.forEnsureUser(parsed.revision, isReplayed(response), parsed.created, parsed.user);
    }

    public MutationResult deleteUser(String idOrName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return deleteUserWithResult(idOrName, actor, idempotencyKey).mutation();
    }

    public record DeleteUserOutcome(MutationResult mutation, UUID deletedUuid) {
    }

    public DeleteUserOutcome deleteUserWithResult(String idOrName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest("/v1/users/" + encodePathSegment(requireId(idOrName, "user")), actor, idempotencyKey)
                .DELETE()
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        DeleteResponse parsed = gson.fromJson(parseObject(response.body(), "delete user"), DeleteResponse.class);
        UUID deletedUuid = null;
        if (parsed.uuid != null && !parsed.uuid.isBlank()) {
            try {
                deletedUuid = UUID.fromString(parsed.uuid.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new DeleteUserOutcome(
                MutationResult.forDelete(parsed.revision, isReplayed(response), parsed.deleted),
                deletedUuid);
    }

    public MutationResult setParent(
            String idOrName,
            String group,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", group);
        body.put("mode", (mode == null ? ParentMode.ADD : mode).wireName());
        if (durationSeconds > 0) {
            body.put("durationSeconds", durationSeconds);
        }
        if (expiry > 0) {
            body.put("expiry", expiry);
        }
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        if (setPrimary) {
            body.put("setPrimary", true);
        }
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/parents", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult removeParent(
            String idOrName,
            String group,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return removeParent(idOrName, group, contexts, false, actor, idempotencyKey);
    }

    public MutationResult removeParent(
            String idOrName,
            String group,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/parents/" + encodePathSegment(requireId(group, "group"))
                + contextQuery(contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public MutationResult setPrimaryGroup(String idOrName, String group, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = Map.of("group", requireId(group, "group"));
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/primary-group", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .PUT(jsonBody(body))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setPermission(idOrName, key, value, durationSeconds, expiry, contexts, null, actor, idempotencyKey);
    }

    public MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = permissionBody(key, value, durationSeconds, expiry, contexts, modifier);
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/permissions", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult unsetPermission(
            String idOrName,
            String key,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return unsetPermission(idOrName, key, contexts, false, actor, idempotencyKey);
    }

    public MutationResult unsetPermission(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/permissions" + keyAndContextQuery(requireId(key, "permission"), contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public MutationResult setMeta(
            String idOrName, String key, String value, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return setMeta(idOrName, key, value, 0L, 0L, null, null, actor, idempotencyKey);
    }

    public MutationResult setMeta(
            String idOrName,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                        + "/meta/" + encodePathSegment(requireId(key, "meta key")),
                actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .PUT(jsonBody(metaBody(value, durationSeconds, expiry, contexts, modifier)))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult unsetMeta(String idOrName, String key, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return unsetMeta(idOrName, key, null, false, actor, idempotencyKey);
    }

    public MutationResult unsetMeta(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/meta/" + encodePathSegment(requireId(key, "meta key"))
                + contextQuery(contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public MutationResult setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setPrefix(idOrName, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    public MutationResult setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/prefixes", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(affixBody(priority, value, durationSeconds, expiry, contexts, mode, modifier)))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult removePrefix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return removePrefix(idOrName, priority, null, null, false, actor, idempotencyKey);
    }

    public MutationResult removePrefix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = affixDeletePath("/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/prefixes/" + priority, value, contexts, tempOnly);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public MutationResult setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setSuffix(idOrName, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    public MutationResult setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/suffixes", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(affixBody(priority, value, durationSeconds, expiry, contexts, mode, modifier)))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult removeSuffix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return removeSuffix(idOrName, priority, null, null, false, actor, idempotencyKey);
    }

    public MutationResult removeSuffix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = affixDeletePath("/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/suffixes/" + priority, value, contexts, tempOnly);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public TrackActionResult promote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return trackAction(idOrName, track, true, actor, idempotencyKey);
    }

    public TrackActionResult demote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return trackAction(idOrName, track, false, actor, idempotencyKey);
    }

    private TrackActionResult trackAction(
            String idOrName, String track, boolean promote, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        String suffix = promote ? "/promote" : "/demote";
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + suffix, actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(Map.of("track", requireId(track, "track"))))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        JsonObject object = parseObject(response.body(), "track action");
        requireObject(object, "user", "track action");
        return gson.fromJson(object, TrackActionResult.class);
    }

    public MutationResult clearUser(String idOrName, String scope, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest.BodyPublisher publisher;
        if (scope == null || scope.isBlank()) {
            publisher = jsonBody(Map.of());
        } else {
            publisher = jsonBody(Map.of("scope", scope.trim()));
        }
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/clear", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        return executeUserMutation(request);
    }

    public MutationResult batchUser(
            String idOrName, List<BatchOp> operations, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("operations must contain at least one item");
        }
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/batch", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(Map.of("operations", operations)))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        JsonObject object = parseObject(response.body(), "user batch");
        requireObject(object, "user", "user batch");
        UserRecordResponse parsed = gson.fromJson(object, UserRecordResponse.class);
        return MutationResult.forUser(parsed.revision, isReplayed(response), parsed.user);
    }

    // Group CRUD

    public MutationResult createGroup(
            String name, int weight, String displayName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", requireId(name, "group"));
        body.put("weight", weight);
        if (displayName != null && !displayName.isEmpty()) {
            body.put("displayName", displayName);
        }
        HttpRequest request = writeRequest("/v1/groups", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        GroupResponse parsed = parseGroupResponse(response.body());
        return MutationResult.forGroup(parsed.revision, isReplayed(response), response.statusCode() == 201, parsed.group);
    }

    public MutationResult patchGroup(
            String name, Integer weight, String displayName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        if (weight == null && displayName == null) {
            throw new IllegalArgumentException("At least one of weight or displayName is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (weight != null) {
            body.put("weight", weight);
        }
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        HttpRequest request = writeRequest("/v1/groups/" + encodePathSegment(requireId(name, "group")), actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .method("PATCH", jsonBody(body))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult deleteGroup(String name, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest("/v1/groups/" + encodePathSegment(requireId(name, "group")), actor, idempotencyKey)
                .DELETE()
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        DeleteResponse parsed = gson.fromJson(parseObject(response.body(), "delete group"), DeleteResponse.class);
        return MutationResult.forDelete(parsed.revision, isReplayed(response), parsed.deleted);
    }

    public MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setGroupPermission(name, key, value, durationSeconds, expiry, contexts, null, actor, idempotencyKey);
    }

    public MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group")) + "/permissions", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(permissionBody(key, value, durationSeconds, expiry, contexts, modifier)))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult unsetGroupPermission(
            String name,
            String key,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return unsetGroupPermission(name, key, contexts, false, actor, idempotencyKey);
    }

    public MutationResult unsetGroupPermission(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/permissions" + keyAndContextQuery(requireId(key, "permission"), contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    public MutationResult addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", requireId(parentGroup, "parent group"));
        body.put("mode", (mode == null ? ParentMode.ADD : mode).wireName());
        if (durationSeconds > 0) {
            body.put("durationSeconds", durationSeconds);
        }
        if (expiry > 0) {
            body.put("expiry", expiry);
        }
        if (contexts != null && !contexts.isEmpty()) {
            body.put("contexts", contexts);
        }
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group")) + "/parents", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult removeGroupParent(
            String name,
            String parentGroup,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return removeGroupParent(name, parentGroup, contexts, false, actor, idempotencyKey);
    }

    public MutationResult removeGroupParent(
            String name,
            String parentGroup,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/parents/" + encodePathSegment(requireId(parentGroup, "parent group"))
                + contextQuery(contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    public MutationResult setGroupMeta(
            String name, String key, String value, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return setGroupMeta(name, key, value, 0L, 0L, null, null, actor, idempotencyKey);
    }

    public MutationResult setGroupMeta(
            String name,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group"))
                        + "/meta/" + encodePathSegment(requireId(key, "meta key")),
                actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .PUT(jsonBody(metaBody(value, durationSeconds, expiry, contexts, modifier)))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult unsetGroupMeta(String name, String key, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return unsetGroupMeta(name, key, null, false, actor, idempotencyKey);
    }

    public MutationResult unsetGroupMeta(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = appendQueryParam("/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/meta/" + encodePathSegment(requireId(key, "meta key"))
                + contextQuery(contexts), tempOnly ? "tempOnly=true" : null);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    public MutationResult setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setGroupPrefix(name, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    public MutationResult setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group")) + "/prefixes", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(affixBody(priority, value, durationSeconds, expiry, contexts, mode, modifier)))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult removeGroupPrefix(String name, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return removeGroupPrefix(name, priority, null, null, false, actor, idempotencyKey);
    }

    public MutationResult removeGroupPrefix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = affixDeletePath("/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/prefixes/" + priority, value, contexts, tempOnly);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    public MutationResult setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        return setGroupSuffix(name, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    public MutationResult setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group")) + "/suffixes", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(affixBody(priority, value, durationSeconds, expiry, contexts, mode, modifier)))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult removeGroupSuffix(String name, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        return removeGroupSuffix(name, priority, null, null, false, actor, idempotencyKey);
    }

    public MutationResult removeGroupSuffix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        String path = affixDeletePath("/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/suffixes/" + priority, value, contexts, tempOnly);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    public SearchResultRecord search(String query) throws IOException, InterruptedException {
        HttpRequest request = readRequest("/v1/search?q=" + Contexts.encode(requireId(query, "query"))).GET().build();
        HttpResponse<String> response = sendExpecting(request, 200);
        return gson.fromJson(parseObject(response.body(), "search"), SearchResultRecord.class);
    }

    public BulkUpdateSummaryRecord bulkUpdate(
            String scope,
            String action,
            String field,
            String value,
            List<BulkConstraintRecord> constraints,
            boolean dryRun,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope == null || scope.isBlank() ? "all" : scope);
        body.put("action", requireId(action, "action"));
        if (field != null && !field.isBlank()) body.put("field", field);
        if (value != null) body.put("value", value);
        if (constraints != null && !constraints.isEmpty()) body.put("constraints", constraints);
        body.put("dryRun", dryRun);
        HttpRequest request = writeRequest("/v1/bulkupdate", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        return gson.fromJson(parseObject(response.body(), "bulkupdate"), BulkUpdateSummaryRecord.class);
    }

    public MutationResult setUserTrack(
            String idOrName, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", requireId(group, "group"));
        if (contexts != null && !contexts.isEmpty()) body.put("contexts", contexts);
        HttpRequest request = writeRequest(
                "/v1/users/" + encodePathSegment(requireId(idOrName, "user")) + "/tracks/" + encodePathSegment(requireId(track, "track")),
                actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return executeUserMutation(request);
    }

    public MutationResult clearUserTrack(
            String idOrName, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        String path = "/v1/users/" + encodePathSegment(requireId(idOrName, "user"))
                + "/tracks/" + encodePathSegment(requireId(track, "track")) + contextQuery(contexts);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeUserMutation(request);
    }

    public MutationResult setGroupTrack(
            String name, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", requireId(group, "group"));
        if (contexts != null && !contexts.isEmpty()) body.put("contexts", contexts);
        HttpRequest request = writeRequest(
                "/v1/groups/" + encodePathSegment(requireId(name, "group")) + "/tracks/" + encodePathSegment(requireId(track, "track")),
                actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        return executeGroupMutation(request);
    }

    public MutationResult clearGroupTrack(
            String name, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        String path = "/v1/groups/" + encodePathSegment(requireId(name, "group"))
                + "/tracks/" + encodePathSegment(requireId(track, "track")) + contextQuery(contexts);
        HttpRequest request = writeRequest(path, actor, idempotencyKey).DELETE().build();
        return executeGroupMutation(request);
    }

    // Track CRUD

    public MutationResult createTrack(
            String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", requireId(name, "track"));
        body.put("groups", groups == null ? List.of() : groups);
        HttpRequest request = writeRequest("/v1/tracks", actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        TrackResponse parsed = parseTrackResponse(response.body());
        return MutationResult.forTrack(parsed.revision, isReplayed(response), response.statusCode() == 201, parsed.track);
    }

    public MutationResult putTrack(
            String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = Map.of("groups", groups == null ? List.of() : groups);
        HttpRequest request = writeRequest(
                "/v1/tracks/" + encodePathSegment(requireId(name, "track")), actor, idempotencyKey)
                .header("Content-Type", "application/json")
                .PUT(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        TrackResponse parsed = parseTrackResponse(response.body());
        return MutationResult.forTrack(parsed.revision, isReplayed(response), false, parsed.track);
    }

    public MutationResult deleteTrack(String name, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest(
                "/v1/tracks/" + encodePathSegment(requireId(name, "track")), actor, idempotencyKey)
                .DELETE()
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        DeleteResponse parsed = gson.fromJson(parseObject(response.body(), "delete track"), DeleteResponse.class);
        return MutationResult.forDelete(parsed.revision, isReplayed(response), parsed.deleted);
    }

    // Admin endpoints

    public List<LogEntry> fetchLogs(int limit, String targetType, String target, String actor)
            throws IOException, InterruptedException {
        requireWriteToken();
        StringJoiner query = new StringJoiner("&");
        if (limit > 0) {
            query.add("limit=" + limit);
        }
        if (targetType != null && !targetType.isBlank()) {
            query.add("targetType=" + Contexts.encode(targetType.trim()));
        }
        if (target != null && !target.isBlank()) {
            query.add("target=" + Contexts.encode(target.trim()));
        }
        if (actor != null && !actor.isBlank()) {
            query.add("actor=" + Contexts.encode(actor.trim()));
        }
        String suffix = query.length() == 0 ? "" : "?" + query;
        HttpRequest request = writeRequest("/v1/logs" + suffix, null, null).GET().build();
        HttpResponse<String> response = sendExpecting(request, 200);
        JsonObject object = parseObject(response.body(), "logs");
        requireArray(object, "logs", "logs");
        LogsResponse parsed = gson.fromJson(object, LogsResponse.class);
        return parsed.logs == null ? List.of() : List.copyOf(parsed.logs);
    }

    public MutationResult triggerRemoteReload(String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        requireWriteToken();
        HttpRequest request = writeRequest("/v1/reload", actor, idempotencyKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        ReloadResponse parsed = gson.fromJson(parseObject(response.body(), "reload"), ReloadResponse.class);
        return MutationResult.forReload(parsed.revision, isReplayed(response), parsed.reloaded);
    }

    public EditorSessionResult createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        if (actor != null && !actor.isBlank()) body.put("actor", actor.trim());
        if (commandAlias != null && !commandAlias.isBlank()) body.put("commandAlias", commandAlias.trim());
        if (targetType != null && !targetType.isBlank()) body.put("targetType", targetType.trim());
        if (target != null && !target.isBlank()) body.put("target", target.trim());
        if (filter != null && !filter.isBlank()) body.put("filter", filter.trim());
        if (extraPermissions != null && !extraPermissions.isEmpty()) body.put("extraPermissions", extraPermissions);
        if (extraContexts != null && !extraContexts.isEmpty()) body.put("extraContexts", extraContexts);

        HttpRequest request = writeRequest("/v1/editor/sessions", actor, null)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        JsonObject obj = parseObject(response.body(), "editor session");
        require(obj, "token", "editor session");
        require(obj, "url", "editor session");
        return gson.fromJson(obj, EditorSessionResult.class);
    }

    public EditorApplyResult applyEditorEdits(String code, String actor)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", requireId(code, "applyedits code"));
        if (actor != null && !actor.isBlank()) body.put("actor", actor.trim());

        HttpRequest request = writeRequest("/v1/editor/applyedits", actor, null)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200);
        return gson.fromJson(parseObject(response.body(), "apply edits"), EditorApplyResult.class);
    }

    public TreeSessionResult createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("root", (root == null || root.isBlank()) ? "." : root.trim());
        if (actor != null && !actor.isBlank()) body.put("actor", actor.trim());
        if (commandAlias != null && !commandAlias.isBlank()) body.put("commandAlias", commandAlias.trim());
        if (extraPermissions != null && !extraPermissions.isEmpty()) body.put("extraPermissions", extraPermissions);

        HttpRequest request = writeRequest("/v1/tree/sessions", actor, null)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        JsonObject obj = parseObject(response.body(), "tree session");
        require(obj, "token", "tree session");
        require(obj, "url", "tree session");
        return gson.fromJson(obj, TreeSessionResult.class);
    }

    public VerboseSessionResult createVerboseSession(String actor, String commandAlias)
            throws IOException, InterruptedException {
        requireWriteToken();
        Map<String, Object> body = new LinkedHashMap<>();
        if (actor != null && !actor.isBlank()) body.put("actor", actor.trim());
        if (commandAlias != null && !commandAlias.isBlank()) body.put("commandAlias", commandAlias.trim());

        HttpRequest request = writeRequest("/v1/verbose/sessions", actor, null)
                .header("Content-Type", "application/json")
                .POST(jsonBody(body))
                .build();
        HttpResponse<String> response = sendExpecting(request, 200, 201);
        JsonObject obj = parseObject(response.body(), "verbose session");
        require(obj, "token", "verbose session");
        require(obj, "url", "verbose session");
        return gson.fromJson(obj, VerboseSessionResult.class);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void requireWriteToken() {
        if (!hasWriteToken()) {
            throw new IllegalStateException("StonyPerms write token is not configured (writes disabled)");
        }
    }

    private MutationResult executeUserMutation(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = sendExpecting(request, 200);
        UserRecordResponse parsed = parseUserRecordResponse(response.body());
        return MutationResult.forUser(parsed.revision, isReplayed(response), parsed.user);
    }

    private MutationResult executeGroupMutation(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = sendExpecting(request, 200);
        GroupResponse parsed = parseGroupResponse(response.body());
        return MutationResult.forGroup(parsed.revision, isReplayed(response), false, parsed.group);
    }

    private HttpResponse<String> sendExpecting(HttpRequest request, int... expectedStatuses)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(request);
        for (int status : expectedStatuses) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        throw failure(response.statusCode(), response.body());
    }

    private UserRecordResponse parseUserRecordResponse(String body) throws IOException {
        JsonObject object = parseObject(body, "user record");
        requireObject(object, "user", "user record");
        return gson.fromJson(object, UserRecordResponse.class);
    }

    private GroupResponse parseGroupResponse(String body) throws IOException {
        JsonObject object = parseObject(body, "group");
        requireObject(object, "group", "group");
        return gson.fromJson(object, GroupResponse.class);
    }

    private TrackResponse parseTrackResponse(String body) throws IOException {
        JsonObject object = parseObject(body, "track");
        requireObject(object, "track", "track");
        return gson.fromJson(object, TrackResponse.class);
    }

    private Snapshot parseSnapshot(JsonObject object) throws IOException {
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

    private HttpRequest.Builder readRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + readToken)
                .header("User-Agent", USER_AGENT);
    }

    private HttpRequest.Builder writeRequest(String path, String actor, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + writeToken)
                .header("User-Agent", USER_AGENT);
        if (actor != null && !actor.isBlank()) {
            builder.header("X-StonyPerms-Actor", actor.trim());
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("Idempotency-Key", idempotencyKey.trim());
        }
        return builder;
    }

    private HttpRequest.BodyPublisher jsonBody(Object payload) {
        return HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> permissionBody(
            String key, boolean value, long durationSeconds, long expiry, Map<String, String> contexts, String modifier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", requireId(key, "permission"));
        body.put("value", value);
        if (durationSeconds > 0) body.put("durationSeconds", durationSeconds);
        if (expiry > 0) body.put("expiry", expiry);
        if (contexts != null && !contexts.isEmpty()) body.put("contexts", contexts);
        if (modifier != null && !modifier.isBlank()) body.put("modifier", modifier.trim());
        return body;
    }

    private static Map<String, Object> metaBody(
            String value, long durationSeconds, long expiry, Map<String, String> contexts, String modifier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value == null ? "" : value);
        if (durationSeconds > 0) body.put("durationSeconds", durationSeconds);
        if (expiry > 0) body.put("expiry", expiry);
        if (contexts != null && !contexts.isEmpty()) body.put("contexts", contexts);
        if (modifier != null && !modifier.isBlank()) body.put("modifier", modifier.trim());
        return body;
    }

    private static Map<String, Object> affixBody(
            int priority, String value, long durationSeconds, long expiry, Map<String, String> contexts, String mode, String modifier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("priority", priority);
        body.put("value", value == null ? "" : value);
        if (durationSeconds > 0) body.put("durationSeconds", durationSeconds);
        if (expiry > 0) body.put("expiry", expiry);
        if (contexts != null && !contexts.isEmpty()) body.put("contexts", contexts);
        if (mode != null && !mode.isBlank()) body.put("mode", mode.trim());
        if (modifier != null && !modifier.isBlank()) body.put("modifier", modifier.trim());
        return body;
    }

    private static String appendQueryParam(String path, String param) {
        if (param == null || param.isEmpty()) {
            return path;
        }
        return path + (path.indexOf('?') >= 0 ? "&" : "?") + param;
    }

    private static String affixDeletePath(String basePath, String value, Map<String, String> contexts, boolean tempOnly) {
        String path = basePath + contextQuery(contexts);
        if (value != null && !value.isEmpty()) {
            path = appendQueryParam(path, "value=" + Contexts.encode(value));
        }
        if (tempOnly) {
            path = appendQueryParam(path, "tempOnly=true");
        }
        return path;
    }

    private static String contextQuery(Map<String, String> contexts) {
        String key = Contexts.key(contexts);
        return key.isEmpty() ? "" : "?" + key;
    }

    private static String keyAndContextQuery(String key, Map<String, String> contexts) {
        String ctx = Contexts.key(contexts);
        String base = "?key=" + Contexts.encode(key);
        return ctx.isEmpty() ? base : base + "&" + ctx;
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static boolean isReplayed(HttpResponse<?> response) {
        return "true".equalsIgnoreCase(response.headers().firstValue("X-Idempotency-Replayed").orElse(""));
    }

    static String encodePathSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int ch = b & 0xff;
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append((char) ch);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((ch >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(ch & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static ApiException failure(int status, String body) {
        String message = switch (status) {
            case 400 -> "bad request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not found";
            case 409 -> "conflict";
            case 429 -> "rate limited";
            case 503 -> "service unavailable";
            default -> "API request failed";
        };
        return new ApiException(status, ApiException.parseSafeErrorCode(body), message);
    }

    @Override
    public void close() {
        http.close();
    }

    private record RevisionResponse(long revision) {
    }

    private record CheckResponse(String value, String source, long revision) {
    }

    private record UserRecordResponse(long revision, UserRecord user) {
    }

    private record EnsureUserResponse(long revision, boolean created, UserRecord user) {
    }

    private record DeleteResponse(long revision, boolean deleted, String uuid) {
    }

    private record GroupsResponse(long revision, List<GroupRecord> groups) {
    }

    private record GroupResponse(long revision, GroupRecord group) {
    }

    private record GroupMembersResponse(long revision, String group, List<GroupMember> members) {
    }

    private record TracksResponse(long revision, List<TrackRecord> tracks) {
    }

    private record TrackResponse(long revision, TrackRecord track) {
    }

    private record LogsResponse(long revision, List<LogEntry> logs) {
    }

    private record ReloadResponse(long revision, boolean reloaded) {
    }
}
