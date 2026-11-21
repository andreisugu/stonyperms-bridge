package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.BatchOp;
import dev.stonyperms.bridge.core.BulkConstraintRecord;
import dev.stonyperms.bridge.core.BulkUpdateSummaryRecord;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.EditorApplyResult;
import dev.stonyperms.bridge.core.EditorSessionResult;
import dev.stonyperms.bridge.core.GroupMember;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.HealthInfo;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.SearchResultRecord;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.StonyPermsBackend;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.TreeSessionResult;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.UsersPage;
import dev.stonyperms.bridge.core.VerboseSessionResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** {@link StonyPermsBackend} backed by the standalone {@link LocalPermissionManager}. */
public final class LocalStonyPermsBackend implements StonyPermsBackend {

    private final LocalPermissionManager manager;

    public LocalStonyPermsBackend(LocalPermissionManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    public static LocalStonyPermsBackend create(StandaloneConfig config, Consumer<ChangeEvent> listener)
            throws IOException {
        return new LocalStonyPermsBackend(new LocalPermissionManager(config, listener));
    }

    public LocalPermissionManager manager() {
        return manager;
    }

    /** Ensures or updates an online player record synchronously during join. */
    public void ensureOnlineUser(UUID uuid, String username, String actor) throws IOException {
        manager.resolveOrCreateUser(uuid, username, null, false, actor);
    }

    public void recordResolvedCheck(UUID uuid, String permission, String value, String source) {
        manager.recordResolvedCheck(uuid, permission, value, source);
    }

    @Override
    public boolean isStandalone() {
        return true;
    }

    @Override
    public boolean hasWriteToken() {
        return true;
    }

    @Override
    public FetchResult fetchSnapshot(UUID uuid, Map<String, String> contexts, String etag) {
        Snapshot snap = manager.effectiveSnapshot(uuid, contexts);
        String nextEtag = manager.generation() + "-" + snap.revision();
        if (etag != null && etag.equals(nextEtag)) {
            return new FetchResult(null, nextEtag, true);
        }
        return new FetchResult(snap, nextEtag, false);
    }

    @Override
    public long fetchRevision() {
        return manager.revision();
    }

    @Override
    public CheckResult check(UUID uuid, String permission, Map<String, String> contexts) {
        return manager.check(uuid, permission, contexts);
    }

    @Override
    public HealthInfo fetchHealth() {
        return manager.health();
    }

    @Override
    public Map<UUID, Snapshot> fetchSnapshotsBatch(List<UUID> uuids, Map<String, String> contexts) {
        if (uuids == null || uuids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Snapshot> out = new LinkedHashMap<>(uuids.size());
        for (UUID id : uuids) {
            if (id != null && !out.containsKey(id)) {
                out.put(id, manager.effectiveSnapshot(id, contexts));
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public UsersPage listUsers(String groupFilter, int offset, int limit) {
        return manager.listUsers(groupFilter, offset, limit);
    }

    @Override
    public UserRecord fetchUser(String idOrName) throws IOException {
        return manager.fetchUser(idOrName);
    }

    @Override
    public Optional<UserRecord> resolveUserByName(String username) {
        return manager.resolveUserByName(username);
    }

    @Override
    public Snapshot fetchSnapshotByName(String username, Map<String, String> contexts) throws IOException {
        return manager.fetchSnapshotByName(username, contexts);
    }

    @Override
    public List<GroupRecord> fetchGroups() {
        return manager.fetchGroups();
    }

    @Override
    public GroupRecord fetchGroup(String name) throws IOException {
        return manager.fetchGroup(name);
    }

    @Override
    public List<GroupMember> fetchGroupMembers(String name) throws IOException {
        return manager.fetchGroupMembers(name);
    }

    @Override
    public List<TrackRecord> fetchTracks() {
        return manager.fetchTracks();
    }

    @Override
    public TrackRecord fetchTrack(String name) throws IOException {
        return manager.fetchTrack(name);
    }

    @Override
    public MutationResult ensureUser(
            UUID uuid, String username, String primaryGroup, boolean createOffline, String actor, String idempotencyKey)
            throws IOException {
        return manager.resolveOrCreateUser(uuid, username, primaryGroup, createOffline, actor);
    }

    @Override
    public MutationResult deleteUser(String idOrName, String actor, String idempotencyKey) throws IOException {
        return manager.deleteUser(idOrName, actor).mutation();
    }

    @Override
    public StonyPermsClient.DeleteUserOutcome deleteUserWithResult(
            String idOrName, String actor, String idempotencyKey) throws IOException {
        return manager.deleteUser(idOrName, actor);
    }

    @Override
    public MutationResult setParent(
            String idOrName,
            String group,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.setParent(idOrName, group, mode, durationSeconds, expiry, contexts, setPrimary, actor);
    }

    @Override
    public MutationResult removeParent(
            String idOrName, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return removeParent(idOrName, group, contexts, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removeParent(
            String idOrName,
            String group,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeParent(idOrName, group, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setPrimaryGroup(String idOrName, String group, String actor, String idempotencyKey)
            throws IOException {
        return manager.setPrimaryGroup(idOrName, group, actor);
    }

    @Override
    public MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setPermission(idOrName, key, value, durationSeconds, expiry, contexts, null, actor, idempotencyKey);
    }

    @Override
    public MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.setPermission(idOrName, key, value, durationSeconds, expiry, contexts, modifier, actor);
    }

    @Override
    public MutationResult unsetPermission(
            String idOrName, String key, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return unsetPermission(idOrName, key, contexts, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult unsetPermission(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.unsetPermission(idOrName, key, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setMeta(String idOrName, String key, String value, String actor, String idempotencyKey)
            throws IOException {
        return setMeta(idOrName, key, value, 0L, 0L, null, null, actor, idempotencyKey);
    }

    @Override
    public MutationResult setMeta(
            String idOrName,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.setMeta(idOrName, key, value, durationSeconds, expiry, contexts, modifier, actor);
    }

    @Override
    public MutationResult unsetMeta(String idOrName, String key, String actor, String idempotencyKey)
            throws IOException {
        return unsetMeta(idOrName, key, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult unsetMeta(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.unsetMeta(idOrName, key, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setPrefix(idOrName, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    @Override
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
            String idempotencyKey) throws IOException {
        return manager.setAffix(idOrName, true, priority, value, durationSeconds, expiry, contexts, mode, modifier, actor);
    }

    @Override
    public MutationResult removePrefix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException {
        return removePrefix(idOrName, priority, null, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removePrefix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeAffix(idOrName, true, priority, value, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setSuffix(idOrName, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    @Override
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
            String idempotencyKey) throws IOException {
        return manager.setAffix(idOrName, false, priority, value, durationSeconds, expiry, contexts, mode, modifier, actor);
    }

    @Override
    public MutationResult removeSuffix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException {
        return removeSuffix(idOrName, priority, null, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removeSuffix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeAffix(idOrName, false, priority, value, contexts, tempOnly, actor);
    }

    @Override
    public TrackActionResult promote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException {
        return manager.stepUserTrack(idOrName, track, true, actor);
    }

    @Override
    public TrackActionResult demote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException {
        return manager.stepUserTrack(idOrName, track, false, actor);
    }

    @Override
    public MutationResult clearUser(String idOrName, String scope, String actor, String idempotencyKey)
            throws IOException {
        return manager.clearUser(idOrName, scope, actor);
    }

    @Override
    public MutationResult batchUser(
            String idOrName, List<BatchOp> operations, String actor, String idempotencyKey) throws IOException {
        return manager.batchUser(idOrName, operations, actor);
    }

    @Override
    public MutationResult createGroup(
            String name, int weight, String displayName, String actor, String idempotencyKey) throws IOException {
        return manager.createGroup(name, weight, displayName, actor);
    }

    @Override
    public MutationResult patchGroup(
            String name, Integer weight, String displayName, String actor, String idempotencyKey) throws IOException {
        return manager.patchGroup(name, weight, displayName, actor);
    }

    @Override
    public MutationResult deleteGroup(String name, String actor, String idempotencyKey) throws IOException {
        return manager.deleteGroup(name, actor);
    }

    @Override
    public MutationResult renameGroup(String oldName, String newName, String actor, String idempotencyKey)
            throws IOException {
        return manager.renameGroup(oldName, newName, actor);
    }

    @Override
    public MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setGroupPermission(name, key, value, durationSeconds, expiry, contexts, null, actor, idempotencyKey);
    }

    @Override
    public MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.setGroupPermission(name, key, value, durationSeconds, expiry, contexts, modifier, actor);
    }

    @Override
    public MutationResult unsetGroupPermission(
            String name, String key, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return unsetGroupPermission(name, key, contexts, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult unsetGroupPermission(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.unsetGroupPermission(name, key, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.addGroupParent(name, parentGroup, mode, durationSeconds, expiry, contexts, actor);
    }

    @Override
    public MutationResult removeGroupParent(
            String name, String parentGroup, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return removeGroupParent(name, parentGroup, contexts, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removeGroupParent(
            String name,
            String parentGroup,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeGroupParent(name, parentGroup, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setGroupMeta(String name, String key, String value, String actor, String idempotencyKey)
            throws IOException {
        return setGroupMeta(name, key, value, 0L, 0L, null, null, actor, idempotencyKey);
    }

    @Override
    public MutationResult setGroupMeta(
            String name,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.setGroupMeta(name, key, value, durationSeconds, expiry, contexts, modifier, actor);
    }

    @Override
    public MutationResult unsetGroupMeta(String name, String key, String actor, String idempotencyKey)
            throws IOException {
        return unsetGroupMeta(name, key, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult unsetGroupMeta(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.unsetGroupMeta(name, key, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setGroupPrefix(name, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    @Override
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
            String idempotencyKey) throws IOException {
        return manager.setGroupAffix(name, true, priority, value, durationSeconds, expiry, contexts, mode, modifier, actor);
    }

    @Override
    public MutationResult removeGroupPrefix(String name, int priority, String actor, String idempotencyKey)
            throws IOException {
        return removeGroupPrefix(name, priority, null, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removeGroupPrefix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeGroupAffix(name, true, priority, value, contexts, tempOnly, actor);
    }

    @Override
    public MutationResult setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException {
        return setGroupSuffix(name, priority, value, durationSeconds, expiry, contexts, null, null, actor, idempotencyKey);
    }

    @Override
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
            String idempotencyKey) throws IOException {
        return manager.setGroupAffix(name, false, priority, value, durationSeconds, expiry, contexts, mode, modifier, actor);
    }

    @Override
    public MutationResult removeGroupSuffix(String name, int priority, String actor, String idempotencyKey)
            throws IOException {
        return removeGroupSuffix(name, priority, null, null, false, actor, idempotencyKey);
    }

    @Override
    public MutationResult removeGroupSuffix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.removeGroupAffix(name, false, priority, value, contexts, tempOnly, actor);
    }

    @Override
    public SearchResultRecord search(String query) {
        return manager.search(query);
    }

    @Override
    public BulkUpdateSummaryRecord bulkUpdate(
            String scope,
            String action,
            String field,
            String value,
            List<BulkConstraintRecord> constraints,
            boolean dryRun,
            String actor,
            String idempotencyKey) throws IOException {
        return manager.bulkUpdate(scope, action, field, value, constraints, dryRun, actor);
    }

    @Override
    public MutationResult setUserTrack(
            String idOrName, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return manager.setUserTrack(idOrName, track, group, contexts, actor);
    }

    @Override
    public MutationResult clearUserTrack(
            String idOrName, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return manager.clearUserTrack(idOrName, track, contexts, actor);
    }

    @Override
    public MutationResult setGroupTrack(
            String name, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return manager.setGroupTrack(name, track, group, contexts, actor);
    }

    @Override
    public MutationResult clearGroupTrack(
            String name, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException {
        return manager.clearGroupTrack(name, track, contexts, actor);
    }

    @Override
    public MutationResult createTrack(String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException {
        return manager.createTrack(name, groups, actor);
    }

    @Override
    public MutationResult putTrack(String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException {
        return manager.putTrack(name, groups, actor);
    }

    @Override
    public MutationResult deleteTrack(String name, String actor, String idempotencyKey) throws IOException {
        return manager.deleteTrack(name, actor);
    }

    @Override
    public MutationResult renameTrack(String oldName, String newName, String actor, String idempotencyKey)
            throws IOException {
        return manager.renameTrack(oldName, newName, actor);
    }

    @Override
    public List<LogEntry> fetchLogs(int limit, String targetType, String target, String actor) {
        return manager.queryLogs(limit, targetType, target, actor);
    }

    @Override
    public MutationResult triggerRemoteReload(String actor, String idempotencyKey) throws IOException {
        return manager.reload(actor);
    }

    @Override
    public EditorSessionResult createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts) throws IOException {
        return manager.createEditorSession(actor, commandAlias, targetType, target, filter, extraPermissions, extraContexts);
    }

    @Override
    public EditorApplyResult applyEditorEdits(String code, String actor) throws IOException {
        return manager.applyEditorCode(code, actor);
    }

    @Override
    public TreeSessionResult createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions) throws IOException {
        return manager.createTreeSession(root, actor, commandAlias, extraPermissions);
    }

    @Override
    public VerboseSessionResult createVerboseSession(String actor, String commandAlias) throws IOException {
        return manager.createVerboseSession(actor, commandAlias);
    }

    @Override
    public void startVerboseRecording(String actor, String targetFilter, String queryFilter) {
        manager.engine().startVerboseRecording(actor, targetFilter, queryFilter);
    }

    @Override
    public int stopVerboseRecording() {
        return manager.engine().stopVerboseRecording().entries().size();
    }

    @Override
    public boolean isVerboseRecording() {
        return manager.engine().isVerboseRecording();
    }

    @Override
    public void close() {
        manager.close();
    }
}
