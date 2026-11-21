package dev.stonyperms.bridge.api;

import dev.stonyperms.bridge.core.BatchOp;
import dev.stonyperms.bridge.core.BulkConstraintRecord;
import dev.stonyperms.bridge.core.BulkUpdateSummaryRecord;
import dev.stonyperms.bridge.core.SearchResultRecord;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.Decision;
import dev.stonyperms.bridge.core.EditorApplyResult;
import dev.stonyperms.bridge.core.EditorSessionResult;
import dev.stonyperms.bridge.core.TreeSessionResult;
import dev.stonyperms.bridge.core.VerboseSessionResult;
import dev.stonyperms.bridge.core.GroupMember;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.HealthInfo;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsBackend;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.UsersPage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Default thread-safe implementation of {@link StonyPermsBridgeAPI}. */
public final class DefaultStonyPermsBridgeAPI implements StonyPermsBridgeAPI {

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws Exception;
    }

    private final BridgeService service;
    private final StonyPermsBackend client;
    private final Executor ioExecutor;
    private final boolean writeEnabled;
    private final String defaultActor;
    private final Consumer<UUID> userRefreshHook;
    private final Runnable globalRefreshHook;

    private final CopyOnWriteArrayList<BiConsumer<UUID, Snapshot>> userListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ChangeEvent>> changeListeners = new CopyOnWriteArrayList<>();

    public DefaultStonyPermsBridgeAPI(
            BridgeService service,
            StonyPermsBackend client,
            Executor ioExecutor,
            boolean writeEnabled,
            String defaultActor,
            Consumer<UUID> userRefreshHook,
            Runnable globalRefreshHook) {
        this.service = Objects.requireNonNull(service, "service");
        this.client = Objects.requireNonNull(client, "client");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.writeEnabled = writeEnabled;
        this.defaultActor = (defaultActor == null || defaultActor.isBlank()) ? "bridge" : defaultActor.trim();
        this.userRefreshHook = userRefreshHook == null ? ignored -> {} : userRefreshHook;
        this.globalRefreshHook = globalRefreshHook == null ? () -> {} : globalRefreshHook;
    }

    // -------------------------------------------------------------------------
    // Cached synchronous reads
    // -------------------------------------------------------------------------

    @Override
    public Optional<Snapshot> getCachedSnapshot(UUID uuid, Map<String, String> contexts) {
        if (uuid == null) {
            return Optional.empty();
        }
        SnapshotCache.Lookup lookup = service.lookup(uuid, safeContexts(contexts));
        if (!usable(lookup)) {
            return Optional.empty();
        }
        return Optional.of(lookup.snapshot());
    }

    @Override
    public Decision checkCached(UUID uuid, String permission, Map<String, String> contexts) {
        if (uuid == null) {
            return Decision.undefined("bridge:null-uuid");
        }
        return service.resolve(uuid, safeContexts(contexts), permission);
    }

    @Override
    public String getPrimaryGroup(UUID uuid, Map<String, String> contexts) {
        return getCachedSnapshot(uuid, contexts)
                .map(s -> s.user().primaryGroup())
                .orElse("default");
    }

    @Override
    public List<GroupRef> getGroups(UUID uuid, Map<String, String> contexts) {
        return getCachedSnapshot(uuid, contexts)
                .map(Snapshot::groups)
                .orElse(List.of());
    }

    @Override
    public boolean inGroup(UUID uuid, String group, Map<String, String> contexts) {
        if (group == null || group.isBlank()) {
            return false;
        }
        Optional<Snapshot> snapshotOpt = getCachedSnapshot(uuid, contexts);
        if (snapshotOpt.isEmpty()) {
            return false;
        }
        Snapshot snapshot = snapshotOpt.get();
        if (snapshot.user().primaryGroup().equalsIgnoreCase(group)) {
            return true;
        }
        for (GroupRef ref : snapshot.groups()) {
            if (ref.name().equalsIgnoreCase(group)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<String> getPrefix(UUID uuid, Map<String, String> contexts) {
        return uuid == null ? Optional.empty() : service.option(uuid, safeContexts(contexts), "prefix");
    }

    @Override
    public Optional<String> getSuffix(UUID uuid, Map<String, String> contexts) {
        return uuid == null ? Optional.empty() : service.option(uuid, safeContexts(contexts), "suffix");
    }

    @Override
    public Optional<String> getMeta(UUID uuid, String key, Map<String, String> contexts) {
        if (uuid == null || key == null || key.isEmpty()) {
            return Optional.empty();
        }
        return getCachedSnapshot(uuid, contexts)
                .map(s -> s.user().meta().get(key))
                .filter(v -> !v.isEmpty());
    }

    @Override
    public Map<String, String> getAllMeta(UUID uuid, Map<String, String> contexts) {
        return getCachedSnapshot(uuid, contexts)
                .map(s -> s.user().meta())
                .orElse(Map.of());
    }

    // -------------------------------------------------------------------------
    // Authoritative async reads
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Snapshot> fetchSnapshot(UUID uuid, Map<String, String> contexts) {
        Map<String, String> ctx = safeContexts(contexts);
        return supplyAsync(() -> {
            service.refresh(uuid, ctx);
            SnapshotCache.Lookup lookup = service.lookup(uuid, ctx);
            if (!lookup.present()) {
                throw new IllegalStateException("Snapshot missing after refresh for " + uuid);
            }
            Snapshot snapshot = lookup.snapshot();
            notifyUserUpdated(uuid, snapshot);
            return snapshot;
        });
    }

    @Override
    public CompletableFuture<Map<UUID, Snapshot>> fetchSnapshotsBatch(List<UUID> uuids, Map<String, String> contexts) {
        Map<String, String> ctx = safeContexts(contexts);
        String ctxKey = Contexts.key(ctx);
        return supplyAsync(() -> {
            Map<UUID, Snapshot> result = client.fetchSnapshotsBatch(uuids, ctx);
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Snapshot> entry : result.entrySet()) {
                service.cache().put(new SnapshotCache.Key(entry.getKey(), ctxKey), entry.getValue(), null, now);
                notifyUserUpdated(entry.getKey(), entry.getValue());
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<UsersPage> listUsers(String groupFilter, int offset, int limit) {
        return supplyAsync(() -> client.listUsers(groupFilter, offset, limit));
    }

    @Override
    public CompletableFuture<Optional<UserRecord>> resolveUser(String username) {
        return supplyAsync(() -> client.resolveUserByName(username));
    }

    @Override
    public CompletableFuture<UserRecord> fetchUserRecord(String idOrName) {
        return supplyAsync(() -> client.fetchUser(idOrName));
    }

    @Override
    public CompletableFuture<List<GroupRecord>> fetchAllGroups() {
        return supplyAsync(client::fetchGroups);
    }

    @Override
    public CompletableFuture<GroupRecord> fetchGroup(String name) {
        return supplyAsync(() -> client.fetchGroup(name));
    }

    @Override
    public CompletableFuture<List<GroupMember>> fetchGroupMembers(String name) {
        return supplyAsync(() -> client.fetchGroupMembers(name));
    }

    @Override
    public CompletableFuture<List<TrackRecord>> fetchTracks() {
        return supplyAsync(client::fetchTracks);
    }

    @Override
    public CompletableFuture<TrackRecord> fetchTrack(String name) {
        return supplyAsync(() -> client.fetchTrack(name));
    }

    @Override
    public CompletableFuture<CheckResult> checkAuthoritative(
            UUID uuid, String permission, Map<String, String> contexts) {
        return supplyAsync(() -> service.authoritativeCheck(uuid, permission, safeContexts(contexts)));
    }

    @Override
    public CompletableFuture<HealthInfo> fetchHealth() {
        return supplyAsync(client::fetchHealth);
    }

    @Override
    public CompletableFuture<SearchResultRecord> search(String query) {
        return supplyAsync(() -> client.search(query));
    }

    // -------------------------------------------------------------------------
    // Cross-server writes & admin
    // -------------------------------------------------------------------------

    @Override
    public boolean isStandalone() {
        return client.isStandalone();
    }

    @Override
    public boolean isWriteEnabled() {
        return writeEnabled && client.hasWriteToken();
    }

    @Override
    public CompletableFuture<MutationResult> renameGroup(
            String oldName, String newName, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.renameGroup(oldName, newName, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> renameTrack(
            String oldName, String newName, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.renameTrack(oldName, newName, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<Void> startVerboseRecording(String actor, String targetFilter, String queryFilter) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            client.startVerboseRecording(effectiveActor(actor), targetFilter, queryFilter);
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> stopVerboseRecording() {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(client::stopVerboseRecording);
    }

    @Override
    public boolean isVerboseRecording() {
        return client.isVerboseRecording();
    }

    @Override
    public String defaultActor() {
        return defaultActor;
    }

    @Override
    public CompletableFuture<MutationResult> ensureUser(
            UUID uuid, String username, String primaryGroup, boolean createOffline, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.ensureUser(uuid, username, primaryGroup, createOffline, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> deleteUser(String idOrName, String actor, String idempotencyKey) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            StonyPermsClient.DeleteUserOutcome outcome = client.deleteUserWithResult(
                    idOrName, effectiveActor(actor), idempotencyKey);
            UUID parsed = outcome.deletedUuid() != null ? outcome.deletedUuid() : tryParseUuid(idOrName);
            if (parsed != null) {
                service.cache().invalidate(parsed);
                userRefreshHook.accept(parsed);
            } else {
                service.cache().clear();
                globalRefreshHook.run();
            }
            return outcome.mutation();
        });
    }

    @Override
    public CompletableFuture<MutationResult> addParent(
            String idOrName,
            String group,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor,
            String idempotencyKey) {
        return writeUserAsync(() -> client.setParent(
                idOrName, group, mode, durationSeconds, expiry, safeContexts(contexts), setPrimary,
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeParent(
            String idOrName, String group, Map<String, String> contexts, String actor, String idempotencyKey) {
        return removeParent(idOrName, group, contexts, false, actor, idempotencyKey);
    }

    @Override
    public CompletableFuture<MutationResult> removeParent(
            String idOrName, String group, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.removeParent(
                idOrName, group, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setPrimaryGroup(
            String idOrName, String group, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.setPrimaryGroup(
                idOrName, group, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return setPermission(idOrName, key, value, durationSeconds, expiry, contexts, null, actor, idempotencyKey);
    }

    @Override
    public CompletableFuture<MutationResult> setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) {
        return writeUserAsync(() -> client.setPermission(
                idOrName, key, value, durationSeconds, expiry, safeContexts(contexts),
                modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetPermission(
            String idOrName, String key, Map<String, String> contexts, String actor, String idempotencyKey) {
        return unsetPermission(idOrName, key, contexts, false, actor, idempotencyKey);
    }

    @Override
    public CompletableFuture<MutationResult> unsetPermission(
            String idOrName, String key, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.unsetPermission(
                idOrName, key, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setMeta(
            String idOrName, String key, String value, String actor, String idempotencyKey) {
        return setMeta(idOrName, key, value, 0L, 0L, null, null, actor, idempotencyKey);
    }

    @Override
    public CompletableFuture<MutationResult> setMeta(
            String idOrName,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) {
        return writeUserAsync(() -> client.setMeta(
                idOrName, key, value, durationSeconds, expiry, safeContexts(contexts), modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetMeta(
            String idOrName, String key, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.unsetMeta(
                idOrName, key, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeUserAsync(() -> client.setPrefix(
                idOrName, priority, value, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removePrefix(
            String idOrName, int priority, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.removePrefix(
                idOrName, priority, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeUserAsync(() -> client.setSuffix(
                idOrName, priority, value, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeSuffix(
            String idOrName, int priority, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.removeSuffix(
                idOrName, priority, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<TrackActionResult> promote(
            String idOrName, String track, String actor, String idempotencyKey) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            TrackActionResult result = client.promote(idOrName, track, effectiveActor(actor), idempotencyKey);
            if (result.user() != null && result.user().uuid() != null) {
                userRefreshHook.accept(result.user().uuid());
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<TrackActionResult> demote(
            String idOrName, String track, String actor, String idempotencyKey) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            TrackActionResult result = client.demote(idOrName, track, effectiveActor(actor), idempotencyKey);
            if (result.user() != null && result.user().uuid() != null) {
                userRefreshHook.accept(result.user().uuid());
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<MutationResult> clearUser(
            String idOrName, String scope, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.clearUser(
                idOrName, scope, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> executeBatch(
            String idOrName, List<BatchOp> operations, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.batchUser(
                idOrName, operations, effectiveActor(actor), idempotencyKey));
    }

    // Group writes

    @Override
    public CompletableFuture<MutationResult> createGroup(
            String name, int weight, String displayName, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.createGroup(
                name, weight, displayName, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> patchGroup(
            String name, Integer weight, String displayName, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.patchGroup(
                name, weight, displayName, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> deleteGroup(String name, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.deleteGroup(name, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupPermission(
                name, key, value, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetGroupPermission(
            String name, String key, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.unsetGroupPermission(
                name, key, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeGlobalAsync(() -> client.addGroupParent(
                name, parentGroup, mode, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupParent(
            String name, String parentGroup, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupParent(
                name, parentGroup, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupMeta(
            String name, String key, String value, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupMeta(
                name, key, value, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetGroupMeta(
            String name, String key, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.unsetGroupMeta(
                name, key, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupPrefix(
                name, priority, value, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupPrefix(
            String name, int priority, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupPrefix(
                name, priority, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupSuffix(
                name, priority, value, durationSeconds, expiry, safeContexts(contexts),
                effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupSuffix(
            String name, int priority, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupSuffix(
                name, priority, effectiveActor(actor), idempotencyKey));
    }

    // Track writes

    @Override
    public CompletableFuture<MutationResult> createTrack(
            String name, List<String> groups, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.createTrack(
                name, groups, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> putTrack(
            String name, List<String> groups, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.putTrack(
                name, groups, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> deleteTrack(String name, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.deleteTrack(name, effectiveActor(actor), idempotencyKey));
    }

    // Admin

    @Override
    public CompletableFuture<List<LogEntry>> fetchLogs(int limit, String targetType, String target, String actor) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> client.fetchLogs(limit, targetType, target, actor));
    }

    @Override
    public CompletableFuture<MutationResult> triggerRemoteReload(String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.triggerRemoteReload(effectiveActor(actor), idempotencyKey));
    }

    // Web Suite (StonyPermsWeb)

    @Override
    public CompletableFuture<EditorSessionResult> createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> client.createEditorSession(
                effectiveActor(actor), commandAlias, targetType, target, filter, extraPermissions, extraContexts));
    }

    @Override
    public CompletableFuture<EditorApplyResult> applyEditorEdits(String code, String actor) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            EditorApplyResult res = client.applyEditorEdits(code, effectiveActor(actor));
            globalRefreshHook.run();
            return res;
        });
    }

    @Override
    public CompletableFuture<TreeSessionResult> createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> client.createTreeSession(root, effectiveActor(actor), commandAlias, extraPermissions));
    }

    @Override
    public CompletableFuture<VerboseSessionResult> createVerboseSession(String actor, String commandAlias) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> client.createVerboseSession(effectiveActor(actor), commandAlias));
    }

    @Override
    public CompletableFuture<MutationResult> unsetMeta(
            String idOrName, String key, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.unsetMeta(idOrName, key, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setPrefix(
            String idOrName, int priority, String value, long durationSeconds, long expiry,
            Map<String, String> contexts, String mode, String modifier, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.setPrefix(idOrName, priority, value, durationSeconds, expiry, safeContexts(contexts), mode, modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removePrefix(
            String idOrName, int priority, String value, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.removePrefix(idOrName, priority, value, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setSuffix(
            String idOrName, int priority, String value, long durationSeconds, long expiry,
            Map<String, String> contexts, String mode, String modifier, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.setSuffix(idOrName, priority, value, durationSeconds, expiry, safeContexts(contexts), mode, modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeSuffix(
            String idOrName, int priority, String value, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.removeSuffix(idOrName, priority, value, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupPermission(
            String name, String key, boolean value, long durationSeconds, long expiry,
            Map<String, String> contexts, String modifier, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupPermission(name, key, value, durationSeconds, expiry, safeContexts(contexts), modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetGroupPermission(
            String name, String key, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.unsetGroupPermission(name, key, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupParent(
            String name, String parentGroup, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupParent(name, parentGroup, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupMeta(
            String name, String key, String value, long durationSeconds, long expiry,
            Map<String, String> contexts, String modifier, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupMeta(name, key, value, durationSeconds, expiry, safeContexts(contexts), modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> unsetGroupMeta(
            String name, String key, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.unsetGroupMeta(name, key, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupPrefix(
            String name, int priority, String value, long durationSeconds, long expiry,
            Map<String, String> contexts, String mode, String modifier, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupPrefix(name, priority, value, durationSeconds, expiry, safeContexts(contexts), mode, modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupPrefix(
            String name, int priority, String value, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupPrefix(name, priority, value, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupSuffix(
            String name, int priority, String value, long durationSeconds, long expiry,
            Map<String, String> contexts, String mode, String modifier, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupSuffix(name, priority, value, durationSeconds, expiry, safeContexts(contexts), mode, modifier, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> removeGroupSuffix(
            String name, int priority, String value, Map<String, String> contexts, boolean tempOnly, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.removeGroupSuffix(name, priority, value, safeContexts(contexts), tempOnly, effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<BulkUpdateSummaryRecord> bulkUpdate(
            String scope, String action, String field, String value,
            List<BulkConstraintRecord> constraints, boolean dryRun, String actor, String idempotencyKey) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            BulkUpdateSummaryRecord summary = client.bulkUpdate(scope, action, field, value, constraints, dryRun, effectiveActor(actor), idempotencyKey);
            if (!dryRun) {
                globalRefreshHook.run();
            }
            return summary;
        });
    }

    @Override
    public CompletableFuture<MutationResult> setUserTrack(
            String idOrName, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.setUserTrack(idOrName, track, group, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> clearUserTrack(
            String idOrName, String track, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeUserAsync(() -> client.clearUserTrack(idOrName, track, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> setGroupTrack(
            String name, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.setGroupTrack(name, track, group, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    @Override
    public CompletableFuture<MutationResult> clearGroupTrack(
            String name, String track, Map<String, String> contexts, String actor, String idempotencyKey) {
        return writeGlobalAsync(() -> client.clearGroupTrack(name, track, safeContexts(contexts), effectiveActor(actor), idempotencyKey));
    }

    // -------------------------------------------------------------------------
    // Reactive listeners & dispatch helpers
    // -------------------------------------------------------------------------

    @Override
    public AutoCloseable onUserUpdated(BiConsumer<UUID, Snapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        userListeners.add(listener);
        return () -> userListeners.remove(listener);
    }

    @Override
    public AutoCloseable onChangeEvent(Consumer<ChangeEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    public void notifyUserUpdated(UUID uuid, Snapshot snapshot) {
        if (uuid == null || snapshot == null) {
            return;
        }
        for (BiConsumer<UUID, Snapshot> listener : userListeners) {
            try {
                listener.accept(uuid, snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void notifyChangeEvent(ChangeEvent event) {
        if (event == null) {
            return;
        }
        for (Consumer<ChangeEvent> listener : changeListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private CompletableFuture<MutationResult> writeUserAsync(IoSupplier<MutationResult> task) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            MutationResult result = task.get();
            if (result.user() != null && result.user().uuid() != null) {
                userRefreshHook.accept(result.user().uuid());
            }
            return result;
        });
    }

    private CompletableFuture<MutationResult> writeGlobalAsync(IoSupplier<MutationResult> task) {
        if (!isWriteEnabled()) {
            return writesDisabledFailure();
        }
        return supplyAsync(() -> {
            MutationResult result = task.get();
            globalRefreshHook.run();
            return result;
        });
    }

    private <T> CompletableFuture<T> supplyAsync(IoSupplier<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.get();
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, ioExecutor);
    }

    private static <T> CompletableFuture<T> writesDisabledFailure() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("StonyPerms writes are disabled on this server (write.enabled=false or no write token)"));
    }

    private boolean usable(SnapshotCache.Lookup lookup) {
        return lookup.present() && (lookup.state() != SnapshotCache.State.STALE || service.useStaleOnError());
    }

    private String effectiveActor(String actor) {
        return (actor == null || actor.isBlank()) ? defaultActor : actor.trim();
    }

    private static Map<String, String> safeContexts(Map<String, String> contexts) {
        return contexts == null ? Map.of() : contexts;
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
