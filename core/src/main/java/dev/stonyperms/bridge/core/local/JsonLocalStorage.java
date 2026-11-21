package dev.stonyperms.bridge.core.local;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Atomic JSON flat-file storage provider matching gate-stonyperms/storage/json.go layout:
 * <ul>
 *   <li>{@code groups.json} — map of lowercase group name to StoredGroup</li>
 *   <li>{@code tracks.json} — map of lowercase track name to StoredTrack</li>
 *   <li>{@code logs.json} — chronological array of LogEntry (max 1000)</li>
 *   <li>{@code users/&lt;uuid&gt;.json} — StoredUser</li>
 * </ul>
 */
public final class JsonLocalStorage {

    public static final int MAX_LOG_ENTRIES = 1000;

    static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Type GROUPS_MAP_TYPE = new TypeToken<Map<String, StoredGroup>>() {
    }.getType();
    private static final Type TRACKS_MAP_TYPE = new TypeToken<Map<String, StoredTrack>>() {
    }.getType();
    private static final Type LOGS_LIST_TYPE = new TypeToken<List<LogEntry>>() {
    }.getType();

    private final Object ioLock = new Object();
    private final Path baseDir;
    private final Path usersDir;

    public record DataBundle(
            Map<UUID, UserRecord> users,
            Map<String, GroupRecord> groups,
            Map<String, TrackRecord> tracks,
            List<LogEntry> logs) {
    }

    // Stored wire DTOs matching gate-stonyperms/model JSON tags (`omitempty`) identically.
    public static final class StoredNode {
        public String key;
        public boolean value;
        public Long expiry;
        public Map<String, String> contexts;
    }

    public static final class StoredParent {
        public String group;
        public Long expiry;
        public Map<String, String> contexts;
    }

    public static final class StoredAffix {
        public int priority;
        public String value;
        public Long expiry;
        public Map<String, String> contexts;
    }

    public static final class StoredMetaEntry {
        public String key;
        public String value;
        public Integer priority;
        public Long expiry;
        public Map<String, String> contexts;
    }

    public static final class StoredGroup {
        public String name;
        public String displayName;
        public int weight;
        public List<StoredParent> parents;
        public List<StoredNode> nodes;
        public Map<String, String> meta;
        public List<StoredMetaEntry> metaEntries;
        public List<StoredAffix> prefixes;
        public List<StoredAffix> suffixes;
    }

    public static final class StoredTrack {
        public String name;
        public List<String> groups;
    }

    public static final class StoredUser {
        public String uuid;
        public String username;
        public String primaryGroup;
        public List<StoredParent> parents;
        public List<StoredNode> nodes;
        public Map<String, String> meta;
        public List<StoredMetaEntry> metaEntries;
        public List<StoredAffix> prefixes;
        public List<StoredAffix> suffixes;
    }

    public JsonLocalStorage(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        this.usersDir = baseDir.resolve("users");
        Files.createDirectories(this.usersDir);
        ensureDefaults();
    }

    public Path baseDir() {
        return baseDir;
    }

    private Path groupsPath() {
        return baseDir.resolve("groups.json");
    }

    private Path tracksPath() {
        return baseDir.resolve("tracks.json");
    }

    private Path logsPath() {
        return baseDir.resolve("logs.json");
    }

    private Path userPath(UUID id) {
        return usersDir.resolve(id.toString() + ".json");
    }

    private void ensureDefaults() throws IOException {
        synchronized (ioLock) {
            if (Files.notExists(groupsPath())) {
                GroupRecord def = new GroupRecord(
                        "default", "&7Member", 0, List.of(),
                        List.of(
                                new Node("gate.command.server", true, 0L, Map.of()),
                                new Node("gate.command.glist", true, 0L, Map.of()),
                                new Node("gate.command.ping", true, 0L, Map.of())),
                        Map.of(),
                        List.of(new PriorityMeta(10, "&7[Member] ", 0L, Map.of())),
                        List.of(),
                        List.of());
                GroupRecord admin = new GroupRecord(
                        "admin", "&cAdmin", 100,
                        List.of(new ParentEntry("default", 0L, Map.of())),
                        List.of(
                                new Node("*", true, 0L, Map.of()),
                                new Node("stonyperms.*", true, 0L, Map.of())),
                        Map.of(),
                        List.of(new PriorityMeta(100, "&c[Admin] ", 0L, Map.of())),
                        List.of(),
                        List.of());
                Map<String, StoredGroup> groupsMap = new LinkedHashMap<>();
                groupsMap.put("default", toStoredGroup(def));
                groupsMap.put("admin", toStoredGroup(admin));
                atomicWriteJson(groupsPath(), groupsMap);
            }
            if (Files.notExists(tracksPath())) {
                Map<String, StoredTrack> tracksMap = new LinkedHashMap<>();
                tracksMap.put("ranks", toStoredTrack(new TrackRecord("ranks", List.of("default", "admin"))));
                atomicWriteJson(tracksPath(), tracksMap);
            }
            if (Files.notExists(logsPath())) {
                List<LogEntry> initial = List.of(new LogEntry(
                        Instant.now().getEpochSecond(), "System", "general", "system", "Initialized StonyPerms storage"));
                atomicWriteJson(logsPath(), initial);
            }
        }
    }

    public DataBundle loadAll() throws IOException {
        synchronized (ioLock) {
            Map<String, GroupRecord> groups = new LinkedHashMap<>();
            if (Files.isRegularFile(groupsPath())) {
                String raw = Files.readString(groupsPath(), StandardCharsets.UTF_8);
                Map<String, StoredGroup> parsed = parseJsonOrNull(raw, GROUPS_MAP_TYPE);
                if (parsed != null) {
                    for (Map.Entry<String, StoredGroup> e : parsed.entrySet()) {
                        if (e.getValue() != null) {
                            e.getValue().name = e.getKey().toLowerCase(Locale.ROOT);
                            GroupRecord rec = fromStoredGroup(e.getValue());
                            groups.put(rec.name(), rec);
                        }
                    }
                }
            }

            Map<String, TrackRecord> tracks = new LinkedHashMap<>();
            if (Files.isRegularFile(tracksPath())) {
                String raw = Files.readString(tracksPath(), StandardCharsets.UTF_8);
                Map<String, StoredTrack> parsed = parseJsonOrNull(raw, TRACKS_MAP_TYPE);
                if (parsed != null) {
                    for (Map.Entry<String, StoredTrack> e : parsed.entrySet()) {
                        if (e.getValue() != null) {
                            e.getValue().name = e.getKey().toLowerCase(Locale.ROOT);
                            TrackRecord rec = fromStoredTrack(e.getValue());
                            tracks.put(rec.name(), rec);
                        }
                    }
                }
            }

            List<LogEntry> logs = new ArrayList<>();
            if (Files.isRegularFile(logsPath())) {
                String raw = Files.readString(logsPath(), StandardCharsets.UTF_8);
                List<LogEntry> parsed = parseJsonOrNull(raw, LOGS_LIST_TYPE);
                if (parsed != null) {
                    logs.addAll(parsed);
                }
            }

            Map<UUID, UserRecord> users = new LinkedHashMap<>();
            if (Files.isDirectory(usersDir)) {
                try (Stream<Path> stream = Files.list(usersDir)) {
                    for (Path file : stream.toList()) {
                        if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".json")) {
                            continue;
                        }
                        try {
                            String raw = Files.readString(file, StandardCharsets.UTF_8);
                            StoredUser su = PRETTY_GSON.fromJson(raw, StoredUser.class);
                            if (su != null && su.uuid != null && !su.uuid.isBlank()) {
                                UserRecord user = fromStoredUser(su);
                                if (user.uuid() != null) {
                                    users.put(user.uuid(), user);
                                }
                            }
                        } catch (RuntimeException | IOException ignored) {
                        }
                    }
                }
            }
            return new DataBundle(users, groups, tracks, logs);
        }
    }

    public void saveUser(UserRecord user) throws IOException {
        if (user == null || user.uuid() == null) {
            throw new IOException("Invalid user for save");
        }
        synchronized (ioLock) {
            atomicWriteJson(userPath(user.uuid()), toStoredUser(user));
        }
    }

    public void deleteUser(UUID id) throws IOException {
        if (id == null) {
            return;
        }
        synchronized (ioLock) {
            Files.deleteIfExists(userPath(id));
        }
    }

    public void saveGroup(GroupRecord group) throws IOException {
        if (group == null || group.name().isEmpty()) {
            throw new IOException("Invalid group for save");
        }
        synchronized (ioLock) {
            Map<String, StoredGroup> groupsMap = new LinkedHashMap<>();
            if (Files.isRegularFile(groupsPath())) {
                String raw = Files.readString(groupsPath(), StandardCharsets.UTF_8);
                Map<String, StoredGroup> existing = parseJsonOrNull(raw, GROUPS_MAP_TYPE);
                if (existing != null) {
                    groupsMap.putAll(existing);
                }
            }
            String key = group.name().toLowerCase(Locale.ROOT);
            groupsMap.put(key, toStoredGroup(group));
            atomicWriteJson(groupsPath(), groupsMap);
        }
    }

    public void deleteGroup(String name) throws IOException {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        synchronized (ioLock) {
            Map<String, StoredGroup> groupsMap = new LinkedHashMap<>();
            if (Files.isRegularFile(groupsPath())) {
                String raw = Files.readString(groupsPath(), StandardCharsets.UTF_8);
                Map<String, StoredGroup> existing = parseJsonOrNull(raw, GROUPS_MAP_TYPE);
                if (existing != null) {
                    groupsMap.putAll(existing);
                }
            }
            groupsMap.remove(key);
            atomicWriteJson(groupsPath(), groupsMap);
        }
    }

    public void saveTrack(TrackRecord track) throws IOException {
        if (track == null || track.name().isEmpty()) {
            throw new IOException("Invalid track for save");
        }
        synchronized (ioLock) {
            Map<String, StoredTrack> tracksMap = new LinkedHashMap<>();
            if (Files.isRegularFile(tracksPath())) {
                String raw = Files.readString(tracksPath(), StandardCharsets.UTF_8);
                Map<String, StoredTrack> existing = parseJsonOrNull(raw, TRACKS_MAP_TYPE);
                if (existing != null) {
                    tracksMap.putAll(existing);
                }
            }
            String key = track.name().toLowerCase(Locale.ROOT);
            tracksMap.put(key, toStoredTrack(track));
            atomicWriteJson(tracksPath(), tracksMap);
        }
    }

    public void deleteTrack(String name) throws IOException {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        synchronized (ioLock) {
            Map<String, StoredTrack> tracksMap = new LinkedHashMap<>();
            if (Files.isRegularFile(tracksPath())) {
                String raw = Files.readString(tracksPath(), StandardCharsets.UTF_8);
                Map<String, StoredTrack> existing = parseJsonOrNull(raw, TRACKS_MAP_TYPE);
                if (existing != null) {
                    tracksMap.putAll(existing);
                }
            }
            tracksMap.remove(key);
            atomicWriteJson(tracksPath(), tracksMap);
        }
    }

    public void appendLog(LogEntry entry) throws IOException {
        synchronized (ioLock) {
            List<LogEntry> logs = new ArrayList<>();
            if (Files.isRegularFile(logsPath())) {
                String raw = Files.readString(logsPath(), StandardCharsets.UTF_8);
                List<LogEntry> existing = parseJsonOrNull(raw, LOGS_LIST_TYPE);
                if (existing != null) {
                    logs.addAll(existing);
                }
            }
            logs.add(entry);
            if (logs.size() > MAX_LOG_ENTRIES) {
                logs = new ArrayList<>(logs.subList(logs.size() - MAX_LOG_ENTRIES, logs.size()));
            }
            atomicWriteJson(logsPath(), logs);
        }
    }

    // -------------------------------------------------------------------------
    // Mapping helpers shared with PortableWebCodec
    // -------------------------------------------------------------------------

    public static StoredGroup toStoredGroup(GroupRecord g) {
        StoredGroup s = new StoredGroup();
        s.name = g.name().toLowerCase(Locale.ROOT);
        s.displayName = g.displayName().isEmpty() ? null : g.displayName();
        s.weight = g.weight();
        s.parents = toStoredParents(g.parents());
        s.nodes = toStoredNodes(g.nodes());
        s.meta = g.meta().isEmpty() ? null : new LinkedHashMap<>(g.meta());
        s.metaEntries = toStoredMetaEntries(g.metaEntries());
        s.prefixes = toStoredAffixes(g.prefixes());
        s.suffixes = toStoredAffixes(g.suffixes());
        return s;
    }

    public static GroupRecord fromStoredGroup(StoredGroup s) {
        String name = s.name == null ? "" : s.name.trim().toLowerCase(Locale.ROOT);
        return new GroupRecord(
                name,
                s.displayName == null ? "" : s.displayName,
                s.weight,
                fromStoredParents(s.parents),
                fromStoredNodes(s.nodes, "group:" + name, s.weight, false),
                s.meta == null ? Map.of() : s.meta,
                fromStoredAffixes(s.prefixes),
                fromStoredAffixes(s.suffixes),
                fromStoredMetaEntries(s.metaEntries));
    }

    public static StoredTrack toStoredTrack(TrackRecord t) {
        StoredTrack s = new StoredTrack();
        s.name = t.name().toLowerCase(Locale.ROOT);
        s.groups = new ArrayList<>(t.groups());
        return s;
    }

    public static TrackRecord fromStoredTrack(StoredTrack s) {
        String name = s.name == null ? "" : s.name.trim().toLowerCase(Locale.ROOT);
        List<String> groups = s.groups == null ? List.of() : s.groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(g -> g.trim().toLowerCase(Locale.ROOT))
                .toList();
        return new TrackRecord(name, groups);
    }

    public static StoredUser toStoredUser(UserRecord u) {
        StoredUser s = new StoredUser();
        s.uuid = u.uuid().toString();
        s.username = u.username();
        s.primaryGroup = u.primaryGroup();
        s.parents = toStoredParents(u.parents());
        s.nodes = toStoredNodes(u.nodes());
        s.meta = u.meta().isEmpty() ? null : new LinkedHashMap<>(u.meta());
        s.metaEntries = toStoredMetaEntries(u.metaEntries());
        s.prefixes = toStoredAffixes(u.prefixes());
        s.suffixes = toStoredAffixes(u.suffixes());
        return s;
    }

    public static UserRecord fromStoredUser(StoredUser s) {
        UUID uid = (s.uuid == null || s.uuid.isBlank()) ? null : parseUuidLenient(s.uuid);
        return new UserRecord(
                uid,
                s.username == null ? "" : s.username,
                s.primaryGroup == null || s.primaryGroup.isBlank() ? "default" : s.primaryGroup.trim().toLowerCase(Locale.ROOT),
                fromStoredParents(s.parents),
                fromStoredNodes(s.nodes, "user", 0, true),
                s.meta == null ? Map.of() : s.meta,
                fromStoredAffixes(s.prefixes),
                fromStoredAffixes(s.suffixes),
                fromStoredMetaEntries(s.metaEntries));
    }

    private static UUID parseUuidLenient(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<StoredNode> toStoredNodes(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        List<StoredNode> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            StoredNode sn = new StoredNode();
            sn.key = n.key();
            sn.value = n.value();
            sn.expiry = n.expiry() > 0 ? n.expiry() : null;
            sn.contexts = n.contexts().isEmpty() ? null : new LinkedHashMap<>(n.contexts());
            out.add(sn);
        }
        return out;
    }

    private static List<Node> fromStoredNodes(List<StoredNode> list, String source, int weight, boolean user) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Node> out = new ArrayList<>(list.size());
        for (StoredNode sn : list) {
            if (sn == null || sn.key == null || sn.key.isBlank()) {
                continue;
            }
            out.add(new Node(
                    sn.key,
                    sn.value,
                    MetaEntry.normalizeContexts(sn.contexts),
                    sn.expiry == null ? 0L : sn.expiry,
                    source,
                    weight,
                    user));
        }
        return out;
    }

    private static List<StoredParent> toStoredParents(List<ParentEntry> parents) {
        if (parents == null || parents.isEmpty()) {
            return null;
        }
        List<StoredParent> out = new ArrayList<>(parents.size());
        for (ParentEntry p : parents) {
            StoredParent sp = new StoredParent();
            sp.group = p.group().toLowerCase(Locale.ROOT);
            sp.expiry = p.expiry() > 0 ? p.expiry() : null;
            sp.contexts = p.contexts().isEmpty() ? null : new LinkedHashMap<>(p.contexts());
            out.add(sp);
        }
        return out;
    }

    private static List<ParentEntry> fromStoredParents(List<StoredParent> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<ParentEntry> out = new ArrayList<>(list.size());
        for (StoredParent sp : list) {
            if (sp == null || sp.group == null || sp.group.isBlank()) {
                continue;
            }
            out.add(new ParentEntry(
                    sp.group,
                    sp.expiry == null ? 0L : sp.expiry,
                    MetaEntry.normalizeContexts(sp.contexts)));
        }
        return out;
    }

    private static List<StoredAffix> toStoredAffixes(List<PriorityMeta> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<StoredAffix> out = new ArrayList<>(items.size());
        for (PriorityMeta pm : items) {
            StoredAffix sa = new StoredAffix();
            sa.priority = pm.priority();
            sa.value = pm.value();
            sa.expiry = pm.expiry() > 0 ? pm.expiry() : null;
            sa.contexts = pm.contexts().isEmpty() ? null : new LinkedHashMap<>(pm.contexts());
            out.add(sa);
        }
        return out;
    }

    private static List<PriorityMeta> fromStoredAffixes(List<StoredAffix> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<PriorityMeta> out = new ArrayList<>(list.size());
        for (StoredAffix sa : list) {
            if (sa == null) {
                continue;
            }
            out.add(new PriorityMeta(
                    sa.priority,
                    sa.value,
                    sa.expiry == null ? 0L : sa.expiry,
                    MetaEntry.normalizeContexts(sa.contexts)));
        }
        return out;
    }

    private static List<StoredMetaEntry> toStoredMetaEntries(List<MetaEntry> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<StoredMetaEntry> out = new ArrayList<>(items.size());
        for (MetaEntry me : items) {
            StoredMetaEntry sm = new StoredMetaEntry();
            sm.key = me.key();
            sm.value = me.value();
            sm.priority = me.priority() != 0 ? me.priority() : null;
            sm.expiry = me.expiry() > 0 ? me.expiry() : null;
            sm.contexts = me.contexts().isEmpty() ? null : new LinkedHashMap<>(me.contexts());
            out.add(sm);
        }
        return out;
    }

    private static List<MetaEntry> fromStoredMetaEntries(List<StoredMetaEntry> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<MetaEntry> out = new ArrayList<>(list.size());
        for (StoredMetaEntry sm : list) {
            if (sm == null || sm.key == null || sm.key.isBlank()) {
                continue;
            }
            out.add(new MetaEntry(
                    sm.key,
                    sm.value,
                    sm.priority == null ? 0 : sm.priority,
                    sm.expiry == null ? 0L : sm.expiry,
                    MetaEntry.normalizeContexts(sm.contexts)));
        }
        return out;
    }

    /** Utility used by tests and interop checks to compare canonical stored JSON representations. */
    public static JsonElement toJsonTree(Object value) {
        return PRETTY_GSON.toJsonTree(value);
    }

    public static JsonObject parseRawJson(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static <T> T parseJsonOrNull(String raw, Type type) {
        try {
            return PRETTY_GSON.fromJson(raw, type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void atomicWriteJson(Path target, Object data) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = PRETTY_GSON.toJson(data) + "\n";
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
