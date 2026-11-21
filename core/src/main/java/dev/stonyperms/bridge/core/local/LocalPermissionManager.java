package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.BatchOp;
import dev.stonyperms.bridge.core.BulkConstraintRecord;
import dev.stonyperms.bridge.core.BulkUpdateSummaryRecord;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.DurationParser;
import dev.stonyperms.bridge.core.EditorApplyResult;
import dev.stonyperms.bridge.core.EditorSessionResult;
import dev.stonyperms.bridge.core.GroupMember;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.HealthInfo;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.SearchHitRecord;
import dev.stonyperms.bridge.core.SearchResultRecord;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.TreeSessionResult;
import dev.stonyperms.bridge.core.UserInfo;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.UserSummary;
import dev.stonyperms.bridge.core.UsersPage;
import dev.stonyperms.bridge.core.VerboseSessionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Thread-safe standalone StonyPerms permission manager coordinating disk persistence,
 * evaluation engine, track progressions, bulk updates, audit logs, expiry cleanup,
 * and portable {@code sp1_} StonyPermsWeb sessions.
 */
public final class LocalPermissionManager implements AutoCloseable {

    public static final int SCHEMA_VERSION = 1;
    public static final int LOCAL_SNAPSHOT_TTL_SECONDS = 3600;
    private static final int MAX_EDITOR_USERS = 500;
    private static final int MAX_KNOWN_PERMISSIONS = 5000;
    private static final int MAX_EDITOR_CONTEXTS = 16;
    private static final int MAX_EDITOR_TEXT_LEN = 128;
    private static final int MAX_EDITOR_PERM_LEN = 256;
    private static final long WEB_SESSION_TTL_MS = 2L * 3600L * 1000L;

    private static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("^[a-z0-9_+-]{1,64}$");
    private static final Pattern EDITOR_CTX_KEY_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<String> DEFAULT_KNOWN_PERMISSIONS = List.of(
            "*",
            "gate.*",
            "gate.command.alert",
            "gate.command.find",
            "gate.command.glist",
            "gate.command.ping",
            "gate.command.send",
            "gate.command.server",
            "stonyperms.*",
            "stonyperms.admin",
            "stonyperms.applyedits",
            "stonyperms.bulkupdate",
            "stonyperms.check",
            "stonyperms.creategroup",
            "stonyperms.createtrack",
            "stonyperms.deletegroup",
            "stonyperms.deletetrack",
            "stonyperms.editor",
            "stonyperms.group",
            "stonyperms.info",
            "stonyperms.listgroups",
            "stonyperms.listtracks",
            "stonyperms.log",
            "stonyperms.search",
            "stonyperms.sync",
            "stonyperms.track",
            "stonyperms.tree",
            "stonyperms.use",
            "stonyperms.user",
            "stonyperms.verbose",
            "stonyperms.bridge.admin",
            "stonyperms.bridge.write",
            "minecraft.command.gamemode",
            "minecraft.command.teleport",
            "minecraft.command.kick",
            "minecraft.command.ban");

    private final StandaloneConfig config;
    private final JsonLocalStorage storage;
    private final LocalPermissionEngine engine;
    private final Consumer<ChangeEvent> eventListener;

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<UUID, UserRecord> users = new HashMap<>();
    private final Map<String, UUID> nameToUuid = new HashMap<>();
    private final Map<String, GroupRecord> groups = new HashMap<>();
    private final Map<String, TrackRecord> tracks = new HashMap<>();
    private final List<LogEntry> logs = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong(1L);
    private final String generation = randomHex(8);
    private final ScheduledExecutorService cleanupScheduler;

    public LocalPermissionManager(StandaloneConfig config, Consumer<ChangeEvent> eventListener) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.storage = new JsonLocalStorage(config.storageDir());
        this.engine = new LocalPermissionEngine();
        this.engine.setPrimaryGroupCalculation(config.primaryGroupCalculation());
        this.engine.setStackConfig(config.stackConfig());
        this.eventListener = eventListener == null ? ignored -> {} : eventListener;

        reload("");

        if (config.expiryCleanupSeconds() > 0) {
            this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stonyperms-local-expiry");
                t.setDaemon(true);
                return t;
            });
            long period = config.expiryCleanupSeconds();
            this.cleanupScheduler.scheduleAtFixedRate(
                    () -> cleanupExpired(Instant.now().getEpochSecond()),
                    period, period, TimeUnit.SECONDS);
        } else {
            this.cleanupScheduler = null;
        }
    }

    public StandaloneConfig config() {
        return config;
    }

    public LocalPermissionEngine engine() {
        return engine;
    }

    public JsonLocalStorage storage() {
        return storage;
    }

    public long revision() {
        return revision.get();
    }

    public String generation() {
        return generation;
    }

    // -------------------------------------------------------------------------
    // Lifecycle & Stats
    // -------------------------------------------------------------------------

    public MutationResult reload(String actor) throws IOException {
        JsonLocalStorage.DataBundle bundle = storage.loadAll();
        long rev;
        rw.writeLock().lock();
        try {
            users.clear();
            nameToUuid.clear();
            groups.clear();
            tracks.clear();
            logs.clear();

            groups.putAll(bundle.groups());
            tracks.putAll(bundle.tracks());
            logs.addAll(bundle.logs());
            users.putAll(bundle.users());
            for (UserRecord u : users.values()) {
                if (!u.username().isEmpty()) {
                    nameToUuid.put(u.username().toLowerCase(Locale.ROOT), u.uuid());
                }
            }
            if (actor != null && !actor.isBlank()) {
                logActionLocked(actor.trim(), "general", "system", "Reloaded permissions from storage");
            }
            rev = revision.incrementAndGet();
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "reload", "", "", "", "", safeActor(actor), nowEpoch()));
        return MutationResult.forReload(rev, false, true);
    }

    public HealthInfo health() {
        rw.readLock().lock();
        try {
            return new HealthInfo("ok", SCHEMA_VERSION, revision.get(), true, false);
        } finally {
            rw.readLock().unlock();
        }
    }

    public int usersCount() {
        rw.readLock().lock();
        try {
            return users.size();
        } finally {
            rw.readLock().unlock();
        }
    }

    public int groupsCount() {
        rw.readLock().lock();
        try {
            return groups.size();
        } finally {
            rw.readLock().unlock();
        }
    }

    public int tracksCount() {
        rw.readLock().lock();
        try {
            return tracks.size();
        } finally {
            rw.readLock().unlock();
        }
    }

    public void cleanupExpired(long nowEpochSeconds) {
        boolean changed = false;
        long rev = 0L;
        rw.writeLock().lock();
        try {
            for (Map.Entry<UUID, UserRecord> e : List.copyOf(users.entrySet())) {
                UserRecord u = e.getValue();
                CleanResult<UserRecord> res = cleanUserExpired(u, nowEpochSeconds);
                if (res.cleaned > 0) {
                    try {
                        storage.saveUser(res.value);
                        users.put(u.uuid(), res.value);
                        changed = true;
                    } catch (IOException ignored) {
                    }
                }
            }
            for (Map.Entry<String, GroupRecord> e : List.copyOf(groups.entrySet())) {
                GroupRecord g = e.getValue();
                CleanResult<GroupRecord> res = cleanGroupExpired(g, nowEpochSeconds);
                if (res.cleaned > 0) {
                    try {
                        storage.saveGroup(res.value);
                        groups.put(g.name(), res.value);
                        changed = true;
                    } catch (IOException ignored) {
                    }
                }
            }
            if (changed) {
                rev = revision.incrementAndGet();
            }
        } finally {
            rw.writeLock().unlock();
        }
        if (changed) {
            notifyEvent(new ChangeEvent(rev, "expiry_cleanup", "", "", "", "", "", nowEpochSeconds));
        }
    }

    // -------------------------------------------------------------------------
    // Reads & Evaluation
    // -------------------------------------------------------------------------

    public Snapshot effectiveSnapshot(UUID uuid, Map<String, String> contexts) {
        if (uuid == null || NIL_UUID.equals(uuid)) {
            throw new IllegalArgumentException("uuid is required");
        }
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        rw.readLock().lock();
        try {
            long rev = revision.get();
            UserRecord stored = users.get(uuid);
            boolean found = stored != null;
            UserRecord effUser = found
                    ? stored
                    : new UserRecord(uuid, "", config.defaultGroup(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of());

            List<GroupRecord> resolved = engine.resolveInheritedGroups(effUser, groups, cleanCtx, now);
            String primary = engine.resolvePrimaryGroupAt(effUser, groups, cleanCtx, config.defaultGroup(), now);
            Map<String, String> allMeta = engine.resolveAllMetaAt(effUser, groups, cleanCtx, now);
            String prefix = engine.getPrefixAt(effUser, groups, cleanCtx, now);
            String suffix = engine.getSuffixAt(effUser, groups, cleanCtx, now);

            List<GroupRef> groupRefs = new ArrayList<>(resolved.size());
            for (GroupRecord g : resolved) {
                groupRefs.add(new GroupRef(g.name(), g.effectiveDisplayName(), g.weight()));
            }

            List<Node> effectiveNodes = new ArrayList<>();
            for (Node n : effUser.nodes()) {
                if (!n.isExpired(now) && n.matchesContext(cleanCtx)) {
                    effectiveNodes.add(new Node(n.key(), n.value(), n.contexts(), n.expiry(), "user", 0, true));
                }
            }
            for (GroupRecord g : resolved) {
                for (Node n : g.nodes()) {
                    if (!n.isExpired(now) && n.matchesContext(cleanCtx)) {
                        effectiveNodes.add(new Node(
                                n.key(), n.value(), n.contexts(), n.expiry(),
                                "group:" + g.name(), g.weight(), false));
                    }
                }
            }

            UserInfo info = new UserInfo(
                    uuid.toString(),
                    effUser.username(),
                    primary,
                    !found,
                    allMeta,
                    prefix,
                    suffix);

            return new Snapshot(
                    SCHEMA_VERSION,
                    rev,
                    Instant.ofEpochSecond(now).toString(),
                    LOCAL_SNAPSHOT_TTL_SECONDS,
                    info,
                    groupRefs,
                    effectiveNodes);
        } finally {
            rw.readLock().unlock();
        }
    }

    public Snapshot fetchSnapshotByName(String username, Map<String, String> contexts) throws IOException {
        UserRecord found = resolveUserByName(username)
                .orElseThrow(() -> new ApiException(404, "user_not_found", "user not found"));
        return effectiveSnapshot(found.uuid(), contexts);
    }

    public CheckResult check(UUID uuid, String permission, Map<String, String> contexts) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid is required");
        }
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        rw.readLock().lock();
        try {
            long rev = revision.get();
            UserRecord stored = users.get(uuid);
            UserRecord effUser = stored != null
                    ? stored
                    : new UserRecord(uuid, "", config.defaultGroup(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of());
            LocalPermissionEngine.EngineCheckResult res = engine.evaluateAt(effUser, groups, permission, cleanCtx, now);
            engine.recordCheck(effUser.username(), permission, res.result(), res.source());
            return new CheckResult(res.result().wireName(), res.source(), rev);
        } finally {
            rw.readLock().unlock();
        }
    }

    public void recordResolvedCheck(UUID uuid, String permission, String result, String source) {
        if (!engine.isVerboseRecording()) {
            return;
        }
        String subject = uuid == null ? "" : uuid.toString();
        if (uuid != null) {
            rw.readLock().lock();
            try {
                UserRecord u = users.get(uuid);
                if (u != null && !u.username().isEmpty()) {
                    subject = u.username();
                }
            } finally {
                rw.readLock().unlock();
            }
        }
        dev.stonyperms.bridge.core.Tri tri = switch (result == null ? "" : result.toLowerCase(Locale.ROOT)) {
            case "true" -> dev.stonyperms.bridge.core.Tri.TRUE;
            case "false" -> dev.stonyperms.bridge.core.Tri.FALSE;
            default -> dev.stonyperms.bridge.core.Tri.UNDEFINED;
        };
        engine.recordCheck(subject, permission, tri, source);
    }

    public Optional<UserRecord> resolveUserByName(String username) {
        String clean = username == null ? "" : username.trim();
        if (clean.isEmpty()) {
            return Optional.empty();
        }
        UUID directUuid = tryParseUuid(clean);
        rw.readLock().lock();
        try {
            if (directUuid != null) {
                return Optional.ofNullable(users.get(directUuid));
            }
            UUID mapped = nameToUuid.get(clean.toLowerCase(Locale.ROOT));
            return mapped == null ? Optional.empty() : Optional.ofNullable(users.get(mapped));
        } finally {
            rw.readLock().unlock();
        }
    }

    public UserRecord fetchUser(String idOrName) throws ApiException {
        return resolveUserByName(idOrName)
                .orElseThrow(() -> new ApiException(404, "user_not_found", "user not found"));
    }

    public UsersPage listUsers(String groupFilter, int offset, int limit) {
        String cleanGroup = groupFilter == null ? "" : groupFilter.trim().toLowerCase(Locale.ROOT);
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? 50 : limit;

        rw.readLock().lock();
        try {
            long rev = revision.get();
            long now = nowEpoch();
            List<UserRecord> matched = new ArrayList<>();
            for (UserRecord u : users.values()) {
                if (!cleanGroup.isEmpty() && !userInGroup(u, cleanGroup, now)) {
                    continue;
                }
                matched.add(u);
            }
            matched.sort(Comparator.<UserRecord, String>comparing(u -> u.username().toLowerCase(Locale.ROOT))
                    .thenComparing(u -> u.uuid().toString()));
            int total = matched.size();
            if (safeOffset >= total) {
                return new UsersPage(rev, total, safeLimit, safeOffset, List.of());
            }
            int end = Math.min(total, safeOffset + safeLimit);
            List<UserSummary> page = new ArrayList<>(end - safeOffset);
            for (int i = safeOffset; i < end; i++) {
                UserRecord u = matched.get(i);
                page.add(new UserSummary(u.uuid(), u.username(), u.primaryGroup(), u.parents(), u.nodes().size()));
            }
            return new UsersPage(rev, total, safeLimit, safeOffset, page);
        } finally {
            rw.readLock().unlock();
        }
    }

    public List<GroupRecord> fetchGroups() {
        rw.readLock().lock();
        try {
            List<GroupRecord> list = new ArrayList<>(groups.values());
            list.sort(Comparator.<GroupRecord>comparingInt(GroupRecord::weight).reversed()
                    .thenComparing(GroupRecord::name));
            return list;
        } finally {
            rw.readLock().unlock();
        }
    }

    public GroupRecord fetchGroup(String name) throws ApiException {
        String key = requireEntityKey(name, "group");
        rw.readLock().lock();
        try {
            GroupRecord g = groups.get(key);
            if (g == null) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            return g;
        } finally {
            rw.readLock().unlock();
        }
    }

    public List<GroupMember> fetchGroupMembers(String name) throws ApiException {
        String key = requireEntityKey(name, "group");
        rw.readLock().lock();
        try {
            if (!groups.containsKey(key)) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            long now = nowEpoch();
            List<GroupMember> members = new ArrayList<>();
            for (UserRecord u : users.values()) {
                if (u.primaryGroup().equalsIgnoreCase(key)) {
                    members.add(new GroupMember(u.uuid(), u.username(), u.primaryGroup(), true, 0L, Map.of()));
                    continue;
                }
                ParentEntry best = null;
                for (ParentEntry p : u.parents()) {
                    if (!p.group().equalsIgnoreCase(key) || p.isExpired(now)) {
                        continue;
                    }
                    if (best == null || (p.permanent() && !best.permanent())
                            || (!best.permanent() && p.expiry() > best.expiry())) {
                        best = p;
                    }
                }
                if (best != null) {
                    members.add(new GroupMember(
                            u.uuid(), u.username(), u.primaryGroup(),
                            best.permanent(), best.expiry(), best.contexts()));
                }
            }
            members.sort(Comparator.<GroupMember, String>comparing(m -> m.username().toLowerCase(Locale.ROOT))
                    .thenComparing(m -> m.uuid().toString()));
            return members;
        } finally {
            rw.readLock().unlock();
        }
    }

    public List<TrackRecord> fetchTracks() {
        rw.readLock().lock();
        try {
            List<TrackRecord> out = new ArrayList<>(tracks.values());
            out.sort(Comparator.comparing(TrackRecord::name));
            return out;
        } finally {
            rw.readLock().unlock();
        }
    }

    public TrackRecord fetchTrack(String name) throws ApiException {
        String key = requireEntityKey(name, "track");
        rw.readLock().lock();
        try {
            TrackRecord t = tracks.get(key);
            if (t == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            return t;
        } finally {
            rw.readLock().unlock();
        }
    }

    public List<LogEntry> queryLogs(int limit, String targetType, String target, String actor) {
        String tt = targetType == null ? "" : targetType.trim();
        String tg = target == null ? "" : target.trim();
        String ac = actor == null ? "" : actor.trim();

        rw.readLock().lock();
        try {
            List<LogEntry> out = new ArrayList<>();
            for (int i = logs.size() - 1; i >= 0; i--) {
                LogEntry entry = logs.get(i);
                if (!tt.isEmpty() && !entry.targetType().equalsIgnoreCase(tt)) {
                    continue;
                }
                if (!tg.isEmpty() && !entry.target().equalsIgnoreCase(tg)) {
                    continue;
                }
                if (!ac.isEmpty() && !entry.actor().equalsIgnoreCase(ac)) {
                    continue;
                }
                out.add(entry);
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
            }
            return out;
        } finally {
            rw.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // User Mutations
    // -------------------------------------------------------------------------

    public MutationResult resolveOrCreateUser(
            UUID uuid, String username, String primaryGroup, boolean createOffline, String actor) throws IOException {
        UUID resolvedId = uuid;
        String cleanName = username == null ? "" : username.trim();
        if (resolvedId == null) {
            if (cleanName.isEmpty()) {
                throw new ApiException(400, "invalid_request", "uuid or username is required");
            }
            Optional<UserRecord> existingByName = resolveUserByName(cleanName);
            if (existingByName.isPresent()) {
                resolvedId = existingByName.get().uuid();
            } else if (createOffline) {
                resolvedId = offlinePlayerUuid(cleanName);
            } else {
                throw new ApiException(404, "user_not_found", "user not found");
            }
        }
        String effPrimary = (primaryGroup == null || primaryGroup.isBlank())
                ? config.defaultGroup() : primaryGroup.trim().toLowerCase(Locale.ROOT);

        final UUID finalUuid = resolvedId;
        boolean created = false;
        UserRecord saved;
        long rev;

        rw.writeLock().lock();
        try {
            if (!groups.containsKey(effPrimary)) {
                throw new ApiException(404, "group_not_found", "group not found: " + effPrimary);
            }
            UserRecord existing = users.get(finalUuid);
            if (existing != null) {
                if (cleanName.isEmpty() || existing.username().equals(cleanName)) {
                    return MutationResult.forEnsureUser(revision.get(), false, false, existing);
                }
                UserRecord updated = new UserRecord(
                        existing.uuid(), cleanName, existing.primaryGroup(),
                        existing.parents(), existing.nodes(), existing.meta(),
                        existing.prefixes(), existing.suffixes(), existing.metaEntries());
                storage.saveUser(updated);
                if (!existing.username().isEmpty() && !existing.username().equalsIgnoreCase(cleanName)) {
                    nameToUuid.remove(existing.username().toLowerCase(Locale.ROOT));
                }
                users.put(finalUuid, updated);
                nameToUuid.put(cleanName.toLowerCase(Locale.ROOT), finalUuid);
                rev = revision.incrementAndGet();
                logActionLocked(actor, "user", userLogTarget(updated), "Updated username to " + cleanName);
                saved = updated;
            } else {
                created = true;
                UserRecord createdRec = new UserRecord(
                        finalUuid, cleanName, effPrimary,
                        List.of(), List.of(), Map.of(), List.of(), List.of(), List.of());
                storage.saveUser(createdRec);
                users.put(finalUuid, createdRec);
                if (!cleanName.isEmpty()) {
                    nameToUuid.put(cleanName.toLowerCase(Locale.ROOT), finalUuid);
                }
                rev = revision.incrementAndGet();
                logActionLocked(actor, "user", userLogTarget(createdRec),
                        "Created user (primaryGroup: " + effPrimary + ")");
                saved = createdRec;
            }
        } finally {
            rw.writeLock().unlock();
        }
        notifyUserUpdated(rev, saved, actor);
        return MutationResult.forEnsureUser(rev, false, created, saved);
    }

    public StonyPermsClient.DeleteUserOutcome deleteUser(String idOrName, String actor) throws IOException {
        rw.writeLock().lock();
        UserRecord existing;
        long rev;
        try {
            existing = resolveUserLocked(idOrName);
            storage.deleteUser(existing.uuid());
            if (!existing.username().isEmpty()) {
                nameToUuid.remove(existing.username().toLowerCase(Locale.ROOT));
            }
            users.remove(existing.uuid());
            rev = revision.incrementAndGet();
            logActionLocked(actor, "user", userLogTarget(existing), "Deleted user");
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(
                rev, "user_deleted", existing.uuid().toString(), existing.username(), "", "", safeActor(actor), nowEpoch()));
        return new StonyPermsClient.DeleteUserOutcome(
                MutationResult.forDelete(rev, false, true), existing.uuid());
    }

    public MutationResult setParent(
            String idOrName,
            String groupName,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            boolean setPrimary,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(groupName, "group");
        ParentMode effectiveMode = mode == null ? ParentMode.ADD : mode;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String tempMod = modeToModifier(effectiveMode);

        return mutateUser(idOrName, actor, (u, out) -> {
            if (!groups.containsKey(cleanGroup)) {
                throw new ApiException(404, "group_not_found", "group not found: " + cleanGroup);
            }
            List<ParentEntry> base = effectiveMode == ParentMode.SET ? new ArrayList<>() : u.parents();
            List<ParentEntry> updatedParents = addParentWithModifier(
                    base, new ParentEntry(cleanGroup, effExpiry, cleanCtx), tempMod, now);
            String primary = setPrimary ? cleanGroup : u.primaryGroup();
            out.next = new UserRecord(
                    u.uuid(), u.username(), primary, updatedParents, u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = "Added parent '" + cleanGroup + "'";
        });
    }

    public MutationResult removeParent(
            String idOrName, String groupName, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String cleanGroup = requireEntityKey(groupName, "group");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        return mutateUser(idOrName, actor, (u, out) -> {
            RemoveListResult<ParentEntry> res = removeParentList(u.parents(), cleanGroup, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), res.list, u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = (tempOnly ? "Removed temporary parent '" : "Removed parent '") + cleanGroup + "'";
        });
    }

    public MutationResult setPrimaryGroup(String idOrName, String groupName, String actor) throws IOException {
        String cleanGroup = requireEntityKey(groupName, "group");
        return mutateUser(idOrName, actor, (u, out) -> {
            if (!groups.containsKey(cleanGroup)) {
                throw new ApiException(404, "group_not_found", "group not found: " + cleanGroup);
            }
            if (u.primaryGroup().equalsIgnoreCase(cleanGroup)) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), cleanGroup, u.parents(), u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = "Set primary group to '" + cleanGroup + "'";
        });
    }

    public MutationResult setPermission(
            String idOrName,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor) throws IOException {
        String normKey = normalizePermKey(key);
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);

        return mutateUser(idOrName, actor, (u, out) -> {
            List<Node> nextNodes = addNodeWithModifier(
                    u.nodes(), new Node(normKey, value, cleanCtx, effExpiry, "user", 0, true), cleanMod, now);
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), nextNodes,
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = "Set permission '" + normKey + "' = " + value;
        });
    }

    public MutationResult unsetPermission(
            String idOrName, String key, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String normKey = normalizePermKey(key);
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        return mutateUser(idOrName, actor, (u, out) -> {
            RemoveListResult<Node> res = removeNodeList(u.nodes(), normKey, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), res.list,
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = (tempOnly ? "Unset temporary permission '" : "Unset permission '") + normKey + "'";
        });
    }

    public MutationResult setMeta(
            String idOrName,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor) throws IOException {
        String cleanKey = requireNonBlank(key, "meta key");
        String cleanVal = value == null ? "" : value;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);

        return mutateUser(idOrName, actor, (u, out) -> {
            MetaMutationResult mm = setMetaEntryList(
                    u.meta(), u.metaEntries(), new MetaEntry(cleanKey, cleanVal, 0, effExpiry, cleanCtx), cleanMod, now);
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), u.nodes(),
                    mm.meta, u.prefixes(), u.suffixes(), mm.entries);
            out.audit = "Set meta '" + cleanKey + "'";
        });
    }

    public MutationResult unsetMeta(
            String idOrName, String key, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String cleanKey = requireNonBlank(key, "meta key");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        return mutateUser(idOrName, actor, (u, out) -> {
            MetaMutationResult mm = unsetMetaEntryList(u.meta(), u.metaEntries(), cleanKey, cleanCtx, tempOnly);
            if (!mm.changed) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), u.nodes(),
                    mm.meta, u.prefixes(), u.suffixes(), mm.entries);
            out.audit = (tempOnly ? "Unset temporary meta '" : "Unset meta '") + cleanKey + "'";
        });
    }

    public MutationResult setAffix(
            String idOrName,
            boolean isPrefix,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor) throws IOException {
        String cleanVal = value == null ? "" : value;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);
        boolean replaceInCtx = "set".equalsIgnoreCase(mode);
        PriorityMeta pm = new PriorityMeta(priority, cleanVal, effExpiry, cleanCtx);

        return mutateUser(idOrName, actor, (u, out) -> {
            List<PriorityMeta> src = isPrefix ? u.prefixes() : u.suffixes();
            List<PriorityMeta> next = replaceInCtx
                    ? setAffixInContextList(src, pm)
                    : addAffixWithModifierList(src, pm, cleanMod, now);
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), u.nodes(), u.meta(),
                    isPrefix ? next : u.prefixes(),
                    isPrefix ? u.suffixes() : next,
                    u.metaEntries());
            out.audit = "Set " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")";
        });
    }

    public MutationResult removeAffix(
            String idOrName,
            boolean isPrefix,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor) throws IOException {
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        String valFilter = value == null ? "" : value;
        return mutateUser(idOrName, actor, (u, out) -> {
            List<PriorityMeta> src = isPrefix ? u.prefixes() : u.suffixes();
            RemoveListResult<PriorityMeta> res = removeAffixList(src, priority, valFilter, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), u.nodes(), u.meta(),
                    isPrefix ? res.list : u.prefixes(),
                    isPrefix ? u.suffixes() : res.list,
                    u.metaEntries());
            out.audit = (tempOnly ? "Removed temporary " : "Removed ") + (isPrefix ? "prefix" : "suffix")
                    + " (priority " + priority + ")";
        });
    }

    public TrackActionResult stepUserTrack(String idOrName, String trackName, boolean promote, String actor)
            throws IOException {
        String trackKey = requireEntityKey(trackName, "track");
        UserRecord saved;
        String fromGroup = "";
        String nextGroup;
        long rev;

        rw.writeLock().lock();
        try {
            TrackRecord track = tracks.get(trackKey);
            if (track == null) {
                throw new ApiException(404, "track_not_found", "track not found: " + trackName);
            }
            if (track.groups().isEmpty()) {
                throw new ApiException(400, "track_empty", "track contains no groups: " + trackName);
            }
            UserRecord u = resolveUserLocked(idOrName);
            for (String g : track.groups()) {
                if (u.primaryGroup().equalsIgnoreCase(g)) {
                    fromGroup = g;
                    continue;
                }
                for (ParentEntry p : u.parents()) {
                    if (p.group().equalsIgnoreCase(g)) {
                        fromGroup = g;
                        break;
                    }
                }
            }
            if (promote) {
                int idx = trackIndexOf(track.groups(), fromGroup);
                if (idx < 0) {
                    nextGroup = track.groups().getFirst();
                } else if (idx + 1 < track.groups().size()) {
                    nextGroup = track.groups().get(idx + 1);
                } else {
                    throw new ApiException(409, "track_top", "user is already at the highest group on track");
                }
            } else {
                if (fromGroup.isEmpty()) {
                    throw new ApiException(400, "not_on_track", "user is not on track");
                }
                int idx = trackIndexOf(track.groups(), fromGroup);
                if (idx <= 0) {
                    throw new ApiException(409, "track_bottom", "user is already at the lowest group on track");
                }
                nextGroup = track.groups().get(idx - 1);
            }

            List<ParentEntry> parents = u.parents();
            if (!fromGroup.isEmpty()) {
                parents = removeParentList(parents, fromGroup, Map.of(), false).list;
            }
            parents = addParentWithModifier(parents, new ParentEntry(nextGroup, 0L, Map.of()), "replace", nowEpoch());
            UserRecord updated = new UserRecord(
                    u.uuid(), u.username(), nextGroup, parents, u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            storage.saveUser(updated);
            users.put(updated.uuid(), updated);
            rev = revision.incrementAndGet();
            String verb = promote ? "Promoted" : "Demoted";
            logActionLocked(actor, "user", userLogTarget(updated),
                    verb + " along track '" + trackKey + "' (" + fromGroup + " -> " + nextGroup + ")");
            saved = updated;
        } finally {
            rw.writeLock().unlock();
        }
        notifyUserUpdated(rev, saved, actor);
        return new TrackActionResult(rev, fromGroup, nextGroup, saved);
    }

    public MutationResult clearUser(String idOrName, String scope, String actor) throws IOException {
        String cleanScope = (scope == null || scope.isBlank()) ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!cleanScope.equals("all") && !cleanScope.equals("permissions")
                && !cleanScope.equals("parents") && !cleanScope.equals("meta")) {
            throw new ApiException(400, "invalid_scope", "Invalid clear scope '" + scope + "'");
        }
        return mutateUser(idOrName, actor, (u, out) -> {
            List<Node> nodes = (cleanScope.equals("all") || cleanScope.equals("permissions")) ? List.of() : u.nodes();
            List<ParentEntry> parents = (cleanScope.equals("all") || cleanScope.equals("parents")) ? List.of() : u.parents();
            String primary = cleanScope.equals("all") ? config.defaultGroup() : u.primaryGroup();
            Map<String, String> meta = (cleanScope.equals("all") || cleanScope.equals("meta")) ? Map.of() : u.meta();
            List<MetaEntry> entries = (cleanScope.equals("all") || cleanScope.equals("meta")) ? List.of() : u.metaEntries();
            List<PriorityMeta> prefixes = (cleanScope.equals("all") || cleanScope.equals("meta")) ? List.of() : u.prefixes();
            List<PriorityMeta> suffixes = (cleanScope.equals("all") || cleanScope.equals("meta")) ? List.of() : u.suffixes();
            out.next = new UserRecord(u.uuid(), u.username(), primary, parents, nodes, meta, prefixes, suffixes, entries);
            out.audit = "Cleared user (" + cleanScope + ")";
        });
    }

    public MutationResult batchUser(String idOrName, List<BatchOp> operations, String actor) throws IOException {
        if (operations == null || operations.isEmpty()) {
            throw new ApiException(400, "invalid_batch", "operations must contain at least one item");
        }
        long now = nowEpoch();
        return mutateUser(idOrName, actor, (u, out) -> {
            String primary = u.primaryGroup();
            List<ParentEntry> parents = new ArrayList<>(u.parents());
            List<Node> nodes = new ArrayList<>(u.nodes());
            Map<String, String> meta = new LinkedHashMap<>(u.meta());
            List<MetaEntry> metaEntries = new ArrayList<>(u.metaEntries());
            List<PriorityMeta> prefixes = new ArrayList<>(u.prefixes());
            List<PriorityMeta> suffixes = new ArrayList<>(u.suffixes());

            for (BatchOp op : operations) {
                Map<String, String> ctx = MetaEntry.normalizeContexts(op.contexts());
                long exp = resolveExpiry(op.durationSeconds() == null ? 0L : op.durationSeconds(),
                        op.expiry() == null ? 0L : op.expiry(), now);
                String name = op.op().trim().toLowerCase(Locale.ROOT);
                switch (name) {
                    case "parent_add", "parent_accumulate", "parent_replace", "parent_deny", "parent_set" -> {
                        String g = requireEntityKey(op.group(), "group");
                        if (!groups.containsKey(g)) {
                            throw new ApiException(404, "group_not_found", "group not found: " + g);
                        }
                        if ("parent_set".equals(name)) {
                            parents.clear();
                        }
                        String mod = switch (name) {
                            case "parent_accumulate" -> "accumulate";
                            case "parent_deny" -> "deny";
                            default -> "replace";
                        };
                        parents = new ArrayList<>(addParentWithModifier(parents, new ParentEntry(g, exp, ctx), mod, now));
                        if (Boolean.TRUE.equals(op.setPrimary())) {
                            primary = g;
                        }
                    }
                    case "parent_remove" -> {
                        String g = requireEntityKey(op.group(), "group");
                        parents = new ArrayList<>(removeParentList(parents, g, ctx, false).list);
                    }
                    case "primary_group_set" -> {
                        String g = requireEntityKey(op.group(), "group");
                        if (!groups.containsKey(g)) {
                            throw new ApiException(404, "group_not_found", "group not found: " + g);
                        }
                        primary = g;
                    }
                    case "permission_set" -> {
                        String permKey = normalizePermKey(op.key());
                        boolean val = !(op.value() instanceof Boolean b) || b;
                        nodes = new ArrayList<>(addNodeWithModifier(
                                nodes, new Node(permKey, val, ctx, exp, "user", 0, true), "replace", now));
                    }
                    case "permission_unset" -> {
                        String permKey = normalizePermKey(op.key());
                        nodes = new ArrayList<>(removeNodeList(nodes, permKey, ctx, false).list);
                    }
                    case "meta_set" -> {
                        String mk = requireNonBlank(op.key(), "meta key");
                        String mv = op.value() == null ? "" : String.valueOf(op.value());
                        MetaMutationResult mm = setMetaEntryList(meta, metaEntries, new MetaEntry(mk, mv, 0, exp, ctx), "replace", now);
                        meta = new LinkedHashMap<>(mm.meta);
                        metaEntries = new ArrayList<>(mm.entries);
                    }
                    case "meta_unset" -> {
                        String mk = requireNonBlank(op.key(), "meta key");
                        MetaMutationResult mm = unsetMetaEntryList(meta, metaEntries, mk, ctx, false);
                        meta = new LinkedHashMap<>(mm.meta);
                        metaEntries = new ArrayList<>(mm.entries);
                    }
                    case "prefix_set" -> {
                        int prio = op.priority() == null ? 0 : op.priority();
                        String pv = op.value() == null ? "" : String.valueOf(op.value());
                        prefixes = new ArrayList<>(addAffixWithModifierList(
                                prefixes, new PriorityMeta(prio, pv, exp, ctx), "replace", now));
                    }
                    case "prefix_remove" -> {
                        int prio = op.priority() == null ? 0 : op.priority();
                        prefixes = new ArrayList<>(removeAffixList(prefixes, prio, "", ctx, false).list);
                    }
                    case "suffix_set" -> {
                        int prio = op.priority() == null ? 0 : op.priority();
                        String sv = op.value() == null ? "" : String.valueOf(op.value());
                        suffixes = new ArrayList<>(addAffixWithModifierList(
                                suffixes, new PriorityMeta(prio, sv, exp, ctx), "replace", now));
                    }
                    case "suffix_remove" -> {
                        int prio = op.priority() == null ? 0 : op.priority();
                        suffixes = new ArrayList<>(removeAffixList(suffixes, prio, "", ctx, false).list);
                    }
                    default -> throw new ApiException(400, "invalid_batch_op", "Unsupported batch op: " + op.op());
                }
            }
            out.next = new UserRecord(u.uuid(), u.username(), primary, parents, nodes, meta, prefixes, suffixes, metaEntries);
            out.audit = "Executed batch (" + operations.size() + " ops)";
        });
    }

    public MutationResult setUserTrack(
            String idOrName, String trackName, String targetGroup, Map<String, String> contexts, String actor)
            throws IOException {
        String trackKey = requireEntityKey(trackName, "track");
        String cleanTarget = requireEntityKey(targetGroup, "group");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        String defGroup = config.defaultGroup();

        return mutateUser(idOrName, actor, (u, out) -> {
            TrackRecord track = tracks.get(trackKey);
            if (track == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            if (trackIndexOf(track.groups(), cleanTarget) < 0) {
                throw new ApiException(400, "group_not_in_track",
                        "group '" + cleanTarget + "' is not part of track '" + trackKey + "'");
            }
            if (!groups.containsKey(cleanTarget)) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            Set<String> trackSet = trackGroupSet(track);
            List<ParentEntry> kept = new ArrayList<>();
            for (ParentEntry p : u.parents()) {
                if (trackSet.contains(p.group().toLowerCase(Locale.ROOT))
                        && MetaEntry.sameContexts(p.contexts(), cleanCtx)) {
                    continue;
                }
                kept.add(p);
            }
            List<ParentEntry> nextParents = addParentWithModifier(
                    kept, new ParentEntry(cleanTarget, 0L, cleanCtx), "replace", nowEpoch());
            String primary = u.primaryGroup();
            if (cleanCtx.isEmpty() && (primary.isEmpty() || primary.equals("default")
                    || primary.equalsIgnoreCase(defGroup)
                    || trackSet.contains(primary.toLowerCase(Locale.ROOT)))) {
                primary = cleanTarget;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), primary, nextParents, u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = "Set track '" + trackKey + "' group to '" + cleanTarget + "'";
        });
    }

    public MutationResult clearUserTrack(
            String idOrName, String trackName, Map<String, String> contexts, String actor) throws IOException {
        String trackKey = requireEntityKey(trackName, "track");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        String defGroup = config.defaultGroup();

        return mutateUser(idOrName, actor, (u, out) -> {
            TrackRecord track = tracks.get(trackKey);
            if (track == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            Set<String> trackSet = trackGroupSet(track);
            boolean removed = false;
            List<ParentEntry> kept = new ArrayList<>();
            for (ParentEntry p : u.parents()) {
                if (trackSet.contains(p.group().toLowerCase(Locale.ROOT))
                        && MetaEntry.sameContexts(p.contexts(), cleanCtx)) {
                    removed = true;
                    continue;
                }
                kept.add(p);
            }
            String primary = u.primaryGroup();
            if (cleanCtx.isEmpty() && trackSet.contains(primary.toLowerCase(Locale.ROOT))) {
                primary = defGroup;
                removed = true;
            }
            if (!removed) {
                out.changed = false;
                return;
            }
            out.next = new UserRecord(
                    u.uuid(), u.username(), primary, kept, u.nodes(),
                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries());
            out.audit = "Cleared track '" + trackKey + "'";
        });
    }

    // -------------------------------------------------------------------------
    // Group Mutations
    // -------------------------------------------------------------------------

    public MutationResult createGroup(String name, int weight, String displayName, String actor) throws IOException {
        String key = validateEntityName(name, "group");
        long rev;
        GroupRecord created;
        rw.writeLock().lock();
        try {
            if (groups.containsKey(key)) {
                throw new ApiException(409, "group_exists", "group already exists");
            }
            created = new GroupRecord(
                    key, displayName == null ? "" : displayName, weight,
                    List.of(), List.of(), Map.of(), List.of(), List.of(), List.of());
            storage.saveGroup(created);
            groups.put(key, created);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "group", key, "Created group " + key + " (weight: " + weight + ")");
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "group_updated", "", "", key, "", safeActor(actor), nowEpoch()));
        return MutationResult.forGroup(rev, false, true, created);
    }

    public MutationResult patchGroup(String name, Integer weight, String displayName, String actor) throws IOException {
        if (weight == null && displayName == null) {
            throw new ApiException(400, "invalid_request", "At least one of weight or displayName is required");
        }
        String key = requireEntityKey(name, "group");
        return mutateGroup(key, actor, (g, out) -> {
            int nextW = weight != null ? weight : g.weight();
            String nextD = displayName != null ? displayName : g.displayName();
            if (nextW == g.weight() && Objects.equals(nextD, g.displayName())) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), nextD, nextW, g.parents(), g.nodes(),
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = "Updated group " + key + " (weight=" + nextW + ")";
        });
    }

    public MutationResult deleteGroup(String name, String actor) throws IOException {
        String key = requireEntityKey(name, "group");
        if (key.equals("default") || key.equals(config.defaultGroup())) {
            throw new ApiException(400, "cannot_delete_default", "cannot delete the default group");
        }
        long rev;
        rw.writeLock().lock();
        try {
            if (!groups.containsKey(key)) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            storage.deleteGroup(key);
            groups.remove(key);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "group", key, "Deleted group " + key);
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "group_deleted", "", "", key, "", safeActor(actor), nowEpoch()));
        return MutationResult.forDelete(rev, false, true);
    }

    public MutationResult renameGroup(String oldName, String newName, String actor) throws IOException {
        String cleanOld = oldName == null ? "" : oldName.trim().toLowerCase(Locale.ROOT);
        String cleanNew = validateEntityName(newName, "group");
        if (cleanOld.isEmpty()) {
            throw new ApiException(400, "invalid_request", "group name cannot be empty");
        }
        if (cleanOld.equals("default") || cleanOld.equals(config.defaultGroup())) {
            throw new ApiException(400, "cannot_rename_default", "cannot rename the default group");
        }

        GroupRecord renamed;
        boolean cascaded;
        long rev;
        rw.writeLock().lock();
        try {
            GroupRecord existing = groups.get(cleanOld);
            if (existing == null) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            if (cleanOld.equals(cleanNew)) {
                return MutationResult.forGroup(revision.get(), false, false, existing);
            }
            if (groups.containsKey(cleanNew)) {
                throw new ApiException(409, "group_exists", "group already exists");
            }

            renamed = new GroupRecord(
                    cleanNew,
                    existing.displayName(),
                    existing.weight(),
                    rewriteParentRefs(existing.parents(), cleanOld, cleanNew),
                    retargetGroupNodes(existing.nodes(), cleanNew),
                    existing.meta(),
                    existing.prefixes(),
                    existing.suffixes(),
                    existing.metaEntries());

            Map<String, GroupRecord> updatedGroups = new LinkedHashMap<>();
            for (GroupRecord g : groups.values()) {
                if (g.name().equals(cleanOld) || !hasParentRef(g.parents(), cleanOld)) {
                    continue;
                }
                updatedGroups.put(g.name(), new GroupRecord(
                        g.name(), g.displayName(), g.weight(),
                        rewriteParentRefs(g.parents(), cleanOld, cleanNew),
                        g.nodes(), g.meta(), g.prefixes(), g.suffixes(), g.metaEntries()));
            }

            Map<String, TrackRecord> updatedTracks = new LinkedHashMap<>();
            for (TrackRecord t : tracks.values()) {
                if (!hasTrackGroupRef(t.groups(), cleanOld)) {
                    continue;
                }
                List<String> nextGrps = new ArrayList<>(t.groups().size());
                for (String tg : t.groups()) {
                    nextGrps.add(tg.equalsIgnoreCase(cleanOld) ? cleanNew : tg);
                }
                updatedTracks.put(t.name(), new TrackRecord(t.name(), nextGrps));
            }

            Map<UUID, UserRecord> updatedUsers = new LinkedHashMap<>();
            for (UserRecord u : users.values()) {
                boolean primaryMatch = u.primaryGroup().equalsIgnoreCase(cleanOld);
                boolean parentMatch = hasParentRef(u.parents(), cleanOld);
                if (!primaryMatch && !parentMatch) {
                    continue;
                }
                updatedUsers.put(u.uuid(), new UserRecord(
                        u.uuid(),
                        u.username(),
                        primaryMatch ? cleanNew : u.primaryGroup(),
                        parentMatch ? rewriteParentRefs(u.parents(), cleanOld, cleanNew) : u.parents(),
                        u.nodes(), u.meta(), u.prefixes(), u.suffixes(), u.metaEntries()));
            }

            storage.saveGroup(renamed);
            for (GroupRecord gCopy : updatedGroups.values()) {
                storage.saveGroup(gCopy);
            }
            for (TrackRecord tCopy : updatedTracks.values()) {
                storage.saveTrack(tCopy);
            }
            for (UserRecord uCopy : updatedUsers.values()) {
                storage.saveUser(uCopy);
            }
            storage.deleteGroup(cleanOld);

            groups.remove(cleanOld);
            groups.put(cleanNew, renamed);
            groups.putAll(updatedGroups);
            tracks.putAll(updatedTracks);
            users.putAll(updatedUsers);

            rev = revision.incrementAndGet();
            logActionLocked(actor, "group", cleanNew, "Renamed group '" + cleanOld + "' to '" + cleanNew + "'");
            cascaded = !updatedTracks.isEmpty() || !updatedUsers.isEmpty();
        } finally {
            rw.writeLock().unlock();
        }
        long ts = nowEpoch();
        notifyEvent(new ChangeEvent(rev, "group_deleted", "", "", cleanOld, "", safeActor(actor), ts));
        notifyEvent(new ChangeEvent(rev, "group_updated", "", "", cleanNew, "", safeActor(actor), ts));
        if (cascaded) {
            notifyEvent(new ChangeEvent(rev, "reload", "", "", "", "", safeActor(actor), ts));
        }
        return MutationResult.forGroup(rev, false, false, renamed);
    }

    public MutationResult setGroupPermission(
            String name,
            String key,
            boolean value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String normKey = normalizePermKey(key);
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            List<Node> nextNodes = addNodeWithModifier(
                    g.nodes(),
                    new Node(normKey, value, cleanCtx, effExpiry, "group:" + cleanGroup, g.weight(), false),
                    cleanMod, now);
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), nextNodes,
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = "Set permission '" + normKey + "' = " + value;
        });
    }

    public MutationResult unsetGroupPermission(
            String name, String key, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String normKey = normalizePermKey(key);
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            RemoveListResult<Node> res = removeNodeList(g.nodes(), normKey, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), res.list,
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = (tempOnly ? "Unset temporary permission '" : "Unset permission '") + normKey + "'";
        });
    }

    public MutationResult addGroupParent(
            String name,
            String parentGroup,
            ParentMode mode,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String cleanParent = requireEntityKey(parentGroup, "parent group");
        if (cleanGroup.equals(cleanParent)) {
            throw new ApiException(400, "invalid_parent", "group cannot inherit from itself");
        }
        ParentMode effectiveMode = mode == null ? ParentMode.ADD : mode;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String tempMod = modeToModifier(effectiveMode);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            if (!groups.containsKey(cleanParent)) {
                throw new ApiException(404, "group_not_found", "parent group not found: " + cleanParent);
            }
            List<ParentEntry> base = effectiveMode == ParentMode.SET ? new ArrayList<>() : g.parents();
            List<ParentEntry> nextParents = addParentWithModifier(
                    base, new ParentEntry(cleanParent, effExpiry, cleanCtx), tempMod, now);
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), nextParents, g.nodes(),
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = "Added parent '" + cleanParent + "'";
        });
    }

    public MutationResult removeGroupParent(
            String name, String parentGroup, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String cleanParent = requireEntityKey(parentGroup, "parent group");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            RemoveListResult<ParentEntry> res = removeParentList(g.parents(), cleanParent, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), res.list, g.nodes(),
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = (tempOnly ? "Removed temporary parent '" : "Removed parent '") + cleanParent + "'";
        });
    }

    public MutationResult setGroupMeta(
            String name,
            String key,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String modifier,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String cleanKey = requireNonBlank(key, "meta key");
        String cleanVal = value == null ? "" : value;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            MetaMutationResult mm = setMetaEntryList(
                    g.meta(), g.metaEntries(), new MetaEntry(cleanKey, cleanVal, 0, effExpiry, cleanCtx), cleanMod, now);
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), g.nodes(),
                    mm.meta, g.prefixes(), g.suffixes(), mm.entries);
            out.audit = "Set meta '" + cleanKey + "'";
        });
    }

    public MutationResult unsetGroupMeta(
            String name, String key, Map<String, String> contexts, boolean tempOnly, String actor)
            throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String cleanKey = requireNonBlank(key, "meta key");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            MetaMutationResult mm = unsetMetaEntryList(g.meta(), g.metaEntries(), cleanKey, cleanCtx, tempOnly);
            if (!mm.changed) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), g.nodes(),
                    mm.meta, g.prefixes(), g.suffixes(), mm.entries);
            out.audit = (tempOnly ? "Unset temporary meta '" : "Unset meta '") + cleanKey + "'";
        });
    }

    public MutationResult setGroupAffix(
            String name,
            boolean isPrefix,
            int priority,
            String value,
            long durationSeconds,
            long expiry,
            Map<String, String> contexts,
            String mode,
            String modifier,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String cleanVal = value == null ? "" : value;
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        long now = nowEpoch();
        long effExpiry = resolveExpiry(durationSeconds, expiry, now);
        String cleanMod = normalizeModifier(modifier);
        boolean replaceInCtx = "set".equalsIgnoreCase(mode);
        PriorityMeta pm = new PriorityMeta(priority, cleanVal, effExpiry, cleanCtx);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            List<PriorityMeta> src = isPrefix ? g.prefixes() : g.suffixes();
            List<PriorityMeta> next = replaceInCtx
                    ? setAffixInContextList(src, pm)
                    : addAffixWithModifierList(src, pm, cleanMod, now);
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), g.nodes(), g.meta(),
                    isPrefix ? next : g.prefixes(),
                    isPrefix ? g.suffixes() : next,
                    g.metaEntries());
            out.audit = "Set " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")";
        });
    }

    public MutationResult removeGroupAffix(
            String name,
            boolean isPrefix,
            int priority,
            String value,
            Map<String, String> contexts,
            boolean tempOnly,
            String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);
        String valFilter = value == null ? "" : value;

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            List<PriorityMeta> src = isPrefix ? g.prefixes() : g.suffixes();
            RemoveListResult<PriorityMeta> res = removeAffixList(src, priority, valFilter, cleanCtx, tempOnly);
            if (!res.removed) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), g.parents(), g.nodes(), g.meta(),
                    isPrefix ? res.list : g.prefixes(),
                    isPrefix ? g.suffixes() : res.list,
                    g.metaEntries());
            out.audit = (tempOnly ? "Removed temporary " : "Removed ") + (isPrefix ? "prefix" : "suffix")
                    + " (priority " + priority + ")";
        });
    }

    public MutationResult setGroupTrack(
            String name, String trackName, String targetGroup, Map<String, String> contexts, String actor)
            throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String trackKey = requireEntityKey(trackName, "track");
        String cleanTarget = requireEntityKey(targetGroup, "target group");
        if (cleanGroup.equals(cleanTarget)) {
            throw new ApiException(400, "invalid_parent", "group cannot inherit from itself");
        }
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            TrackRecord track = tracks.get(trackKey);
            if (track == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            if (trackIndexOf(track.groups(), cleanTarget) < 0) {
                throw new ApiException(400, "group_not_in_track",
                        "group '" + cleanTarget + "' is not part of track '" + trackKey + "'");
            }
            if (!groups.containsKey(cleanTarget)) {
                throw new ApiException(404, "group_not_found", "group not found");
            }
            Set<String> trackSet = trackGroupSet(track);
            List<ParentEntry> kept = new ArrayList<>();
            for (ParentEntry p : g.parents()) {
                if (trackSet.contains(p.group().toLowerCase(Locale.ROOT))
                        && MetaEntry.sameContexts(p.contexts(), cleanCtx)) {
                    continue;
                }
                kept.add(p);
            }
            List<ParentEntry> nextParents = addParentWithModifier(
                    kept, new ParentEntry(cleanTarget, 0L, cleanCtx), "replace", nowEpoch());
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), nextParents, g.nodes(),
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = "Set track '" + trackKey + "' parent to '" + cleanTarget + "'";
        });
    }

    public MutationResult clearGroupTrack(
            String name, String trackName, Map<String, String> contexts, String actor) throws IOException {
        String cleanGroup = requireEntityKey(name, "group");
        String trackKey = requireEntityKey(trackName, "track");
        Map<String, String> cleanCtx = MetaEntry.normalizeContexts(contexts);

        return mutateGroup(cleanGroup, actor, (g, out) -> {
            TrackRecord track = tracks.get(trackKey);
            if (track == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            Set<String> trackSet = trackGroupSet(track);
            boolean removed = false;
            List<ParentEntry> kept = new ArrayList<>();
            for (ParentEntry p : g.parents()) {
                if (trackSet.contains(p.group().toLowerCase(Locale.ROOT))
                        && MetaEntry.sameContexts(p.contexts(), cleanCtx)) {
                    removed = true;
                    continue;
                }
                kept.add(p);
            }
            if (!removed) {
                out.changed = false;
                return;
            }
            out.next = new GroupRecord(
                    g.name(), g.displayName(), g.weight(), kept, g.nodes(),
                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries());
            out.audit = "Cleared track '" + trackKey + "'";
        });
    }

    // -------------------------------------------------------------------------
    // Track Mutations
    // -------------------------------------------------------------------------

    public MutationResult createTrack(String name, List<String> trackGroups, String actor) throws IOException {
        String key = validateEntityName(name, "track");
        TrackRecord created;
        long rev;
        rw.writeLock().lock();
        try {
            if (tracks.containsKey(key)) {
                throw new ApiException(409, "track_exists", "track already exists");
            }
            List<String> validated = validateTrackGroupsLocked(trackGroups);
            created = new TrackRecord(key, validated);
            storage.saveTrack(created);
            tracks.put(key, created);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "track", key, "Created track " + key);
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "track_updated", "", "", "", key, safeActor(actor), nowEpoch()));
        return MutationResult.forTrack(rev, false, true, created);
    }

    public MutationResult putTrack(String name, List<String> trackGroups, String actor) throws IOException {
        String key = requireEntityKey(name, "track");
        TrackRecord updated;
        long rev;
        rw.writeLock().lock();
        try {
            if (!tracks.containsKey(key)) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            List<String> validated = validateTrackGroupsLocked(trackGroups);
            updated = new TrackRecord(key, validated);
            storage.saveTrack(updated);
            tracks.put(key, updated);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "track", key, "Updated track groups (" + validated.size() + ")");
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "track_updated", "", "", "", key, safeActor(actor), nowEpoch()));
        return MutationResult.forTrack(rev, false, false, updated);
    }

    public MutationResult deleteTrack(String name, String actor) throws IOException {
        String key = requireEntityKey(name, "track");
        long rev;
        rw.writeLock().lock();
        try {
            if (!tracks.containsKey(key)) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            storage.deleteTrack(key);
            tracks.remove(key);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "track", key, "Deleted track " + key);
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "track_deleted", "", "", "", key, safeActor(actor), nowEpoch()));
        return MutationResult.forDelete(rev, false, true);
    }

    public MutationResult renameTrack(String oldName, String newName, String actor) throws IOException {
        String cleanOld = oldName == null ? "" : oldName.trim().toLowerCase(Locale.ROOT);
        String cleanNew = validateEntityName(newName, "track");
        if (cleanOld.isEmpty()) {
            throw new ApiException(400, "invalid_request", "track name cannot be empty");
        }
        TrackRecord renamed;
        long rev;
        rw.writeLock().lock();
        try {
            TrackRecord existing = tracks.get(cleanOld);
            if (existing == null) {
                throw new ApiException(404, "track_not_found", "track not found");
            }
            if (cleanOld.equals(cleanNew)) {
                return MutationResult.forTrack(revision.get(), false, false, existing);
            }
            if (tracks.containsKey(cleanNew)) {
                throw new ApiException(409, "track_exists", "track already exists");
            }
            renamed = new TrackRecord(cleanNew, existing.groups());
            storage.saveTrack(renamed);
            try {
                storage.deleteTrack(cleanOld);
            } catch (IOException delErr) {
                try {
                    storage.deleteTrack(cleanNew);
                } catch (IOException ignored) {
                }
                throw delErr;
            }
            tracks.remove(cleanOld);
            tracks.put(cleanNew, renamed);
            rev = revision.incrementAndGet();
            logActionLocked(actor, "track", cleanNew, "Renamed track '" + cleanOld + "' to '" + cleanNew + "'");
        } finally {
            rw.writeLock().unlock();
        }
        long ts = nowEpoch();
        notifyEvent(new ChangeEvent(rev, "track_deleted", "", "", "", cleanOld, safeActor(actor), ts));
        notifyEvent(new ChangeEvent(rev, "track_updated", "", "", "", cleanNew, safeActor(actor), ts));
        return MutationResult.forTrack(rev, false, false, renamed);
    }

    // -------------------------------------------------------------------------
    // Search & Bulk Update
    // -------------------------------------------------------------------------

    public SearchResultRecord search(String query) {
        String clean = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return new SearchResultRecord(revision.get(), "", List.of(), List.of());
        }
        boolean isGroupQuery = false;
        String targetGroup = "";
        if (clean.startsWith("group.")) {
            isGroupQuery = true;
            targetGroup = clean.substring("group.".length());
        } else if (clean.startsWith("parent:")) {
            isGroupQuery = true;
            targetGroup = clean.substring("parent:".length());
        }

        List<SearchHitRecord> groupHits = new ArrayList<>();
        List<SearchHitRecord> userHits = new ArrayList<>();
        long rev;
        rw.readLock().lock();
        try {
            rev = revision.get();
            long now = nowEpoch();
            for (GroupRecord g : groups.values()) {
                if (isGroupQuery) {
                    for (ParentEntry p : g.parents()) {
                        if (!p.isExpired(now) && p.group().equalsIgnoreCase(targetGroup)) {
                            groupHits.add(new SearchHitRecord(
                                    "group", g.name(), "", "group." + p.group(), true, p.expiry(), p.contexts()));
                        }
                    }
                    continue;
                }
                for (Node n : g.nodes()) {
                    if (!n.isExpired(now) && nodeKeyMatchesQuery(n.key(), clean)) {
                        groupHits.add(new SearchHitRecord(
                                "group", g.name(), "", n.key(), n.value(), n.expiry(), n.contexts()));
                    }
                }
            }
            for (UserRecord u : users.values()) {
                String label = u.username().isEmpty() ? u.uuid().toString() : u.username();
                if (isGroupQuery) {
                    if (u.primaryGroup().equalsIgnoreCase(targetGroup)) {
                        userHits.add(new SearchHitRecord(
                                "user", label, u.uuid().toString(),
                                "group." + targetGroup + " (primary)", true, 0L, Map.of()));
                    }
                    for (ParentEntry p : u.parents()) {
                        if (!p.isExpired(now) && p.group().equalsIgnoreCase(targetGroup)) {
                            userHits.add(new SearchHitRecord(
                                    "user", label, u.uuid().toString(),
                                    "group." + p.group(), true, p.expiry(), p.contexts()));
                        }
                    }
                    continue;
                }
                for (Node n : u.nodes()) {
                    if (!n.isExpired(now) && nodeKeyMatchesQuery(n.key(), clean)) {
                        userHits.add(new SearchHitRecord(
                                "user", label, u.uuid().toString(), n.key(), n.value(), n.expiry(), n.contexts()));
                    }
                }
            }
        } finally {
            rw.readLock().unlock();
        }
        Comparator<SearchHitRecord> hitCmp = Comparator.comparing(SearchHitRecord::target)
                .thenComparing(SearchHitRecord::nodeKey);
        groupHits.sort(hitCmp);
        userHits.sort(hitCmp);
        return new SearchResultRecord(rev, clean, groupHits, userHits);
    }

    public BulkUpdateSummaryRecord bulkUpdate(
            String scope,
            String action,
            String field,
            String value,
            List<BulkConstraintRecord> constraints,
            boolean dryRun,
            String actor) throws IOException {
        String cleanScope = (scope == null || scope.isBlank()) ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!cleanScope.equals("all") && !cleanScope.equals("users") && !cleanScope.equals("groups")) {
            throw new ApiException(400, "invalid_bulk_scope", "invalid bulkupdate scope: " + scope);
        }
        String cleanAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!cleanAction.equals("update") && !cleanAction.equals("delete")) {
            throw new ApiException(400, "invalid_bulk_action", "invalid bulkupdate action: " + action);
        }
        String cleanField = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
        String cleanVal = value == null ? "" : value.trim();
        if (cleanAction.equals("update")) {
            if (!cleanField.equals("permission") && !cleanField.equals("server") && !cleanField.equals("world")) {
                throw new ApiException(400, "invalid_bulk_field", "invalid bulkupdate field: " + field);
            }
            if (cleanField.equals("permission")) {
                cleanVal = normalizePermKey(cleanVal);
            }
            if (cleanVal.isEmpty()) {
                throw new ApiException(400, "invalid_bulk_value", "bulkupdate value cannot be empty for update action");
            }
        }

        List<BulkConstraintRecord> cleanConstraints = new ArrayList<>();
        if (constraints != null) {
            for (BulkConstraintRecord c : constraints) {
                String cf = c.field() == null ? "" : c.field().trim().toLowerCase(Locale.ROOT);
                String cop = c.operator() == null ? "" : c.operator().trim();
                if (!cf.equals("permission") && !cf.equals("value") && !cf.equals("server") && !cf.equals("world")) {
                    throw new ApiException(400, "invalid_constraint", "invalid constraint field: " + c.field());
                }
                if (!cop.equals("==") && !cop.equals("!=") && !cop.equals("~~") && !cop.equals("!~")) {
                    throw new ApiException(400, "invalid_constraint", "invalid constraint operator: " + c.operator());
                }
                String pat = c.pattern() == null ? "" : c.pattern().trim();
                if (cf.equals("permission") || cf.equals("value")) {
                    pat = pat.toLowerCase(Locale.ROOT);
                }
                cleanConstraints.add(new BulkConstraintRecord(cf, cop, pat));
            }
        }

        int groupsChanged = 0;
        int usersChanged = 0;
        int nodesAffected = 0;
        long rev;

        rw.writeLock().lock();
        try {
            rev = revision.get();
            List<GroupRecord> nextGroups = new ArrayList<>();
            List<UserRecord> nextUsers = new ArrayList<>();

            if (cleanScope.equals("all") || cleanScope.equals("groups")) {
                for (GroupRecord g : groups.values()) {
                    BulkSliceResult br = applyBulkToNodes(g.nodes(), cleanAction, cleanField, cleanVal, cleanConstraints);
                    if (br.changed) {
                        groupsChanged++;
                        nodesAffected += br.affected;
                        if (!dryRun) {
                            nextGroups.add(new GroupRecord(
                                    g.name(), g.displayName(), g.weight(), g.parents(), br.nodes,
                                    g.meta(), g.prefixes(), g.suffixes(), g.metaEntries()));
                        }
                    }
                }
            }
            if (cleanScope.equals("all") || cleanScope.equals("users")) {
                for (UserRecord u : users.values()) {
                    BulkSliceResult br = applyBulkToNodes(u.nodes(), cleanAction, cleanField, cleanVal, cleanConstraints);
                    if (br.changed) {
                        usersChanged++;
                        nodesAffected += br.affected;
                        if (!dryRun) {
                            nextUsers.add(new UserRecord(
                                    u.uuid(), u.username(), u.primaryGroup(), u.parents(), br.nodes,
                                    u.meta(), u.prefixes(), u.suffixes(), u.metaEntries()));
                        }
                    }
                }
            }

            if (dryRun || (nextGroups.isEmpty() && nextUsers.isEmpty())) {
                return new BulkUpdateSummaryRecord(dryRun, groupsChanged, usersChanged, nodesAffected, rev);
            }
            for (GroupRecord g : nextGroups) {
                storage.saveGroup(g);
            }
            for (UserRecord u : nextUsers) {
                storage.saveUser(u);
            }
            for (GroupRecord g : nextGroups) {
                groups.put(g.name(), g);
            }
            for (UserRecord u : nextUsers) {
                users.put(u.uuid(), u);
            }
            rev = revision.incrementAndGet();
            logActionLocked(actor, "general", "bulkupdate",
                    String.format("BulkUpdate %s %s (%d nodes across %d groups, %d users)",
                            cleanScope, cleanAction, nodesAffected, groupsChanged, usersChanged));
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "reload", "", "", "", "", safeActor(actor), nowEpoch()));
        return new BulkUpdateSummaryRecord(false, groupsChanged, usersChanged, nodesAffected, rev);
    }

    // -------------------------------------------------------------------------
    // Portable StonyPermsWeb Editor / Tree / Verbose Sessions & Apply
    // -------------------------------------------------------------------------

    public EditorSessionResult createEditorSession(
            String actor,
            String commandAlias,
            String targetType,
            String target,
            String filter,
            List<String> extraPermissions,
            Map<String, List<String>> extraContexts) throws IOException {
        long nowMs = System.currentTimeMillis();
        long expiresAt = nowMs + WEB_SESSION_TTL_MS;
        String token = randomBase64Token(16);
        String cleanType = (targetType == null || targetType.isBlank()) ? "all" : targetType.trim().toLowerCase(Locale.ROOT);
        String cleanTarget = target == null ? "" : target.trim();
        String effActor = safeActor(actor).isEmpty() ? "Console" : actor.trim();

        PortableWebCodec.EditorSavePayload payload = new PortableWebCodec.EditorSavePayload();
        long rev;
        rw.readLock().lock();
        try {
            rev = revision.get();
            List<GroupRecord> allGroups = fetchGroups();
            payload.groups = allGroups.stream().map(JsonLocalStorage::toStoredGroup).toList();
            List<TrackRecord> allTracks = fetchTracks();
            payload.tracks = allTracks.stream().map(JsonLocalStorage::toStoredTrack).toList();
            UsersPage page = listUsers("", 0, MAX_EDITOR_USERS);
            List<JsonLocalStorage.StoredUser> storedUsers = new ArrayList<>(page.users().size() + 1);
            Set<UUID> seen = new HashSet<>(page.users().size());
            for (UserSummary us : page.users()) {
                UserRecord full = users.get(us.uuid());
                if (full != null) {
                    storedUsers.add(JsonLocalStorage.toStoredUser(full));
                    seen.add(full.uuid());
                }
            }
            if ("user".equals(cleanType) && !cleanTarget.isEmpty()) {
                Optional<UserRecord> targetOpt = resolveUserByName(cleanTarget);
                if (targetOpt.isPresent()) {
                    UserRecord tu = targetOpt.get();
                    if (seen.add(tu.uuid())) {
                        storedUsers.add(JsonLocalStorage.toStoredUser(tu));
                    }
                    cleanTarget = tu.uuid().toString();
                }
            }
            payload.users = storedUsers;
            payload.baseRevision = rev;
            payload.actor = effActor;
        } finally {
            rw.readLock().unlock();
        }

        String code = PortableWebCodec.encodeObject(payload);
        String targetQuery = "";
        if (!cleanTarget.isEmpty()) {
            if ("group".equals(cleanType)) {
                targetQuery = "group:" + cleanTarget.toLowerCase(Locale.ROOT);
            } else if ("user".equals(cleanType)) {
                targetQuery = "user:" + cleanTarget;
            } else if ("track".equals(cleanType)) {
                targetQuery = "track:" + cleanTarget.toLowerCase(Locale.ROOT);
            }
        }
        String suffix = targetQuery.isEmpty()
                ? "/editor#data=" + code
                : "/editor?target=" + PortableWebCodec.urlEscapeSimple(targetQuery) + "#data=" + code;
        String url = PortableWebCodec.joinWebPath(config.webBaseUrl(), suffix);
        return new EditorSessionResult(token, url, "", expiresAt, false);
    }

    public EditorApplyResult applyEditorCode(String rawCode, String actor) throws IOException {
        PortableWebCodec.EditorSavePayload payload = PortableWebCodec.decodeEditorPayload(rawCode);
        String effActor = (actor == null || actor.isBlank()) ? payload.actor : actor.trim();
        return applyEditorPayload(effActor, payload);
    }

    public EditorApplyResult applyEditorPayload(String actor, PortableWebCodec.EditorSavePayload payload)
            throws IOException {
        if (payload == null) {
            throw new ApiException(400, "invalid_apply_code", "editor payload is required");
        }
        String effActor = (actor == null || actor.isBlank()) ? "web-editor" : actor.trim();

        Set<String> delGroupSet = new LinkedHashSet<>();
        if (payload.deletedGroups != null) {
            for (String rawName : payload.deletedGroups) {
                if (rawName == null || rawName.isBlank()) {
                    continue;
                }
                String name = rawName.trim().toLowerCase(Locale.ROOT);
                if ("default".equals(name) || name.equals(config.defaultGroup())) {
                    throw new ApiException(400, "cannot_delete_default", "cannot delete the default group");
                }
                validateEntityName(name, "deleted group");
                delGroupSet.add(name);
            }
        }

        Set<String> delTrackSet = new LinkedHashSet<>();
        if (payload.deletedTracks != null) {
            for (String rawName : payload.deletedTracks) {
                if (rawName == null || rawName.isBlank()) {
                    continue;
                }
                String name = validateEntityName(rawName, "deleted track");
                delTrackSet.add(name);
            }
        }

        Set<UUID> delUserSet = new LinkedHashSet<>();
        if (payload.deletedUsers != null) {
            for (String rawId : payload.deletedUsers) {
                UUID id = tryParseUuid(rawId);
                if (id != null && !NIL_UUID.equals(id)) {
                    delUserSet.add(id);
                }
            }
        }

        List<GroupRecord> cleanGroups = new ArrayList<>();
        Set<String> upsertedGroupNames = new HashSet<>();
        if (payload.groups != null) {
            for (JsonLocalStorage.StoredGroup sg : payload.groups) {
                GroupRecord g = sanitizeEditorGroup(sg);
                if (!delGroupSet.contains(g.name())) {
                    cleanGroups.add(g);
                    upsertedGroupNames.add(g.name());
                }
            }
        }

        List<UserRecord> cleanUsers = new ArrayList<>();
        if (payload.users != null) {
            for (JsonLocalStorage.StoredUser su : payload.users) {
                UserRecord u = sanitizeEditorUser(su);
                if (!delUserSet.contains(u.uuid())) {
                    cleanUsers.add(u);
                }
            }
        }

        int groupsUpdated = 0;
        int groupsDeleted = 0;
        int tracksUpdated = 0;
        int tracksDeleted = 0;
        int usersUpdated = 0;
        int usersDeleted = 0;
        long rev;

        rw.writeLock().lock();
        try {
            Set<String> finalGroupSet = new HashSet<>(groups.keySet());
            finalGroupSet.removeAll(delGroupSet);
            finalGroupSet.addAll(upsertedGroupNames);

            List<TrackRecord> cleanTracks = new ArrayList<>();
            if (payload.tracks != null) {
                for (JsonLocalStorage.StoredTrack st : payload.tracks) {
                    TrackRecord t = sanitizeEditorTrack(st, finalGroupSet);
                    if (!delTrackSet.contains(t.name())) {
                        cleanTracks.add(t);
                    }
                }
            }

            for (GroupRecord g : cleanGroups) {
                GroupRecord existing = groups.get(g.name());
                if (existing != null && editorEqual(JsonLocalStorage.toStoredGroup(existing), JsonLocalStorage.toStoredGroup(g))) {
                    continue;
                }
                storage.saveGroup(g);
                groups.put(g.name(), g);
                groupsUpdated++;
            }
            for (String delName : delGroupSet) {
                if (!groups.containsKey(delName)) {
                    continue;
                }
                storage.deleteGroup(delName);
                groups.remove(delName);
                groupsDeleted++;
            }

            for (TrackRecord t : cleanTracks) {
                TrackRecord existing = tracks.get(t.name());
                if (existing != null && editorEqual(JsonLocalStorage.toStoredTrack(existing), JsonLocalStorage.toStoredTrack(t))) {
                    continue;
                }
                storage.saveTrack(t);
                tracks.put(t.name(), t);
                tracksUpdated++;
            }
            for (String delName : delTrackSet) {
                if (!tracks.containsKey(delName)) {
                    continue;
                }
                storage.deleteTrack(delName);
                tracks.remove(delName);
                tracksDeleted++;
            }

            for (UserRecord u : cleanUsers) {
                UserRecord existing = users.get(u.uuid());
                if (existing != null && editorEqual(JsonLocalStorage.toStoredUser(existing), JsonLocalStorage.toStoredUser(u))) {
                    continue;
                }
                storage.saveUser(u);
                if (existing != null && !existing.username().isEmpty() && !existing.username().equalsIgnoreCase(u.username())) {
                    nameToUuid.remove(existing.username().toLowerCase(Locale.ROOT));
                }
                users.put(u.uuid(), u);
                if (!u.username().isEmpty()) {
                    nameToUuid.put(u.username().toLowerCase(Locale.ROOT), u.uuid());
                }
                usersUpdated++;
            }
            for (UUID delId : delUserSet) {
                UserRecord existing = users.get(delId);
                if (existing == null) {
                    continue;
                }
                storage.deleteUser(delId);
                if (!existing.username().isEmpty()) {
                    nameToUuid.remove(existing.username().toLowerCase(Locale.ROOT));
                }
                users.remove(delId);
                usersDeleted++;
            }

            int totalChanges = groupsUpdated + groupsDeleted + tracksUpdated + tracksDeleted + usersUpdated + usersDeleted;
            if (totalChanges == 0) {
                return new EditorApplyResult(true, revision.get(), 0, 0, 0, 0, 0, 0);
            }
            rev = revision.incrementAndGet();
            logActionLocked(effActor, "general", "web-editor", String.format(
                    "Applied web edits (%d groups updated, %d groups deleted, %d tracks updated, %d tracks deleted, %d users updated, %d users deleted)",
                    groupsUpdated, groupsDeleted, tracksUpdated, tracksDeleted, usersUpdated, usersDeleted));
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "reload", "", "", "", "", effActor, nowEpoch()));
        return new EditorApplyResult(
                true, rev, groupsUpdated, groupsDeleted, tracksUpdated, tracksDeleted, usersUpdated, usersDeleted);
    }

    public TreeSessionResult createTreeSession(
            String root, String actor, String commandAlias, List<String> extraPermissions) throws IOException {
        long nowMs = System.currentTimeMillis();
        long expiresAt = nowMs + WEB_SESSION_TTL_MS;
        String token = randomBase64Token(16);
        String normalizedRoot = root == null ? "." : root.trim();
        while (normalizedRoot.startsWith(".")) {
            normalizedRoot = normalizedRoot.substring(1);
        }
        if (normalizedRoot.isEmpty()) {
            normalizedRoot = ".";
        }

        TreeSet<String> allPerms = new TreeSet<>(DEFAULT_KNOWN_PERMISSIONS);
        rw.readLock().lock();
        try {
            for (GroupRecord g : groups.values()) {
                for (Node n : g.nodes()) {
                    if (!n.key().isEmpty()) {
                        allPerms.add(n.key());
                    }
                }
            }
            for (UserRecord u : users.values()) {
                for (Node n : u.nodes()) {
                    if (!n.key().isEmpty()) {
                        allPerms.add(n.key());
                    }
                }
            }
        } finally {
            rw.readLock().unlock();
        }
        if (extraPermissions != null) {
            for (String p : extraPermissions) {
                if (p != null) {
                    String t = p.trim();
                    if (!t.isEmpty() && t.length() <= MAX_EDITOR_PERM_LEN && !hasControlChars(t)) {
                        allPerms.add(t);
                    }
                }
            }
        }

        List<String> filtered = new ArrayList<>();
        String prefixLower = normalizedRoot.toLowerCase(Locale.ROOT);
        while (prefixLower.endsWith(".")) {
            prefixLower = prefixLower.substring(0, prefixLower.length() - 1);
        }
        for (String p : allPerms) {
            if (".".equals(normalizedRoot) || prefixLower.isEmpty()) {
                filtered.add(p);
            } else {
                String pl = p.toLowerCase(Locale.ROOT);
                if (pl.equals(prefixLower) || pl.startsWith(prefixLower + ".")) {
                    filtered.add(p);
                }
            }
            if (filtered.size() >= MAX_KNOWN_PERMISSIONS) {
                break;
            }
        }

        PortableWebCodec.TreeSessionMetadata meta = new PortableWebCodec.TreeSessionMetadata();
        meta.uploadedBy = (actor == null || actor.isBlank()) ? "Console" : actor.trim();
        meta.commandAlias = (commandAlias == null || commandAlias.isBlank()) ? "sp" : commandAlias.trim();
        meta.time = nowMs;
        meta.root = normalizedRoot;
        meta.count = filtered.size();
        meta.expiresAt = expiresAt;

        PortableWebCodec.TreeSessionData session = new PortableWebCodec.TreeSessionData();
        session.token = token;
        session.metadata = meta;
        session.permissions = filtered;

        String code = PortableWebCodec.encodeObject(session);
        String url = PortableWebCodec.joinWebPath(config.webBaseUrl(), "/tree#data=" + code);
        return new TreeSessionResult(token, url, expiresAt, filtered.size());
    }

    public VerboseSessionResult createVerboseSession(String actor, String commandAlias) throws IOException {
        LocalPermissionEngine.VerboseRecording rec = engine.snapshotVerboseRecording();
        long nowMs = System.currentTimeMillis();
        long expiresAt = nowMs + WEB_SESSION_TTL_MS;
        String token = randomBase64Token(16);

        String effActor = (actor == null || actor.isBlank()) ? rec.actor() : actor.trim();
        if (effActor == null || effActor.isBlank()) {
            effActor = "Console";
        }
        String alias = (commandAlias == null || commandAlias.isBlank()) ? "sp" : commandAlias.trim();
        String filterDesc = "any";
        if (!rec.targetFilter().isEmpty() && !rec.queryFilter().isEmpty()) {
            filterDesc = rec.targetFilter() + " " + rec.queryFilter();
        } else if (!rec.queryFilter().isEmpty()) {
            filterDesc = rec.queryFilter();
        } else if (!rec.targetFilter().isEmpty()) {
            filterDesc = rec.targetFilter();
        }

        long startMs = rec.startTime() == 0 ? nowMs : rec.startTime();
        long endMs = Math.max(startMs, rec.endTime());

        PortableWebCodec.VerboseSessionMetadata meta = new PortableWebCodec.VerboseSessionMetadata();
        meta.uploadedBy = effActor;
        meta.commandAlias = alias;
        meta.startTime = startMs;
        meta.endTime = endMs;
        meta.durationMs = endMs - startMs;
        meta.totalCount = rec.totalCount();
        meta.shownCount = rec.entries().size();
        meta.filter = filterDesc;
        meta.truncated = rec.truncated();
        meta.expiresAt = expiresAt;

        PortableWebCodec.VerboseSessionData session = new PortableWebCodec.VerboseSessionData();
        session.token = token;
        session.metadata = meta;
        session.entries = rec.entries();

        String code = PortableWebCodec.encodeObject(session);
        String url = PortableWebCodec.joinWebPath(config.webBaseUrl(), "/verbose#data=" + code);
        return new VerboseSessionResult(token, url, expiresAt, rec.entries().size());
    }

    // -------------------------------------------------------------------------
    // Static Temporary Modifier & Mutation Primitives (shared with conformance tests)
    // -------------------------------------------------------------------------

    public static long resolveExpiryWithModifier(
            long existingExpiry, long newExpiry, boolean isExpired, String mod, long nowEpochSeconds)
            throws ApiException {
        String cleanMod = normalizeModifier(mod);
        if (existingExpiry <= 0) {
            if ("deny".equals(cleanMod)) {
                throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
            }
            return 0L;
        }
        return switch (cleanMod) {
            case "deny" -> {
                if (!isExpired) {
                    throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
                }
                yield newExpiry;
            }
            case "accumulate" -> {
                if (isExpired || existingExpiry <= nowEpochSeconds) {
                    yield newExpiry;
                }
                if (newExpiry <= nowEpochSeconds) {
                    yield existingExpiry;
                }
                long addedSeconds = newExpiry - nowEpochSeconds;
                yield existingExpiry + addedSeconds;
            }
            default -> (!isExpired && newExpiry > 0 && existingExpiry > newExpiry) ? existingExpiry : newExpiry;
        };
    }

    public static List<Node> addNodeWithModifier(
            List<Node> existingList, Node rawNode, String mod, long nowEpochSeconds) throws ApiException {
        Map<String, String> normCtx = MetaEntry.normalizeContexts(rawNode.contexts());
        Node node = new Node(rawNode.key(), rawNode.value(), normCtx, rawNode.expiry(),
                rawNode.source(), rawNode.weight(), rawNode.user());
        List<Node> list = new ArrayList<>(existingList);
        if (node.expiry() <= 0) {
            for (int i = 0; i < list.size(); i++) {
                Node existing = list.get(i);
                if (existing.key().equals(node.key()) && MetaEntry.sameContexts(existing.contexts(), node.contexts())) {
                    list.set(i, node);
                    return list;
                }
            }
            list.add(node);
            return list;
        }
        String cleanMod = normalizeModifier(mod);
        for (int i = 0; i < list.size(); i++) {
            Node existing = list.get(i);
            if (existing.key().equals(node.key()) && MetaEntry.sameContexts(existing.contexts(), node.contexts())) {
                boolean expired = existing.isExpired(nowEpochSeconds);
                if (existing.expiry() <= 0 && !expired && "deny".equals(cleanMod)) {
                    throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
                }
                long exp = resolveExpiryWithModifier(existing.expiry(), node.expiry(), expired, cleanMod, nowEpochSeconds);
                list.set(i, new Node(node.key(), node.value(), normCtx, exp, node.source(), node.weight(), node.user()));
                return list;
            }
        }
        list.add(node);
        return list;
    }

    public static List<ParentEntry> addParentWithModifier(
            List<ParentEntry> existingList, ParentEntry rawInh, String mod, long nowEpochSeconds) throws ApiException {
        Map<String, String> normCtx = MetaEntry.normalizeContexts(rawInh.contexts());
        ParentEntry inh = new ParentEntry(rawInh.group(), rawInh.expiry(), normCtx);
        List<ParentEntry> list = new ArrayList<>(existingList);
        if (inh.expiry() <= 0) {
            for (int i = 0; i < list.size(); i++) {
                ParentEntry existing = list.get(i);
                if (existing.group().equals(inh.group()) && MetaEntry.sameContexts(existing.contexts(), inh.contexts())) {
                    list.set(i, inh);
                    return list;
                }
            }
            list.add(inh);
            return list;
        }
        String cleanMod = normalizeModifier(mod);
        for (int i = 0; i < list.size(); i++) {
            ParentEntry existing = list.get(i);
            if (existing.group().equals(inh.group()) && MetaEntry.sameContexts(existing.contexts(), inh.contexts())) {
                boolean expired = existing.isExpired(nowEpochSeconds);
                if (existing.expiry() <= 0 && !expired && "deny".equals(cleanMod)) {
                    throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
                }
                long exp = resolveExpiryWithModifier(existing.expiry(), inh.expiry(), expired, cleanMod, nowEpochSeconds);
                list.set(i, new ParentEntry(inh.group(), exp, normCtx));
                return list;
            }
        }
        list.add(inh);
        return list;
    }

    public static List<PriorityMeta> addAffixWithModifierList(
            List<PriorityMeta> existingList, PriorityMeta rawPm, String mod, long nowEpochSeconds) throws ApiException {
        Map<String, String> normCtx = MetaEntry.normalizeContexts(rawPm.contexts());
        PriorityMeta pm = new PriorityMeta(rawPm.priority(), rawPm.value(), rawPm.expiry(), normCtx);
        List<PriorityMeta> list = new ArrayList<>(existingList);
        String cleanMod = normalizeModifier(mod);
        for (int i = 0; i < list.size(); i++) {
            PriorityMeta existing = list.get(i);
            if (existing.priority() == pm.priority() && MetaEntry.sameContexts(existing.contexts(), pm.contexts())) {
                if (pm.expiry() <= 0) {
                    list.set(i, pm);
                    return list;
                }
                boolean expired = existing.isExpired(nowEpochSeconds);
                if (existing.expiry() <= 0 && !expired && "deny".equals(cleanMod)) {
                    throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
                }
                long exp = resolveExpiryWithModifier(existing.expiry(), pm.expiry(), expired, cleanMod, nowEpochSeconds);
                list.set(i, new PriorityMeta(pm.priority(), pm.value(), exp, normCtx));
                return list;
            }
        }
        list.add(pm);
        return list;
    }

    public static MetaMutationResult setMetaEntryList(
            Map<String, String> rawMeta,
            List<MetaEntry> rawEntries,
            MetaEntry rawEntry,
            String mod,
            long nowEpochSeconds) throws ApiException {
        Map<String, String> meta = new LinkedHashMap<>(rawMeta == null ? Map.of() : rawMeta);
        List<MetaEntry> entries = new ArrayList<>(rawEntries == null ? List.of() : rawEntries);
        Map<String, String> normCtx = MetaEntry.normalizeContexts(rawEntry.contexts());
        MetaEntry entry = new MetaEntry(rawEntry.key(), rawEntry.value(), rawEntry.priority(), rawEntry.expiry(), normCtx);
        String cleanMod = normalizeModifier(mod);

        if (entry.expiry() <= 0 && entry.contexts().isEmpty()) {
            meta.put(entry.key(), entry.value());
            for (int i = 0; i < entries.size(); i++) {
                MetaEntry existing = entries.get(i);
                if (existing.key().equalsIgnoreCase(entry.key())
                        && existing.contexts().isEmpty()
                        && existing.expiry() <= 0) {
                    entries.set(i, entry);
                    return new MetaMutationResult(meta, entries, true, entry);
                }
            }
            entries.add(entry);
            return new MetaMutationResult(meta, entries, true, entry);
        }

        for (int i = 0; i < entries.size(); i++) {
            MetaEntry existing = entries.get(i);
            if (existing.key().equalsIgnoreCase(entry.key())
                    && MetaEntry.sameContexts(existing.contexts(), entry.contexts())) {
                if (entry.expiry() > 0) {
                    boolean expired = existing.isExpired(nowEpochSeconds);
                    if (existing.expiry() <= 0 && !expired && "deny".equals(cleanMod)) {
                        throw new ApiException(409, "temp_entry_exists", "matching temporary entry already exists");
                    }
                    long exp = resolveExpiryWithModifier(
                            existing.expiry(), entry.expiry(), expired, cleanMod, nowEpochSeconds);
                    entry = new MetaEntry(entry.key(), entry.value(), entry.priority(), exp, normCtx);
                }
                entries.set(i, entry);
                return new MetaMutationResult(meta, entries, true, entry);
            }
        }
        entries.add(entry);
        return new MetaMutationResult(meta, entries, true, entry);
    }

    public record MetaMutationResult(
            Map<String, String> meta, List<MetaEntry> entries, boolean changed, MetaEntry stored) {
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface UserMutator {
        void mutate(UserRecord existing, MutableOutcome<UserRecord> out) throws ApiException;
    }

    @FunctionalInterface
    private interface GroupMutator {
        void mutate(GroupRecord existing, MutableOutcome<GroupRecord> out) throws ApiException;
    }

    private static final class MutableOutcome<T> {
        T next;
        String audit;
        boolean changed = true;
    }

    private MutationResult mutateUser(String idOrName, String actor, UserMutator mutator) throws IOException {
        UserRecord saved;
        long rev;
        rw.writeLock().lock();
        try {
            UserRecord existing = resolveUserLocked(idOrName);
            MutableOutcome<UserRecord> out = new MutableOutcome<>();
            mutator.mutate(existing, out);
            if (!out.changed || out.next == null) {
                return MutationResult.forUser(revision.get(), false, existing);
            }
            storage.saveUser(out.next);
            users.put(existing.uuid(), out.next);
            rev = revision.incrementAndGet();
            if (out.audit != null && !out.audit.isEmpty()) {
                logActionLocked(actor, "user", userLogTarget(out.next), out.audit);
            }
            saved = out.next;
        } finally {
            rw.writeLock().unlock();
        }
        notifyUserUpdated(rev, saved, actor);
        return MutationResult.forUser(rev, false, saved);
    }

    private MutationResult mutateGroup(String name, String actor, GroupMutator mutator) throws IOException {
        GroupRecord saved;
        long rev;
        rw.writeLock().lock();
        try {
            GroupRecord existing = groups.get(name);
            if (existing == null) {
                throw new ApiException(404, "group_not_found", "group not found: " + name);
            }
            MutableOutcome<GroupRecord> out = new MutableOutcome<>();
            mutator.mutate(existing, out);
            if (!out.changed || out.next == null) {
                return MutationResult.forGroup(revision.get(), false, false, existing);
            }
            storage.saveGroup(out.next);
            groups.put(name, out.next);
            rev = revision.incrementAndGet();
            if (out.audit != null && !out.audit.isEmpty()) {
                logActionLocked(actor, "group", name, out.audit);
            }
            saved = out.next;
        } finally {
            rw.writeLock().unlock();
        }
        notifyEvent(new ChangeEvent(rev, "group_updated", "", "", name, "", safeActor(actor), nowEpoch()));
        return MutationResult.forGroup(rev, false, false, saved);
    }

    private UserRecord resolveUserLocked(String idOrName) throws ApiException {
        String clean = idOrName == null ? "" : idOrName.trim();
        if (clean.isEmpty()) {
            throw new ApiException(400, "invalid_request", "user identifier is required");
        }
        UUID uid = tryParseUuid(clean);
        if (uid != null) {
            UserRecord u = users.get(uid);
            if (u == null) {
                throw new ApiException(404, "user_not_found", "user not found");
            }
            return u;
        }
        UUID mapped = nameToUuid.get(clean.toLowerCase(Locale.ROOT));
        UserRecord u = mapped == null ? null : users.get(mapped);
        if (u == null) {
            throw new ApiException(404, "user_not_found", "user not found");
        }
        return u;
    }

    private List<String> validateTrackGroupsLocked(List<String> rawGroups) throws ApiException {
        if (rawGroups == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(rawGroups.size());
        Set<String> seen = new HashSet<>(rawGroups.size());
        for (String raw : rawGroups) {
            String g = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (g.isEmpty()) {
                throw new ApiException(400, "invalid_track", "track group name cannot be empty");
            }
            if (!seen.add(g)) {
                throw new ApiException(400, "invalid_track", "duplicate group '" + g + "' in track");
            }
            if (!groups.containsKey(g)) {
                throw new ApiException(404, "group_not_found", "group not found: " + g);
            }
            normalized.add(g);
        }
        return normalized;
    }

    private void logActionLocked(String actor, String targetType, String target, String action) {
        if (!config.auditLogEnabled()) {
            return;
        }
        LogEntry entry = new LogEntry(nowEpoch(), safeActor(actor), targetType, target, action);
        logs.add(entry);
        if (logs.size() > JsonLocalStorage.MAX_LOG_ENTRIES) {
            logs.subList(0, logs.size() - JsonLocalStorage.MAX_LOG_ENTRIES).clear();
        }
        try {
            storage.appendLog(entry);
        } catch (IOException ignored) {
        }
    }

    private void notifyUserUpdated(long rev, UserRecord u, String actor) {
        notifyEvent(new ChangeEvent(
                rev, "user_updated", u.uuid().toString(), u.username(), "", "", safeActor(actor), nowEpoch()));
    }

    private void notifyEvent(ChangeEvent event) {
        try {
            eventListener.accept(event);
        } catch (RuntimeException ignored) {
        }
    }

    private record RemoveListResult<T>(List<T> list, boolean removed) {
    }

    private static RemoveListResult<Node> removeNodeList(
            List<Node> nodes, String key, Map<String, String> contexts, boolean tempOnly) {
        boolean removed = false;
        List<Node> kept = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            if ((!tempOnly || n.expiry() > 0) && n.key().equals(key) && MetaEntry.sameContexts(n.contexts(), contexts)) {
                removed = true;
                continue;
            }
            kept.add(n);
        }
        return new RemoveListResult<>(kept, removed);
    }

    private static RemoveListResult<ParentEntry> removeParentList(
            List<ParentEntry> parents, String group, Map<String, String> contexts, boolean tempOnly) {
        boolean removed = false;
        List<ParentEntry> kept = new ArrayList<>(parents.size());
        for (ParentEntry p : parents) {
            if ((!tempOnly || p.expiry() > 0) && p.group().equals(group)
                    && MetaEntry.sameContexts(p.contexts(), contexts)) {
                removed = true;
                continue;
            }
            kept.add(p);
        }
        return new RemoveListResult<>(kept, removed);
    }

    private static List<PriorityMeta> setAffixInContextList(List<PriorityMeta> items, PriorityMeta pm) {
        List<PriorityMeta> kept = new ArrayList<>(items.size() + 1);
        for (PriorityMeta existing : items) {
            if (MetaEntry.sameContexts(existing.contexts(), pm.contexts())) {
                continue;
            }
            kept.add(existing);
        }
        kept.add(pm);
        return kept;
    }

    private static RemoveListResult<PriorityMeta> removeAffixList(
            List<PriorityMeta> items,
            int priority,
            String valueFilter,
            Map<String, String> contexts,
            boolean tempOnly) {
        boolean removed = false;
        List<PriorityMeta> kept = new ArrayList<>(items.size());
        for (PriorityMeta existing : items) {
            if (existing.priority() == priority
                    && (!tempOnly || existing.expiry() > 0)
                    && (valueFilter.isEmpty() || existing.value().equals(valueFilter))
                    && (contexts.isEmpty() || MetaEntry.sameContexts(existing.contexts(), contexts))) {
                removed = true;
                continue;
            }
            kept.add(existing);
        }
        return new RemoveListResult<>(kept, removed);
    }

    private static MetaMutationResult unsetMetaEntryList(
            Map<String, String> rawMeta,
            List<MetaEntry> rawEntries,
            String key,
            Map<String, String> contexts,
            boolean tempOnly) {
        Map<String, String> meta = new LinkedHashMap<>(rawMeta == null ? Map.of() : rawMeta);
        boolean removed = false;
        if (!tempOnly && contexts.isEmpty()) {
            if (meta.remove(key) != null) {
                removed = true;
            } else {
                String matchKey = null;
                for (String k : meta.keySet()) {
                    if (k.equalsIgnoreCase(key)) {
                        matchKey = k;
                        break;
                    }
                }
                if (matchKey != null) {
                    meta.remove(matchKey);
                    removed = true;
                }
            }
        }
        List<MetaEntry> kept = new ArrayList<>(rawEntries.size());
        for (MetaEntry existing : rawEntries) {
            if (existing.key().equalsIgnoreCase(key)
                    && (!tempOnly || existing.expiry() > 0)
                    && ((contexts.isEmpty() && !tempOnly) || MetaEntry.sameContexts(existing.contexts(), contexts))) {
                removed = true;
                continue;
            }
            kept.add(existing);
        }
        return new MetaMutationResult(meta, kept, removed, null);
    }

    private record CleanResult<T>(T value, int cleaned) {
    }

    private static CleanResult<UserRecord> cleanUserExpired(UserRecord u, long now) {
        int[] count = {0};
        List<Node> nodes = filterExpiredNodes(u.nodes(), now, count);
        List<ParentEntry> parents = filterExpiredParents(u.parents(), now, count);
        List<PriorityMeta> prefixes = filterExpiredAffixes(u.prefixes(), now, count);
        List<PriorityMeta> suffixes = filterExpiredAffixes(u.suffixes(), now, count);
        List<MetaEntry> entries = filterExpiredMetaEntries(u.metaEntries(), now, count);
        if (count[0] == 0) {
            return new CleanResult<>(u, 0);
        }
        return new CleanResult<>(
                new UserRecord(u.uuid(), u.username(), u.primaryGroup(), parents, nodes, u.meta(), prefixes, suffixes, entries),
                count[0]);
    }

    private static CleanResult<GroupRecord> cleanGroupExpired(GroupRecord g, long now) {
        int[] count = {0};
        List<Node> nodes = filterExpiredNodes(g.nodes(), now, count);
        List<ParentEntry> parents = filterExpiredParents(g.parents(), now, count);
        List<PriorityMeta> prefixes = filterExpiredAffixes(g.prefixes(), now, count);
        List<PriorityMeta> suffixes = filterExpiredAffixes(g.suffixes(), now, count);
        List<MetaEntry> entries = filterExpiredMetaEntries(g.metaEntries(), now, count);
        if (count[0] == 0) {
            return new CleanResult<>(g, 0);
        }
        return new CleanResult<>(
                new GroupRecord(g.name(), g.displayName(), g.weight(), parents, nodes, g.meta(), prefixes, suffixes, entries),
                count[0]);
    }

    private static List<Node> filterExpiredNodes(List<Node> list, long now, int[] count) {
        List<Node> out = new ArrayList<>(list.size());
        for (Node n : list) {
            if (n.isExpired(now)) {
                count[0]++;
            } else {
                out.add(n);
            }
        }
        return out;
    }

    private static List<ParentEntry> filterExpiredParents(List<ParentEntry> list, long now, int[] count) {
        List<ParentEntry> out = new ArrayList<>(list.size());
        for (ParentEntry p : list) {
            if (p.isExpired(now)) {
                count[0]++;
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private static List<PriorityMeta> filterExpiredAffixes(List<PriorityMeta> list, long now, int[] count) {
        List<PriorityMeta> out = new ArrayList<>(list.size());
        for (PriorityMeta p : list) {
            if (p.isExpired(now)) {
                count[0]++;
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private static List<MetaEntry> filterExpiredMetaEntries(List<MetaEntry> list, long now, int[] count) {
        List<MetaEntry> out = new ArrayList<>(list.size());
        for (MetaEntry m : list) {
            if (m.isExpired(now)) {
                count[0]++;
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private record BulkSliceResult(List<Node> nodes, int affected, boolean changed) {
    }

    private static BulkSliceResult applyBulkToNodes(
            List<Node> nodes, String action, String field, String value, List<BulkConstraintRecord> constraints) {
        if (nodes.isEmpty()) {
            return new BulkSliceResult(nodes, 0, false);
        }
        List<Node> out = new ArrayList<>(nodes.size());
        int affected = 0;
        boolean changed = false;

        for (Node n : nodes) {
            if (!nodeMatchesAllConstraints(n, constraints)) {
                out.add(n);
                continue;
            }
            if ("delete".equals(action)) {
                affected++;
                changed = true;
                continue;
            }
            String nextKey = n.key();
            Map<String, String> nextCtx = new LinkedHashMap<>(n.contexts());
            switch (field) {
                case "permission" -> {
                    if (!nextKey.equals(value)) {
                        nextKey = value;
                        changed = true;
                    }
                }
                case "server", "world" -> {
                    String cur = nextCtx.get(field);
                    if (cur == null || !cur.equalsIgnoreCase(value)) {
                        nextCtx.put(field, value);
                        changed = true;
                    }
                }
                default -> {
                }
            }
            affected++;
            out.add(new Node(nextKey, n.value(), nextCtx, n.expiry(), n.source(), n.weight(), n.user()));
        }
        return new BulkSliceResult(out, affected, changed);
    }

    private static boolean nodeMatchesAllConstraints(Node n, List<BulkConstraintRecord> constraints) {
        for (BulkConstraintRecord c : constraints) {
            String actual = "";
            boolean hasField = true;
            switch (c.field()) {
                case "permission" -> actual = n.key().toLowerCase(Locale.ROOT);
                case "value" -> actual = n.value() ? "true" : "false";
                case "server", "world" -> {
                    String v = n.contexts().get(c.field());
                    if (v == null) {
                        hasField = false;
                    } else {
                        actual = v.toLowerCase(Locale.ROOT);
                    }
                }
                default -> {
                }
            }
            String pat = c.pattern().toLowerCase(Locale.ROOT);
            boolean matched = switch (c.operator()) {
                case "==" -> hasField && actual.equals(pat);
                case "!=" -> !hasField || !actual.equals(pat);
                case "~~" -> hasField && wildcardOrContains(actual, pat);
                case "!~" -> !hasField || !wildcardOrContains(actual, pat);
                default -> false;
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean nodeKeyMatchesQuery(String nodeKey, String query) {
        String lk = nodeKey.trim().toLowerCase(Locale.ROOT);
        if (lk.equals(query)) {
            return true;
        }
        if (query.indexOf('*') >= 0 || query.indexOf('?') >= 0 || query.indexOf('[') >= 0) {
            if (globMatches(query, lk)) {
                return true;
            }
            if (query.endsWith(".*")) {
                String prefix = query.substring(0, query.length() - 1);
                return lk.startsWith(prefix);
            }
        }
        return false;
    }

    private static boolean wildcardOrContains(String value, String pattern) {
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0) {
            if (globMatches(pattern, value)) {
                return true;
            }
            if (pattern.contains("%") && globMatches(pattern.replace('%', '*'), value)) {
                return true;
            }
            return false;
        }
        if (pattern.contains("%") && globMatches(pattern.replace('%', '*'), value)) {
            return true;
        }
        return value.contains(pattern);
    }

    /** Approximates Go's path.Match (`*`, `?`, `[...]` without crossing `/`). */
    private static boolean globMatches(String pattern, String target) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append("[^/]*");
                case '?' -> regex.append("[^/]");
                case '\\' -> {
                    if (i + 1 < pattern.length()) {
                        regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                    } else {
                        return false;
                    }
                }
                case '[' -> {
                    int end = pattern.indexOf(']', i + 1);
                    if (end < 0) {
                        return false;
                    }
                    String cls = pattern.substring(i + 1, end);
                    if (cls.startsWith("^")) {
                        cls = "\\^" + cls.substring(1);
                    }
                    regex.append("[").append(cls).append("]");
                    i = end;
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        regex.append("$");
        try {
            return Pattern.compile(regex.toString()).matcher(target).matches();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    // Editor payload sanitizers matching gate-stonyperms/manager/web_sessions.go

    private GroupRecord sanitizeEditorGroup(JsonLocalStorage.StoredGroup raw) throws ApiException {
        if (raw == null) {
            throw new ApiException(400, "invalid_editor_payload", "null group entry");
        }
        String name = validateEntityName(raw.name, "group");
        String display = raw.displayName == null ? "" : raw.displayName.trim();
        validateEditorText(display, "displayName on group " + name);
        List<Node> nodes = sanitizeEditorNodes(raw.nodes, "group:" + name, raw.weight, false);
        List<ParentEntry> parents = sanitizeEditorParents(raw.parents, name);
        Map<String, String> meta = sanitizeEditorMetaMap(raw.meta);
        List<MetaEntry> metaEntries = sanitizeEditorMetaEntries(raw.metaEntries, meta);
        List<PriorityMeta> prefixes = sanitizeEditorAffixes(raw.prefixes);
        List<PriorityMeta> suffixes = sanitizeEditorAffixes(raw.suffixes);
        return new GroupRecord(name, display, raw.weight, parents, nodes, meta, prefixes, suffixes, metaEntries);
    }

    private TrackRecord sanitizeEditorTrack(JsonLocalStorage.StoredTrack raw, Set<String> validGroups)
            throws ApiException {
        if (raw == null) {
            throw new ApiException(400, "invalid_editor_payload", "null track entry");
        }
        String name = validateEntityName(raw.name, "track");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (raw.groups != null) {
            for (String g : raw.groups) {
                String gn = validateEntityName(g, "track group");
                if (!validGroups.contains(gn)) {
                    throw new ApiException(400, "invalid_editor_payload",
                            "track '" + name + "' references unknown group '" + gn + "'");
                }
                if (!seen.add(gn)) {
                    throw new ApiException(400, "invalid_editor_payload",
                            "track '" + name + "' contains duplicate group '" + gn + "'");
                }
                out.add(gn);
            }
        }
        return new TrackRecord(name, out);
    }

    private UserRecord sanitizeEditorUser(JsonLocalStorage.StoredUser raw) throws ApiException {
        if (raw == null) {
            throw new ApiException(400, "invalid_editor_payload", "null user entry");
        }
        String username = raw.username == null ? "" : raw.username.trim();
        validateEditorText(username, "username");
        UUID uid = tryParseUuid(raw.uuid);
        if (uid == null || NIL_UUID.equals(uid)) {
            if (username.isEmpty()) {
                throw new ApiException(400, "invalid_editor_payload", "user entry requires uuid or username");
            }
            uid = offlinePlayerUuid(username);
        }
        String primary = raw.primaryGroup == null ? "" : raw.primaryGroup.trim().toLowerCase(Locale.ROOT);
        if (primary.isEmpty()) {
            primary = "default";
        } else {
            validateEntityName(primary, "primaryGroup");
        }
        List<Node> nodes = sanitizeEditorNodes(raw.nodes, "user", 0, true);
        List<ParentEntry> parents = sanitizeEditorParents(raw.parents, "");
        Map<String, String> meta = sanitizeEditorMetaMap(raw.meta);
        List<MetaEntry> metaEntries = sanitizeEditorMetaEntries(raw.metaEntries, meta);
        List<PriorityMeta> prefixes = sanitizeEditorAffixes(raw.prefixes);
        List<PriorityMeta> suffixes = sanitizeEditorAffixes(raw.suffixes);
        return new UserRecord(uid, username, primary, parents, nodes, meta, prefixes, suffixes, metaEntries);
    }

    private static List<Node> sanitizeEditorNodes(
            List<JsonLocalStorage.StoredNode> raw, String source, int weight, boolean user) throws ApiException {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Node> out = new ArrayList<>(raw.size());
        for (JsonLocalStorage.StoredNode n : raw) {
            if (n == null || n.key == null) {
                throw new ApiException(400, "invalid_editor_payload", "invalid permission node");
            }
            String key = n.key.trim();
            if (key.isEmpty() || key.length() > MAX_EDITOR_PERM_LEN || hasControlChars(key)) {
                throw new ApiException(400, "invalid_editor_payload", "invalid permission node: " + n.key);
            }
            Map<String, String> ctx = sanitizeEditorContexts(n.contexts);
            long exp = n.expiry == null || n.expiry < 0 ? 0L : n.expiry;
            out.add(new Node(key, n.value, ctx, exp, source, weight, user));
        }
        return out;
    }

    private static List<ParentEntry> sanitizeEditorParents(
            List<JsonLocalStorage.StoredParent> raw, String selfGroup) throws ApiException {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ParentEntry> out = new ArrayList<>(raw.size());
        for (JsonLocalStorage.StoredParent p : raw) {
            if (p == null) {
                continue;
            }
            String grp = validateEntityName(p.group, "parent group");
            if (!selfGroup.isEmpty() && grp.equalsIgnoreCase(selfGroup)) {
                throw new ApiException(400, "invalid_editor_payload", "group '" + selfGroup + "' cannot inherit from itself");
            }
            Map<String, String> ctx = sanitizeEditorContexts(p.contexts);
            long exp = p.expiry == null || p.expiry < 0 ? 0L : p.expiry;
            out.add(new ParentEntry(grp, exp, ctx));
        }
        return out;
    }

    private static Map<String, String> sanitizeEditorMetaMap(Map<String, String> raw) throws ApiException {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || k.length() > 64 || hasControlChars(k)) {
                throw new ApiException(400, "invalid_editor_payload", "invalid meta key: " + e.getKey());
            }
            String v = e.getValue() == null ? "" : e.getValue();
            validateEditorText(v, "meta value for " + k);
            out.put(k, v);
        }
        return out;
    }

    private static List<MetaEntry> sanitizeEditorMetaEntries(
            List<JsonLocalStorage.StoredMetaEntry> raw, Map<String, String> outMeta) throws ApiException {
        List<MetaEntry> out = new ArrayList<>();
        Set<String> coveredGlobal = new HashSet<>();
        if (raw != null) {
            for (JsonLocalStorage.StoredMetaEntry me : raw) {
                if (me == null) {
                    continue;
                }
                String k = me.key == null ? "" : me.key.trim();
                if (k.isEmpty() || k.length() > 64 || hasControlChars(k)) {
                    throw new ApiException(400, "invalid_editor_payload", "invalid meta key");
                }
                String v = me.value == null ? "" : me.value;
                validateEditorText(v, "meta value for " + k);
                Map<String, String> ctx = sanitizeEditorContexts(me.contexts);
                long exp = me.expiry == null || me.expiry < 0 ? 0L : me.expiry;
                int prio = me.priority == null ? 0 : me.priority;
                out.add(new MetaEntry(k, v, prio, exp, ctx));
                if (exp == 0 && ctx.isEmpty()) {
                    coveredGlobal.add(k.toLowerCase(Locale.ROOT));
                    outMeta.putIfAbsent(k, v);
                }
            }
        }
        for (Map.Entry<String, String> e : outMeta.entrySet()) {
            if (!coveredGlobal.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                out.add(new MetaEntry(e.getKey(), e.getValue(), 0, 0L, Map.of()));
            }
        }
        return out;
    }

    private static List<PriorityMeta> sanitizeEditorAffixes(List<JsonLocalStorage.StoredAffix> raw)
            throws ApiException {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<PriorityMeta> out = new ArrayList<>(raw.size());
        for (JsonLocalStorage.StoredAffix pm : raw) {
            if (pm == null) {
                continue;
            }
            String v = pm.value == null ? "" : pm.value;
            validateEditorText(v, "affix value");
            Map<String, String> ctx = sanitizeEditorContexts(pm.contexts);
            long exp = pm.expiry == null || pm.expiry < 0 ? 0L : pm.expiry;
            out.add(new PriorityMeta(pm.priority, v, exp, ctx));
        }
        return out;
    }

    private static Map<String, String> sanitizeEditorContexts(Map<String, String> raw) throws ApiException {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > MAX_EDITOR_CONTEXTS) {
            throw new ApiException(400, "invalid_editor_payload", "at most " + MAX_EDITOR_CONTEXTS + " contexts allowed");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            if (!EDITOR_CTX_KEY_PATTERN.matcher(k).matches()) {
                throw new ApiException(400, "invalid_editor_payload", "invalid context key: " + e.getKey());
            }
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.length() > MAX_EDITOR_TEXT_LEN || hasControlChars(v)) {
                throw new ApiException(400, "invalid_editor_payload", "invalid context value for " + k);
            }
            out.put(k, v);
        }
        return out;
    }

    private static void validateEditorText(String s, String what) throws ApiException {
        if (s != null && (s.length() > MAX_EDITOR_TEXT_LEN || hasControlChars(s))) {
            throw new ApiException(400, "invalid_editor_payload", "invalid " + what);
        }
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean editorEqual(Object a, Object b) {
        return JsonLocalStorage.COMPACT_GSON.toJson(a).equals(JsonLocalStorage.COMPACT_GSON.toJson(b));
    }

    private static boolean userInGroup(UserRecord u, String lowerGroup, long now) {
        if (u.primaryGroup().equalsIgnoreCase(lowerGroup)) {
            return true;
        }
        for (ParentEntry p : u.parents()) {
            if (p.group().equalsIgnoreCase(lowerGroup) && !p.isExpired(now)) {
                return true;
            }
        }
        return false;
    }

    private static List<ParentEntry> rewriteParentRefs(List<ParentEntry> list, String oldName, String newName) {
        List<ParentEntry> out = new ArrayList<>(list.size());
        for (ParentEntry p : list) {
            out.add(p.group().equalsIgnoreCase(oldName)
                    ? new ParentEntry(newName, p.expiry(), p.contexts())
                    : p);
        }
        return out;
    }

    private static List<Node> retargetGroupNodes(List<Node> list, String newGroup) {
        List<Node> out = new ArrayList<>(list.size());
        for (Node n : list) {
            out.add(new Node(n.key(), n.value(), n.contexts(), n.expiry(), "group:" + newGroup, n.weight(), false));
        }
        return out;
    }

    private static boolean hasParentRef(List<ParentEntry> parents, String target) {
        for (ParentEntry p : parents) {
            if (p.group().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrackGroupRef(List<String> groups, String target) {
        for (String g : groups) {
            if (g.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> trackGroupSet(TrackRecord track) {
        Set<String> set = new HashSet<>();
        for (String g : track.groups()) {
            set.add(g.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private static int trackIndexOf(List<String> groups, String target) {
        if (target == null || target.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private static long resolveExpiry(long durationSeconds, long expiry, long nowEpoch) {
        if (durationSeconds > 0) {
            if (durationSeconds > DurationParser.MAX_DURATION_SECONDS) {
                throw new IllegalArgumentException("Duration exceeds maximum allowed range");
            }
            return nowEpoch + durationSeconds;
        }
        return Math.max(0L, expiry);
    }

    private static String normalizePermKey(String key) throws ApiException {
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "invalid_permission", "permission key is required");
        }
        String trimmed = key.trim();
        if (trimmed.regionMatches(true, 0, "r=", 0, 2)) {
            return "r=" + trimmed.substring(2).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeModifier(String modifier) {
        if (modifier == null) {
            return "replace";
        }
        return switch (modifier.trim().toLowerCase(Locale.ROOT)) {
            case "accumulate" -> "accumulate";
            case "deny" -> "deny";
            default -> "replace";
        };
    }

    private static String modeToModifier(ParentMode mode) {
        return switch (mode) {
            case ACCUMULATE -> "accumulate";
            case DENY -> "deny";
            default -> "replace";
        };
    }

    private static String validateEntityName(String raw, String label) throws ApiException {
        String clean = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ENTITY_NAME_PATTERN.matcher(clean).matches()) {
            throw new ApiException(400, "invalid_name", label + " name must match ^[a-z0-9_+-]{1,64}$");
        }
        return clean;
    }

    private static String requireEntityKey(String raw, String label) throws ApiException {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_name", label + " name is required");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String raw, String label) throws ApiException {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_request", label + " is required");
        }
        return raw.trim();
    }

    private static String userLogTarget(UserRecord u) {
        if (u == null) {
            return "";
        }
        return u.username().isEmpty() ? u.uuid().toString() : u.username();
    }

    private static String safeActor(String actor) {
        return actor == null ? "" : actor.trim();
    }

    private static long nowEpoch() {
        return Instant.now().getEpochSecond();
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

    public static UUID offlinePlayerUuid(String username) {
        try {
            byte[] data = ("OfflinePlayer:" + username.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(data);
            md5[6] = (byte) ((md5[6] & 0x0f) | 0x30);
            md5[8] = (byte) ((md5[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (md5[i] & 0xffL);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (md5[i] & 0xffL);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String randomBase64Token(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    @Override
    public void close() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
    }
}
