package dev.stonyperms.bridge.api;

import dev.stonyperms.bridge.core.BatchOp;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.CheckResult;
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
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.UsersPage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Public developer API exposed by StonyPermsBridge on Paper (via Bukkit ServicesManager)
 * and Fabric (via StonyPermsBridgeMod.api()).
 *
 * <p>All cached read methods are non-blocking and safe to call on the server thread.
 * Authoritative reads and cross-server mutations return {@link CompletableFuture}s
 * executed on the bridge I/O pool.
 */
public interface StonyPermsBridgeAPI {

    // -------------------------------------------------------------------------
    // Cached synchronous reads (non-blocking)
    // -------------------------------------------------------------------------

    Optional<Snapshot> getCachedSnapshot(UUID uuid, Map<String, String> contexts);

    Decision checkCached(UUID uuid, String permission, Map<String, String> contexts);

    String getPrimaryGroup(UUID uuid, Map<String, String> contexts);

    List<GroupRef> getGroups(UUID uuid, Map<String, String> contexts);

    boolean inGroup(UUID uuid, String group, Map<String, String> contexts);

    Optional<String> getPrefix(UUID uuid, Map<String, String> contexts);

    Optional<String> getSuffix(UUID uuid, Map<String, String> contexts);

    Optional<String> getMeta(UUID uuid, String key, Map<String, String> contexts);

    Map<String, String> getAllMeta(UUID uuid, Map<String, String> contexts);

    // -------------------------------------------------------------------------
    // Authoritative asynchronous reads
    // -------------------------------------------------------------------------

    CompletableFuture<Snapshot> fetchSnapshot(UUID uuid, Map<String, String> contexts);

    CompletableFuture<Map<UUID, Snapshot>> fetchSnapshotsBatch(List<UUID> uuids, Map<String, String> contexts);

    CompletableFuture<UsersPage> listUsers(String groupFilter, int offset, int limit);

    CompletableFuture<Optional<UserRecord>> resolveUser(String username);

    CompletableFuture<UserRecord> fetchUserRecord(String idOrName);

    CompletableFuture<List<GroupRecord>> fetchAllGroups();

    CompletableFuture<GroupRecord> fetchGroup(String name);

    CompletableFuture<List<GroupMember>> fetchGroupMembers(String name);

    CompletableFuture<List<TrackRecord>> fetchTracks();

    CompletableFuture<TrackRecord> fetchTrack(String name);

    CompletableFuture<CheckResult> checkAuthoritative(UUID uuid, String permission, Map<String, String> contexts);

    CompletableFuture<HealthInfo> fetchHealth();

    // -------------------------------------------------------------------------
    // Cross-server writes & admin (fail closed unless isWriteEnabled() is true)
    // -------------------------------------------------------------------------

    boolean isWriteEnabled();

    String defaultActor();

    CompletableFuture<MutationResult> ensureUser(
            UUID uuid, String username, String primaryGroup, boolean createOffline, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> ensureUser(
            UUID uuid, String username, String primaryGroup, boolean createOffline) {
        return ensureUser(uuid, username, primaryGroup, createOffline, defaultActor(), null);
    }

    CompletableFuture<MutationResult> deleteUser(String idOrName, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> deleteUser(String idOrName) {
        return deleteUser(idOrName, defaultActor(), null);
    }

    CompletableFuture<MutationResult> addParent(
            String idOrName,
            String group,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor,
            String idempotencyKey);

    default CompletableFuture<MutationResult> addParent(
            String idOrName, String group, ParentMode mode, long durationSeconds, Map<String, String> contexts, boolean setPrimary) {
        return addParent(idOrName, group, mode, durationSeconds, 0L, contexts, setPrimary, defaultActor(), null);
    }

    CompletableFuture<MutationResult> removeParent(
            String idOrName, String group, Map<String, String> contexts, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> removeParent(String idOrName, String group, Map<String, String> contexts) {
        return removeParent(idOrName, group, contexts, defaultActor(), null);
    }

    CompletableFuture<MutationResult> setPrimaryGroup(
            String idOrName, String group, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> setPrimaryGroup(String idOrName, String group) {
        return setPrimaryGroup(idOrName, group, defaultActor(), null);
    }

    CompletableFuture<MutationResult> setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    default CompletableFuture<MutationResult> setPermission(
            String idOrName, String key, boolean value, long durationSeconds, Map<String, String> contexts) {
        return setPermission(idOrName, key, value, durationSeconds, 0L, contexts, defaultActor(), null);
    }

    CompletableFuture<MutationResult> unsetPermission(
            String idOrName, String key, Map<String, String> contexts, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> unsetPermission(String idOrName, String key, Map<String, String> contexts) {
        return unsetPermission(idOrName, key, contexts, defaultActor(), null);
    }

    CompletableFuture<MutationResult> setMeta(
            String idOrName, String key, String value, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> setMeta(String idOrName, String key, String value) {
        return setMeta(idOrName, key, value, defaultActor(), null);
    }

    CompletableFuture<MutationResult> unsetMeta(
            String idOrName, String key, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> unsetMeta(String idOrName, String key) {
        return unsetMeta(idOrName, key, defaultActor(), null);
    }

    CompletableFuture<MutationResult> setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    default CompletableFuture<MutationResult> setPrefix(
            String idOrName, int priority, String value, long durationSeconds, Map<String, String> contexts) {
        return setPrefix(idOrName, priority, value, durationSeconds, 0L, contexts, defaultActor(), null);
    }

    CompletableFuture<MutationResult> removePrefix(
            String idOrName, int priority, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> removePrefix(String idOrName, int priority) {
        return removePrefix(idOrName, priority, defaultActor(), null);
    }

    CompletableFuture<MutationResult> setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    default CompletableFuture<MutationResult> setSuffix(
            String idOrName, int priority, String value, long durationSeconds, Map<String, String> contexts) {
        return setSuffix(idOrName, priority, value, durationSeconds, 0L, contexts, defaultActor(), null);
    }

    CompletableFuture<MutationResult> removeSuffix(
            String idOrName, int priority, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> removeSuffix(String idOrName, int priority) {
        return removeSuffix(idOrName, priority, defaultActor(), null);
    }

    CompletableFuture<TrackActionResult> promote(
            String idOrName, String track, String actor, String idempotencyKey);

    default CompletableFuture<TrackActionResult> promote(String idOrName, String track) {
        return promote(idOrName, track, defaultActor(), null);
    }

    CompletableFuture<TrackActionResult> demote(
            String idOrName, String track, String actor, String idempotencyKey);

    default CompletableFuture<TrackActionResult> demote(String idOrName, String track) {
        return demote(idOrName, track, defaultActor(), null);
    }

    CompletableFuture<MutationResult> clearUser(
            String idOrName, String scope, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> clearUser(String idOrName, String scope) {
        return clearUser(idOrName, scope, defaultActor(), null);
    }

    CompletableFuture<MutationResult> executeBatch(
            String idOrName, List<BatchOp> operations, String actor, String idempotencyKey);

    default CompletableFuture<MutationResult> executeBatch(String idOrName, List<BatchOp> operations) {
        return executeBatch(idOrName, operations, defaultActor(), null);
    }

    // Group CRUD

    CompletableFuture<MutationResult> createGroup(
            String name, int weight, String displayName, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> patchGroup(
            String name, Integer weight, String displayName, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> deleteGroup(String name, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    CompletableFuture<MutationResult> unsetGroupPermission(
            String name, String key, Map<String, String> contexts, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    CompletableFuture<MutationResult> removeGroupParent(
            String name, String parentGroup, Map<String, String> contexts, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> setGroupMeta(
            String name, String key, String value, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> unsetGroupMeta(
            String name, String key, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    CompletableFuture<MutationResult> removeGroupPrefix(
            String name, int priority, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey);

    CompletableFuture<MutationResult> removeGroupSuffix(
            String name, int priority, String actor, String idempotencyKey);

    // Track CRUD

    CompletableFuture<MutationResult> createTrack(
            String name, List<String> groups, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> putTrack(
            String name, List<String> groups, String actor, String idempotencyKey);

    CompletableFuture<MutationResult> deleteTrack(String name, String actor, String idempotencyKey);

    // Admin

    CompletableFuture<List<LogEntry>> fetchLogs(int limit, String targetType, String target, String actor);

    CompletableFuture<MutationResult> triggerRemoteReload(String actor, String idempotencyKey);

    // Web Suite (StonyPermsWeb)

    CompletableFuture<EditorSessionResult> createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts);

    CompletableFuture<EditorApplyResult> applyEditorEdits(String code, String actor);

    CompletableFuture<TreeSessionResult> createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions);

    CompletableFuture<VerboseSessionResult> createVerboseSession(String actor, String commandAlias);

    // -------------------------------------------------------------------------
    // Reactive event subscriptions
    // -------------------------------------------------------------------------

    AutoCloseable onUserUpdated(BiConsumer<UUID, Snapshot> listener);

    AutoCloseable onChangeEvent(Consumer<ChangeEvent> listener);
}
