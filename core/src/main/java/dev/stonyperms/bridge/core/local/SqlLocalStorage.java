package dev.stonyperms.bridge.core.local;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Relational SQL storage implementation for SQLite, MySQL/MariaDB, and PostgreSQL
 * conforming to the canonical schema v1 defined in {@code spec/SPEC.md}.
 */
public final class SqlLocalStorage implements LocalStorage {

    public static final int SCHEMA_VERSION = 1;

    public enum Dialect {
        SQLITE("sqlite"),
        MYSQL("mysql"),
        MARIADB("mariadb"),
        POSTGRESQL("postgresql");

        private final String id;

        Dialect(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Dialect fromType(String raw) {
            String norm = StandaloneConfig.normalizeStorageType(raw);
            return switch (norm) {
                case "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "mariadb" -> MARIADB;
                case "postgresql" -> POSTGRESQL;
                default -> throw new IllegalArgumentException("Not an SQL storage type: " + raw);
            };
        }
    }

    public record TargetSpec(Dialect dialect, String jdbcUrl, String driverClass, String redactedTarget) {
    }

    private final Dialect dialect;
    private final String prefix;
    private final String target;
    private final HikariDataSource dataSource;
    private final Object sqliteWriteLock = new Object();

    public SqlLocalStorage(String storageType, Path storageDir, DatabaseConfig dbCfg) throws IOException {
        this(storageType, storageDir, dbCfg, resolveDbPassword(storageType, dbCfg));
    }

    SqlLocalStorage(String storageType, Path storageDir, DatabaseConfig dbCfg, String resolvedPassword)
            throws IOException {
        this.dialect = Dialect.fromType(storageType);
        DatabaseConfig cfg = dbCfg == null ? DatabaseConfig.defaults() : dbCfg;
        DatabaseConfig.validateTablePrefix(cfg.tablePrefix());
        this.prefix = cfg.tablePrefix();

        TargetSpec spec = buildTargetSpec(dialect, storageDir, cfg);
        this.target = spec.redactedTarget();

        if (dialect == DialectSQLite()) {
            String f = cfg.file().isEmpty() ? storageDir.resolve("stonyperms.db").toString() : cfg.file();
            if (!":memory:".equals(f) && !f.startsWith("file:")) {
                Path parent = Path.of(f).getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
        }

        HikariConfig hc = new HikariConfig();
        hc.setDriverClassName(spec.driverClass());
        hc.setJdbcUrl(spec.jdbcUrl());
        hc.setPoolName("StonyPerms-" + dialect.id());
        if (dialect != Dialect.SQLITE) {
            hc.setUsername(cfg.username());
            hc.setPassword(resolvedPassword == null ? "" : resolvedPassword);
            hc.setMaximumPoolSize(cfg.maxPoolSize());
            hc.setMinimumIdle(cfg.minIdle());
            hc.setMaxLifetime(cfg.maxLifetimeSeconds() * 1000L);
            hc.setConnectionTimeout(cfg.connectTimeoutSeconds() * 1000L);
        } else {
            hc.setMaximumPoolSize(1);
            hc.setMinimumIdle(1);
            hc.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");
        }

        try {
            this.dataSource = new HikariDataSource(hc);
        } catch (RuntimeException error) {
            throw new IOException("Failed to open " + dialect.id() + " pool (" + target + "): " + error.getMessage(), error);
        }

        try {
            initSchemaAndSeed();
        } catch (IOException | RuntimeException error) {
            try {
                this.dataSource.close();
            } catch (RuntimeException ignored) {
            }
            throw error;
        }
    }

    private static Dialect DialectSQLite() {
        return Dialect.SQLITE;
    }

    private static String resolveDbPassword(String storageType, DatabaseConfig dbCfg) throws IOException {
        Dialect d = Dialect.fromType(storageType);
        if (d == Dialect.SQLITE || dbCfg == null) {
            return "";
        }
        return dbCfg.resolvePassword();
    }

    public static TargetSpec buildTargetSpec(Dialect dialect, Path storageDir, DatabaseConfig cfg) {
        String prefix = cfg.tablePrefix();
        return switch (dialect) {
            case SQLITE -> {
                String file = cfg.file().isEmpty()
                        ? storageDir.resolve("stonyperms.db").toString()
                        : cfg.file();
                String jdbcUrl = file.startsWith("jdbc:sqlite:") ? file : "jdbc:sqlite:" + file;
                yield new TargetSpec(dialect, jdbcUrl, "org.sqlite.JDBC", "sqlite:" + file);
            }
            case MYSQL, MARIADB -> {
                int port = cfg.port() > 0 ? cfg.port() : 3306;
                String ssl = mapMariaSslMode(cfg.sslMode());
                int timeoutMs = cfg.connectTimeoutSeconds() * 1000;
                String jdbcUrl = "jdbc:mariadb://" + cfg.host() + ":" + port + "/"
                        + urlEncode(cfg.database())
                        + "?useUnicode=true&characterEncoding=utf8mb4"
                        + "&connectTimeout=" + timeoutMs
                        + "&sslMode=" + urlEncode(ssl)
                        + "&allowPublicKeyRetrieval=true";
                String safe = dialect.id() + "://" + cfg.username() + "@" + cfg.host() + ":" + port
                        + "/" + cfg.database() + "?prefix=" + prefix;
                yield new TargetSpec(dialect, jdbcUrl, "org.mariadb.jdbc.Driver", safe);
            }
            case POSTGRESQL -> {
                int port = cfg.port() > 0 ? cfg.port() : 5432;
                String ssl = mapPostgresSslMode(cfg.sslMode());
                String jdbcUrl = "jdbc:postgresql://" + cfg.host() + ":" + port + "/"
                        + urlEncode(cfg.database())
                        + "?sslmode=" + urlEncode(ssl)
                        + "&connectTimeout=" + cfg.connectTimeoutSeconds();
                String safe = "postgresql://" + cfg.username() + "@" + cfg.host() + ":" + port
                        + "/" + cfg.database() + "?sslmode=" + ssl + "&prefix=" + prefix;
                yield new TargetSpec(dialect, jdbcUrl, "org.postgresql.Driver", safe);
            }
        };
    }

    public static String mapMariaSslMode(String raw) {
        String clean = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (clean) {
            case "disable", "false", "none" -> "disable";
            case "trust", "skip-verify", "require", "true" -> "trust";
            case "verify-ca" -> "verify-ca";
            case "verify-full" -> "verify-full";
            default -> "allow";
        };
    }

    public static String mapPostgresSslMode(String raw) {
        String clean = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (clean) {
            case "disable", "false", "none" -> "disable";
            case "allow" -> "allow";
            case "require", "true", "skip-verify" -> "require";
            case "verify-ca" -> "verify-ca";
            case "verify-full" -> "verify-full";
            default -> "prefer";
        };
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String type() {
        return dialect.id();
    }

    @Override
    public String describeTarget() {
        return target;
    }

    public String tablePrefix() {
        return prefix;
    }

    public Dialect dialect() {
        return dialect;
    }

    private String tbl(String name) {
        return prefix + name;
    }

    public static List<String> ddlStatements(Dialect dialect, String p) {
        String autoPk = switch (dialect) {
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case POSTGRESQL -> "BIGSERIAL PRIMARY KEY";
            case MYSQL, MARIADB -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
        };
        boolean isMy = (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB);
        String metaCollate = isMy ? " COLLATE utf8mb4_bin" : "";
        String tableSuffix = isMy ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";
        String groupNodesIdx = isMy ? ", INDEX idx_perm_key (perm_key)" : "";
        String userNodesIdx = isMy ? ", INDEX idx_perm_key (perm_key)" : "";
        String usersNameIdx = isMy ? ", INDEX idx_username (username)" : "";
        String logsIdx = isMy ? ", INDEX idx_ts_id (timestamp, id)" : "";

        List<String> stmts = new ArrayList<>();
        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "schema_meta ("
                + "version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "groups ("
                + "name VARCHAR(64) PRIMARY KEY, "
                + "display_name VARCHAR(255) NOT NULL DEFAULT '', "
                + "weight INTEGER NOT NULL DEFAULT 0)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "group_nodes ("
                + "group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, "
                + "perm_key VARCHAR(255) NOT NULL, value BOOLEAN NOT NULL, "
                + "expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, "
                + "PRIMARY KEY (group_name, position), "
                + "FOREIGN KEY (group_name) REFERENCES " + p + "groups(name) ON DELETE CASCADE"
                + groupNodesIdx + ")" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "group_parents ("
                + "group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, "
                + "parent_group VARCHAR(64) NOT NULL, expiry BIGINT NOT NULL DEFAULT 0, "
                + "contexts TEXT NOT NULL, PRIMARY KEY (group_name, position), "
                + "FOREIGN KEY (group_name) REFERENCES " + p + "groups(name) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "group_affixes ("
                + "group_name VARCHAR(64) NOT NULL, kind VARCHAR(8) NOT NULL, position INTEGER NOT NULL, "
                + "priority INTEGER NOT NULL DEFAULT 0, value TEXT NOT NULL, "
                + "expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, "
                + "PRIMARY KEY (group_name, kind, position), "
                + "FOREIGN KEY (group_name) REFERENCES " + p + "groups(name) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "group_meta ("
                + "group_name VARCHAR(64) NOT NULL, meta_key VARCHAR(128)" + metaCollate + " NOT NULL, "
                + "meta_value TEXT NOT NULL, PRIMARY KEY (group_name, meta_key), "
                + "FOREIGN KEY (group_name) REFERENCES " + p + "groups(name) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "group_meta_entries ("
                + "group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, "
                + "meta_key VARCHAR(128) NOT NULL, meta_value TEXT NOT NULL, "
                + "priority INTEGER NOT NULL DEFAULT 0, expiry BIGINT NOT NULL DEFAULT 0, "
                + "contexts TEXT NOT NULL, PRIMARY KEY (group_name, position), "
                + "FOREIGN KEY (group_name) REFERENCES " + p + "groups(name) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "users ("
                + "uuid CHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL DEFAULT '', "
                + "primary_group VARCHAR(64) NOT NULL DEFAULT 'default'" + usersNameIdx + ")" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "user_nodes ("
                + "user_uuid CHAR(36) NOT NULL, position INTEGER NOT NULL, "
                + "perm_key VARCHAR(255) NOT NULL, value BOOLEAN NOT NULL, "
                + "expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, "
                + "PRIMARY KEY (user_uuid, position), "
                + "FOREIGN KEY (user_uuid) REFERENCES " + p + "users(uuid) ON DELETE CASCADE"
                + userNodesIdx + ")" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "user_parents ("
                + "user_uuid CHAR(36) NOT NULL, position INTEGER NOT NULL, "
                + "parent_group VARCHAR(64) NOT NULL, expiry BIGINT NOT NULL DEFAULT 0, "
                + "contexts TEXT NOT NULL, PRIMARY KEY (user_uuid, position), "
                + "FOREIGN KEY (user_uuid) REFERENCES " + p + "users(uuid) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "user_affixes ("
                + "user_uuid CHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL, position INTEGER NOT NULL, "
                + "priority INTEGER NOT NULL DEFAULT 0, value TEXT NOT NULL, "
                + "expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, "
                + "PRIMARY KEY (user_uuid, kind, position), "
                + "FOREIGN KEY (user_uuid) REFERENCES " + p + "users(uuid) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "user_meta ("
                + "user_uuid CHAR(36) NOT NULL, meta_key VARCHAR(128)" + metaCollate + " NOT NULL, "
                + "meta_value TEXT NOT NULL, PRIMARY KEY (user_uuid, meta_key), "
                + "FOREIGN KEY (user_uuid) REFERENCES " + p + "users(uuid) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "user_meta_entries ("
                + "user_uuid CHAR(36) NOT NULL, position INTEGER NOT NULL, "
                + "meta_key VARCHAR(128) NOT NULL, meta_value TEXT NOT NULL, "
                + "priority INTEGER NOT NULL DEFAULT 0, expiry BIGINT NOT NULL DEFAULT 0, "
                + "contexts TEXT NOT NULL, PRIMARY KEY (user_uuid, position), "
                + "FOREIGN KEY (user_uuid) REFERENCES " + p + "users(uuid) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "tracks ("
                + "name VARCHAR(64) PRIMARY KEY)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "track_groups ("
                + "track_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, "
                + "group_name VARCHAR(64) NOT NULL, PRIMARY KEY (track_name, position), "
                + "FOREIGN KEY (track_name) REFERENCES " + p + "tracks(name) ON DELETE CASCADE)" + tableSuffix);

        stmts.add("CREATE TABLE IF NOT EXISTS " + p + "logs ("
                + "id " + autoPk + ", timestamp BIGINT NOT NULL, actor VARCHAR(128) NOT NULL, "
                + "target_type VARCHAR(32) NOT NULL, target VARCHAR(128) NOT NULL, "
                + "action TEXT NOT NULL" + logsIdx + ")" + tableSuffix);

        if (!isMy) {
            stmts.add("CREATE INDEX IF NOT EXISTS idx_" + p + "group_nodes_key ON " + p + "group_nodes (perm_key)");
            stmts.add("CREATE INDEX IF NOT EXISTS idx_" + p + "user_nodes_key ON " + p + "user_nodes (perm_key)");
            stmts.add("CREATE INDEX IF NOT EXISTS idx_" + p + "users_username ON " + p + "users (username)");
            stmts.add("CREATE INDEX IF NOT EXISTS idx_" + p + "logs_ts_id ON " + p + "logs (timestamp, id)");
        }
        return stmts;
    }

    private void initSchemaAndSeed() throws IOException {
        try {
            if (dialect == Dialect.SQLITE) {
                synchronized (sqliteWriteLock) {
                    initSchemaAndSeedInner();
                }
            } else {
                initSchemaAndSeedInner();
            }
        } catch (SQLException error) {
            throw new IOException("Failed initializing SQL schema (" + target + "): " + error.getMessage(), error);
        }
    }

    private void initSchemaAndSeedInner() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            if (dialect == Dialect.SQLITE) {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                }
            }
            for (String ddl : ddlStatements(dialect, prefix)) {
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                }
            }

            runInTransaction(conn, txConn -> {
                long now = Instant.now().getEpochSecond();
                String metaSql = (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB)
                        ? "INSERT IGNORE INTO " + tbl("schema_meta") + " (version, applied_at) VALUES (?, ?)"
                        : "INSERT INTO " + tbl("schema_meta") + " (version, applied_at) VALUES (?, ?) ON CONFLICT(version) DO NOTHING";
                try (PreparedStatement ps = txConn.prepareStatement(metaSql)) {
                    ps.setInt(1, SCHEMA_VERSION);
                    ps.setLong(2, now);
                    ps.executeUpdate();
                }

                int groupCount = queryCount(txConn, tbl("groups"));
                if (groupCount == 0) {
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
                    saveGroupTx(txConn, def);
                    saveGroupTx(txConn, admin);

                    if (queryCount(txConn, tbl("tracks")) == 0) {
                        saveTrackTx(txConn, new TrackRecord("ranks", List.of("default", "admin")));
                    }
                    if (queryCount(txConn, tbl("logs")) == 0) {
                        appendLogTx(txConn, new LogEntry(now, "System", "general", "system", "Initialized StonyPerms storage"));
                    }
                }
            });
        }
    }

    private static int queryCount(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public JsonLocalStorage.DataBundle loadAll() throws IOException {
        try (Connection conn = dataSource.getConnection()) {
            Map<String, GroupRecord> groups = loadGroups(conn);
            Map<String, TrackRecord> tracks = loadTracks(conn);
            Map<UUID, UserRecord> users = loadUsers(conn);
            List<LogEntry> logs = loadLogs(conn, JsonLocalStorage.MAX_LOG_ENTRIES);
            return new JsonLocalStorage.DataBundle(users, groups, tracks, logs);
        } catch (SQLException error) {
            throw new IOException("SQL loadAll failed (" + target + "): " + error.getMessage(), error);
        }
    }

    private Map<String, GroupRecord> loadGroups(Connection conn) throws SQLException {
        Map<String, GroupBuilder> builders = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, display_name, weight FROM " + tbl("groups") + " ORDER BY name ASC")) {
            while (rs.next()) {
                String name = rs.getString("name").trim().toLowerCase(Locale.ROOT);
                builders.put(name, new GroupBuilder(name, rs.getString("display_name"), rs.getInt("weight")));
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_name, perm_key, value, expiry, contexts FROM "
                     + tbl("group_nodes") + " ORDER BY group_name ASC, position ASC")) {
            while (rs.next()) {
                String gn = rs.getString("group_name").trim().toLowerCase(Locale.ROOT);
                GroupBuilder b = builders.get(gn);
                if (b != null) {
                    b.nodes.add(new Node(
                            rs.getString("perm_key"),
                            rs.getBoolean("value"),
                            JsonLocalStorage.deserializeContexts(rs.getString("contexts")),
                            rs.getLong("expiry"),
                            "group:" + gn,
                            b.weight,
                            false));
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_name, parent_group, expiry, contexts FROM "
                     + tbl("group_parents") + " ORDER BY group_name ASC, position ASC")) {
            while (rs.next()) {
                GroupBuilder b = builders.get(rs.getString("group_name").trim().toLowerCase(Locale.ROOT));
                if (b != null) {
                    b.parents.add(new ParentEntry(
                            rs.getString("parent_group"),
                            rs.getLong("expiry"),
                            JsonLocalStorage.deserializeContexts(rs.getString("contexts"))));
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_name, kind, priority, value, expiry, contexts FROM "
                     + tbl("group_affixes") + " ORDER BY group_name ASC, kind ASC, position ASC")) {
            while (rs.next()) {
                GroupBuilder b = builders.get(rs.getString("group_name").trim().toLowerCase(Locale.ROOT));
                if (b != null) {
                    PriorityMeta pm = new PriorityMeta(
                            rs.getInt("priority"),
                            rs.getString("value"),
                            rs.getLong("expiry"),
                            JsonLocalStorage.deserializeContexts(rs.getString("contexts")));
                    if ("suffix".equals(rs.getString("kind"))) {
                        b.suffixes.add(pm);
                    } else {
                        b.prefixes.add(pm);
                    }
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_name, meta_key, meta_value FROM " + tbl("group_meta"))) {
            while (rs.next()) {
                GroupBuilder b = builders.get(rs.getString("group_name").trim().toLowerCase(Locale.ROOT));
                if (b != null) {
                    b.meta.put(rs.getString("meta_key"), rs.getString("meta_value"));
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT group_name, meta_key, meta_value, priority, expiry, contexts FROM "
                     + tbl("group_meta_entries") + " ORDER BY group_name ASC, position ASC")) {
            while (rs.next()) {
                GroupBuilder b = builders.get(rs.getString("group_name").trim().toLowerCase(Locale.ROOT));
                if (b != null) {
                    b.metaEntries.add(new MetaEntry(
                            rs.getString("meta_key"),
                            rs.getString("meta_value"),
                            rs.getInt("priority"),
                            rs.getLong("expiry"),
                            JsonLocalStorage.deserializeContexts(rs.getString("contexts"))));
                }
            }
        }

        Map<String, GroupRecord> out = new LinkedHashMap<>(builders.size());
        for (Map.Entry<String, GroupBuilder> e : builders.entrySet()) {
            GroupBuilder b = e.getValue();
            out.put(e.getKey(), new GroupRecord(
                    b.name, b.displayName, b.weight, b.parents, b.nodes, b.meta,
                    b.prefixes, b.suffixes, b.metaEntries));
        }
        return out;
    }

    private Map<String, TrackRecord> loadTracks(Connection conn) throws SQLException {
        Map<String, List<String>> trackGroups = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM " + tbl("tracks") + " ORDER BY name ASC")) {
            while (rs.next()) {
                trackGroups.put(rs.getString("name").trim().toLowerCase(Locale.ROOT), new ArrayList<>());
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT track_name, group_name FROM "
                     + tbl("track_groups") + " ORDER BY track_name ASC, position ASC")) {
            while (rs.next()) {
                List<String> list = trackGroups.get(rs.getString("track_name").trim().toLowerCase(Locale.ROOT));
                if (list != null) {
                    list.add(rs.getString("group_name").trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, TrackRecord> out = new LinkedHashMap<>(trackGroups.size());
        for (Map.Entry<String, List<String>> e : trackGroups.entrySet()) {
            out.put(e.getKey(), new TrackRecord(e.getKey(), e.getValue()));
        }
        return out;
    }

    private Map<UUID, UserRecord> loadUsers(Connection conn) throws SQLException {
        Map<UUID, UserBuilder> builders = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, username, primary_group FROM " + tbl("users"))) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("uuid").trim());
                    builders.put(id, new UserBuilder(id, rs.getString("username"), rs.getString("primary_group")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_uuid, perm_key, value, expiry, contexts FROM "
                     + tbl("user_nodes") + " ORDER BY user_uuid ASC, position ASC")) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("user_uuid").trim());
                    UserBuilder b = builders.get(id);
                    if (b != null) {
                        b.nodes.add(new Node(
                                rs.getString("perm_key"),
                                rs.getBoolean("value"),
                                JsonLocalStorage.deserializeContexts(rs.getString("contexts")),
                                rs.getLong("expiry"),
                                "user",
                                0,
                                true));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_uuid, parent_group, expiry, contexts FROM "
                     + tbl("user_parents") + " ORDER BY user_uuid ASC, position ASC")) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("user_uuid").trim());
                    UserBuilder b = builders.get(id);
                    if (b != null) {
                        b.parents.add(new ParentEntry(
                                rs.getString("parent_group"),
                                rs.getLong("expiry"),
                                JsonLocalStorage.deserializeContexts(rs.getString("contexts"))));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_uuid, kind, priority, value, expiry, contexts FROM "
                     + tbl("user_affixes") + " ORDER BY user_uuid ASC, kind ASC, position ASC")) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("user_uuid").trim());
                    UserBuilder b = builders.get(id);
                    if (b != null) {
                        PriorityMeta pm = new PriorityMeta(
                                rs.getInt("priority"),
                                rs.getString("value"),
                                rs.getLong("expiry"),
                                JsonLocalStorage.deserializeContexts(rs.getString("contexts")));
                        if ("suffix".equals(rs.getString("kind"))) {
                            b.suffixes.add(pm);
                        } else {
                            b.prefixes.add(pm);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_uuid, meta_key, meta_value FROM " + tbl("user_meta"))) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("user_uuid").trim());
                    UserBuilder b = builders.get(id);
                    if (b != null) {
                        b.meta.put(rs.getString("meta_key"), rs.getString("meta_value"));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_uuid, meta_key, meta_value, priority, expiry, contexts FROM "
                     + tbl("user_meta_entries") + " ORDER BY user_uuid ASC, position ASC")) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("user_uuid").trim());
                    UserBuilder b = builders.get(id);
                    if (b != null) {
                        b.metaEntries.add(new MetaEntry(
                                rs.getString("meta_key"),
                                rs.getString("meta_value"),
                                rs.getInt("priority"),
                                rs.getLong("expiry"),
                                JsonLocalStorage.deserializeContexts(rs.getString("contexts"))));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        Map<UUID, UserRecord> out = new LinkedHashMap<>(builders.size());
        for (Map.Entry<UUID, UserBuilder> e : builders.entrySet()) {
            UserBuilder b = e.getValue();
            out.put(e.getKey(), new UserRecord(
                    b.uuid, b.username, b.primaryGroup, b.parents, b.nodes, b.meta,
                    b.prefixes, b.suffixes, b.metaEntries));
        }
        return out;
    }

    @Override
    public void saveUser(UserRecord user) throws IOException {
        if (user == null || user.uuid() == null) {
            throw new IOException("Invalid user for save");
        }
        withWriteTx("saveUser", conn -> saveUserTx(conn, user));
    }

    private void saveUserTx(Connection conn, UserRecord user) throws SQLException {
        String uidStr = user.uuid().toString();
        String primary = (user.primaryGroup() == null || user.primaryGroup().isBlank())
                ? "default" : user.primaryGroup().trim().toLowerCase(Locale.ROOT);
        if (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) {
            String sql = "INSERT INTO " + tbl("users") + " (uuid, username, primary_group) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE username = ?, primary_group = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uidStr);
                ps.setString(2, user.username());
                ps.setString(3, primary);
                ps.setString(4, user.username());
                ps.setString(5, primary);
                ps.executeUpdate();
            }
        } else {
            String sql = "INSERT INTO " + tbl("users") + " (uuid, username, primary_group) VALUES (?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, primary_group = excluded.primary_group";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uidStr);
                ps.setString(2, user.username());
                ps.setString(3, primary);
                ps.executeUpdate();
            }
        }
        deleteUserChildrenTx(conn, uidStr);
        insertNodesTx(conn, "user_nodes", "user_uuid", uidStr, user.nodes());
        insertParentsTx(conn, "user_parents", "user_uuid", uidStr, user.parents());
        insertAffixesTx(conn, "user_affixes", "user_uuid", uidStr, user.prefixes(), user.suffixes());
        insertMetaTx(conn, "user_meta", "user_uuid", uidStr, user.meta());
        insertMetaEntriesTx(conn, "user_meta_entries", "user_uuid", uidStr, user.metaEntries());
    }

    @Override
    public void deleteUser(UUID id) throws IOException {
        if (id == null) {
            return;
        }
        withWriteTx("deleteUser", conn -> {
            String uidStr = id.toString();
            deleteUserChildrenTx(conn, uidStr);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl("users") + " WHERE uuid = ?")) {
                ps.setString(1, uidStr);
                ps.executeUpdate();
            }
        });
    }

    private void deleteUserChildrenTx(Connection conn, String uidStr) throws SQLException {
        for (String t : List.of("user_nodes", "user_parents", "user_affixes", "user_meta", "user_meta_entries")) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl(t) + " WHERE user_uuid = ?")) {
                ps.setString(1, uidStr);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void saveGroup(GroupRecord group) throws IOException {
        if (group == null || group.name().isEmpty()) {
            throw new IOException("Invalid group for save");
        }
        withWriteTx("saveGroup", conn -> saveGroupTx(conn, group));
    }

    private void saveGroupTx(Connection conn, GroupRecord group) throws SQLException {
        String name = group.name().toLowerCase(Locale.ROOT);
        if (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) {
            String sql = "INSERT INTO " + tbl("groups") + " (name, display_name, weight) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE display_name = ?, weight = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, group.displayName());
                ps.setInt(3, group.weight());
                ps.setString(4, group.displayName());
                ps.setInt(5, group.weight());
                ps.executeUpdate();
            }
        } else {
            String sql = "INSERT INTO " + tbl("groups") + " (name, display_name, weight) VALUES (?, ?, ?) "
                    + "ON CONFLICT(name) DO UPDATE SET display_name = excluded.display_name, weight = excluded.weight";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, group.displayName());
                ps.setInt(3, group.weight());
                ps.executeUpdate();
            }
        }
        deleteGroupChildrenTx(conn, name);
        insertNodesTx(conn, "group_nodes", "group_name", name, group.nodes());
        insertParentsTx(conn, "group_parents", "group_name", name, group.parents());
        insertAffixesTx(conn, "group_affixes", "group_name", name, group.prefixes(), group.suffixes());
        insertMetaTx(conn, "group_meta", "group_name", name, group.meta());
        insertMetaEntriesTx(conn, "group_meta_entries", "group_name", name, group.metaEntries());
    }

    @Override
    public void deleteGroup(String name) throws IOException {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        withWriteTx("deleteGroup", conn -> {
            deleteGroupChildrenTx(conn, key);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl("groups") + " WHERE name = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
    }

    private void deleteGroupChildrenTx(Connection conn, String name) throws SQLException {
        for (String t : List.of("group_nodes", "group_parents", "group_affixes", "group_meta", "group_meta_entries")) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl(t) + " WHERE group_name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void saveTrack(TrackRecord track) throws IOException {
        if (track == null || track.name().isEmpty()) {
            throw new IOException("Invalid track for save");
        }
        withWriteTx("saveTrack", conn -> saveTrackTx(conn, track));
    }

    private void saveTrackTx(Connection conn, TrackRecord track) throws SQLException {
        String name = track.name().toLowerCase(Locale.ROOT);
        String trackSql = (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB)
                ? "INSERT IGNORE INTO " + tbl("tracks") + " (name) VALUES (?)"
                : "INSERT INTO " + tbl("tracks") + " (name) VALUES (?) ON CONFLICT(name) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(trackSql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl("track_groups") + " WHERE track_name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        if (!track.groups().isEmpty()) {
            String ins = "INSERT INTO " + tbl("track_groups") + " (track_name, position, group_name) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                int i = 0;
                for (String g : track.groups()) {
                    ps.setString(1, name);
                    ps.setInt(2, i++);
                    ps.setString(3, g.toLowerCase(Locale.ROOT));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    @Override
    public void deleteTrack(String name) throws IOException {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        withWriteTx("deleteTrack", conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl("track_groups") + " WHERE track_name = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tbl("tracks") + " WHERE name = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void appendLog(LogEntry entry) throws IOException {
        if (entry == null) {
            return;
        }
        withWriteTx("appendLog", conn -> appendLogTx(conn, entry));
    }

    private void appendLogTx(Connection conn, LogEntry entry) throws SQLException {
        String sql = "INSERT INTO " + tbl("logs") + " (timestamp, actor, target_type, target, action) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entry.timestamp());
            ps.setString(2, entry.actor());
            ps.setString(3, entry.targetType());
            ps.setString(4, entry.target());
            ps.setString(5, entry.action());
            ps.executeUpdate();
        }
    }

    @Override
    public List<LogEntry> getLogs(int limit) throws IOException {
        try (Connection conn = dataSource.getConnection()) {
            return loadLogs(conn, limit);
        } catch (SQLException error) {
            throw new IOException("SQL getLogs failed (" + target + "): " + error.getMessage(), error);
        }
    }

    private List<LogEntry> loadLogs(Connection conn, int limit) throws SQLException {
        int lim = limit > 0 ? limit : JsonLocalStorage.MAX_LOG_ENTRIES;
        List<LogEntry> out = new ArrayList<>();
        String sql = "SELECT timestamp, actor, target_type, target, action FROM " + tbl("logs")
                + " ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogEntry(
                            rs.getLong("timestamp"),
                            rs.getString("actor"),
                            rs.getString("target_type"),
                            rs.getString("target"),
                            rs.getString("action")));
                }
            }
        }
        Collections.reverse(out);
        return out;
    }

    private void insertNodesTx(Connection conn, String table, String ownerCol, String ownerVal, List<Node> nodes)
            throws SQLException {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tbl(table)
                + " (" + ownerCol + ", position, perm_key, value, expiry, contexts) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 0;
            for (Node n : nodes) {
                ps.setString(1, ownerVal);
                ps.setInt(2, i++);
                ps.setString(3, n.key());
                ps.setBoolean(4, n.value());
                ps.setLong(5, n.expiry());
                ps.setString(6, JsonLocalStorage.serializeContexts(n.contexts()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertParentsTx(
            Connection conn, String table, String ownerCol, String ownerVal, List<ParentEntry> parents)
            throws SQLException {
        if (parents == null || parents.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tbl(table)
                + " (" + ownerCol + ", position, parent_group, expiry, contexts) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 0;
            for (ParentEntry p : parents) {
                ps.setString(1, ownerVal);
                ps.setInt(2, i++);
                ps.setString(3, p.group().toLowerCase(Locale.ROOT));
                ps.setLong(4, p.expiry());
                ps.setString(5, JsonLocalStorage.serializeContexts(p.contexts()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertAffixesTx(
            Connection conn,
            String table,
            String ownerCol,
            String ownerVal,
            List<PriorityMeta> prefixes,
            List<PriorityMeta> suffixes) throws SQLException {
        boolean hasP = prefixes != null && !prefixes.isEmpty();
        boolean hasS = suffixes != null && !suffixes.isEmpty();
        if (!hasP && !hasS) {
            return;
        }
        String sql = "INSERT INTO " + tbl(table)
                + " (" + ownerCol + ", kind, position, priority, value, expiry, contexts) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (hasP) {
                int i = 0;
                for (PriorityMeta pm : prefixes) {
                    ps.setString(1, ownerVal);
                    ps.setString(2, "prefix");
                    ps.setInt(3, i++);
                    ps.setInt(4, pm.priority());
                    ps.setString(5, pm.value());
                    ps.setLong(6, pm.expiry());
                    ps.setString(7, JsonLocalStorage.serializeContexts(pm.contexts()));
                    ps.addBatch();
                }
            }
            if (hasS) {
                int i = 0;
                for (PriorityMeta pm : suffixes) {
                    ps.setString(1, ownerVal);
                    ps.setString(2, "suffix");
                    ps.setInt(3, i++);
                    ps.setInt(4, pm.priority());
                    ps.setString(5, pm.value());
                    ps.setLong(6, pm.expiry());
                    ps.setString(7, JsonLocalStorage.serializeContexts(pm.contexts()));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void insertMetaTx(
            Connection conn, String table, String ownerCol, String ownerVal, Map<String, String> meta)
            throws SQLException {
        if (meta == null || meta.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tbl(table) + " (" + ownerCol + ", meta_key, meta_value) VALUES (?, ?, ?)";
        TreeMap<String, String> sorted = new TreeMap<>(meta);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                ps.setString(1, ownerVal);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertMetaEntriesTx(
            Connection conn, String table, String ownerCol, String ownerVal, List<MetaEntry> entries)
            throws SQLException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tbl(table)
                + " (" + ownerCol + ", position, meta_key, meta_value, priority, expiry, contexts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 0;
            for (MetaEntry me : entries) {
                ps.setString(1, ownerVal);
                ps.setInt(2, i++);
                ps.setString(3, me.key());
                ps.setString(4, me.value());
                ps.setInt(5, me.priority());
                ps.setLong(6, me.expiry());
                ps.setString(7, JsonLocalStorage.serializeContexts(me.contexts()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws SQLException;
    }

    private void withWriteTx(String op, SqlWork work) throws IOException {
        try {
            if (dialect == Dialect.SQLITE) {
                synchronized (sqliteWriteLock) {
                    try (Connection conn = dataSource.getConnection()) {
                        runInTransaction(conn, work);
                    }
                }
            } else {
                try (Connection conn = dataSource.getConnection()) {
                    runInTransaction(conn, work);
                }
            }
        } catch (SQLException error) {
            throw new IOException("SQL " + op + " failed (" + target + "): " + error.getMessage(), error);
        }
    }

    private static void runInTransaction(Connection conn, SqlWork work) throws SQLException {
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            work.run(conn);
            conn.commit();
        } catch (SQLException | RuntimeException error) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            throw error;
        } finally {
            try {
                conn.setAutoCommit(prevAuto);
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private static final class GroupBuilder {
        final String name;
        final String displayName;
        final int weight;
        final List<ParentEntry> parents = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();
        final Map<String, String> meta = new LinkedHashMap<>();
        final List<PriorityMeta> prefixes = new ArrayList<>();
        final List<PriorityMeta> suffixes = new ArrayList<>();
        final List<MetaEntry> metaEntries = new ArrayList<>();

        GroupBuilder(String name, String displayName, int weight) {
            this.name = name;
            this.displayName = displayName == null ? "" : displayName;
            this.weight = weight;
        }
    }

    private static final class UserBuilder {
        final UUID uuid;
        final String username;
        final String primaryGroup;
        final List<ParentEntry> parents = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();
        final Map<String, String> meta = new LinkedHashMap<>();
        final List<PriorityMeta> prefixes = new ArrayList<>();
        final List<PriorityMeta> suffixes = new ArrayList<>();
        final List<MetaEntry> metaEntries = new ArrayList<>();

        UserBuilder(UUID uuid, String username, String primaryGroup) {
            this.uuid = uuid;
            this.username = username == null ? "" : username;
            this.primaryGroup = (primaryGroup == null || primaryGroup.isBlank()) ? "default" : primaryGroup;
        }
    }
}
