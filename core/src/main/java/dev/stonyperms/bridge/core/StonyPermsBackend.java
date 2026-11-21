package dev.stonyperms.bridge.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Full permission backend abstraction implemented by both {@link StonyPermsClient} (Gate HTTP API)
 * and {@code LocalStonyPermsBackend} (standalone local engine & storage).
 */
public interface StonyPermsBackend extends SnapshotSource, AutoCloseable {

    boolean hasWriteToken();

    default boolean isStandalone() {
        return false;
    }

    HealthInfo fetchHealth() throws IOException, InterruptedException;

    Map<UUID, Snapshot> fetchSnapshotsBatch(List<UUID> uuids, Map<String, String> contexts)
            throws IOException, InterruptedException;

    UsersPage listUsers(String groupFilter, int offset, int limit)
            throws IOException, InterruptedException;

    UserRecord fetchUser(String idOrName) throws IOException, InterruptedException;

    Optional<UserRecord> resolveUserByName(String username) throws IOException, InterruptedException;

    Snapshot fetchSnapshotByName(String username, Map<String, String> contexts)
            throws IOException, InterruptedException;

    List<GroupRecord> fetchGroups() throws IOException, InterruptedException;

    GroupRecord fetchGroup(String name) throws IOException, InterruptedException;

    List<GroupMember> fetchGroupMembers(String name) throws IOException, InterruptedException;

    List<TrackRecord> fetchTracks() throws IOException, InterruptedException;

    TrackRecord fetchTrack(String name) throws IOException, InterruptedException;

    MutationResult ensureUser(
            UUID uuid,
            String username,
            String primaryGroup,
            boolean createOffline,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult deleteUser(String idOrName, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    StonyPermsClient.DeleteUserOutcome deleteUserWithResult(String idOrName, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult setParent(
            String idOrName,
            String group,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeParent(
            String idOrName,
            String group,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeParent(
            String idOrName,
            String group,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setPrimaryGroup(String idOrName, String group, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetPermission(
            String idOrName,
            String key,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetPermission(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setMeta(String idOrName, String key, String value, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult setMeta(
            String idOrName,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetMeta(String idOrName, String key, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult unsetMeta(
            String idOrName,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setPrefix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removePrefix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult removePrefix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setSuffix(
            String idOrName,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeSuffix(String idOrName, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult removeSuffix(
            String idOrName,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    TrackActionResult promote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    TrackActionResult demote(String idOrName, String track, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult clearUser(String idOrName, String scope, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult batchUser(String idOrName, List<BatchOp> operations, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult createGroup(String name, int weight, String displayName, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult patchGroup(String name, Integer weight, String displayName, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult deleteGroup(String name, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    default MutationResult renameGroup(String oldName, String newName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("renameGroup requires standalone mode or Gate /sp");
    }

    MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetGroupPermission(
            String name,
            String key,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetGroupPermission(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeGroupParent(
            String name,
            String parentGroup,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeGroupParent(
            String name,
            String parentGroup,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupMeta(String name, String key, String value, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult setGroupMeta(
            String name,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult unsetGroupMeta(String name, String key, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult unsetGroupMeta(
            String name,
            String key,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupPrefix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeGroupPrefix(String name, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult removeGroupPrefix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setGroupSuffix(
            String name,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult removeGroupSuffix(String name, int priority, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult removeGroupSuffix(
            String name,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    SearchResultRecord search(String query) throws IOException, InterruptedException;

    BulkUpdateSummaryRecord bulkUpdate(
            String scope,
            String action,
            String field,
            String value,
            List<BulkConstraintRecord> constraints,
            boolean dryRun,
            String actor,
            String idempotencyKey) throws IOException, InterruptedException;

    MutationResult setUserTrack(
            String idOrName, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult clearUserTrack(
            String idOrName, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult setGroupTrack(
            String name, String track, String group, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult clearGroupTrack(
            String name, String track, Map<String, String> contexts, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult createTrack(String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult putTrack(String name, List<String> groups, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    MutationResult deleteTrack(String name, String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    default MutationResult renameTrack(String oldName, String newName, String actor, String idempotencyKey)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("renameTrack requires standalone mode or Gate /sp");
    }

    List<LogEntry> fetchLogs(int limit, String targetType, String target, String actor)
            throws IOException, InterruptedException;

    MutationResult triggerRemoteReload(String actor, String idempotencyKey)
            throws IOException, InterruptedException;

    EditorSessionResult createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts) throws IOException, InterruptedException;

    EditorApplyResult applyEditorEdits(String code, String actor)
            throws IOException, InterruptedException;

    TreeSessionResult createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions)
            throws IOException, InterruptedException;

    VerboseSessionResult createVerboseSession(String actor, String commandAlias)
            throws IOException, InterruptedException;

    default void startVerboseRecording(String actor, String targetFilter, String queryFilter) {
        throw new UnsupportedOperationException("verbose recording control requires standalone mode");
    }

    default int stopVerboseRecording() {
        throw new UnsupportedOperationException("verbose recording control requires standalone mode");
    }

    default boolean isVerboseRecording() {
        return false;
    }

    @Override
    void close();
}
