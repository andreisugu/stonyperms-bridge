package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLocalStorageTest {

    @Test
    void sqliteFullCrudOrderingRenameExpiryAndPersistence(@TempDir Path tempDir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StandaloneConfig.normalizeStorageType("oracle"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.validateTablePrefix("bad-prefix!"));

        DatabaseConfig dbCfg = new DatabaseConfig(
                tempDir.resolve("custom/sp.db").toString(), "127.0.0.1", 0, "stonyperms", "stonyperms", "", "",
                "sp_custom_", "prefer", 10, 5, 1800L, 5);
        StandaloneConfig cfg = new StandaloneConfig(
                "sqlite", tempDir, dbCfg, "default", "stored",
                StandaloneConfig.StackConfig.defaults(), true, StandaloneConfig.DEFAULT_WEB_URL, 60L);

        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        try (LocalPermissionManager mgr = new LocalPermissionManager(cfg, null)) {
            assertEquals("sqlite", mgr.storage().type());
            assertTrue(mgr.storage().describeTarget().startsWith("sqlite:"));
            assertNotNull(mgr.fetchGroup("default"));
            assertNotNull(mgr.fetchGroup("admin"));
            assertEquals(List.of("default", "admin"), mgr.fetchTrack("ranks").groups());

            mgr.createGroup("vip", 50, "&aVIP", "tester");
            mgr.setGroupPermission("vip", "essentials.*", true, 0L, 0L, Map.of(), "replace", "tester");
            mgr.setGroupPermission("vip", "essentials.god", false, 0L, 2_000_000_000L,
                    Map.of("server", "smp"), "replace", "tester");
            mgr.setGroupMeta("vip", "Shared", "CasePreserved", 0L, 0L, Map.of(), "replace", "tester");
            mgr.setGroupMeta("vip", "homes", "5", 0L, 2_000_000_000L, Map.of("server", "creative"), "replace", "tester");
            mgr.setGroupAffix("vip", true, 50, "&a[VIP] ", 0L, 0L, Map.of(), "set", "replace", "tester");

            mgr.resolveOrCreateUser(id, "Alex", "vip", true, "tester");
            mgr.setPermission(id.toString(), "user.first", true, 0L, 0L, Map.of(), "replace", "tester");
            mgr.setPermission(id.toString(), "user.expired", true, 0L, 1000L, Map.of(), "replace", "tester");

            mgr.renameGroup("vip", "donor", "tester");
            mgr.renameTrack("ranks", "tiers", "tester");
            mgr.cleanupExpired(2000L);

            for (int i = 0; i < 1005; i++) {
                mgr.storage().appendLog(new LogEntry(1700000000L + i, "tester", "user", "Alex", "sql-act-" + i));
            }
        }

        try (LocalPermissionManager reopened = new LocalPermissionManager(cfg, null)) {
            GroupRecord donor = reopened.fetchGroup("donor");
            assertEquals(50, donor.weight());
            assertEquals(2, donor.nodes().size());
            assertEquals("essentials.*", donor.nodes().get(0).key());
            assertEquals("essentials.god", donor.nodes().get(1).key());
            assertEquals("smp", donor.nodes().get(1).contexts().get("server"));
            assertEquals("CasePreserved", donor.meta().get("Shared"));

            UserRecord u = reopened.fetchUser(id.toString());
            assertEquals("donor", u.primaryGroup());
            assertTrue(u.nodes().stream().anyMatch(n -> n.key().equals("user.first")));
            assertTrue(u.nodes().stream().noneMatch(n -> n.key().equals("user.expired")));
            assertEquals(JsonLocalStorage.MAX_LOG_ENTRIES, reopened.queryLogs(2000, "", "", "").size());
            assertEquals(List.of("default", "admin"), reopened.fetchTrack("tiers").groups());
        }
    }

    @Test
    void dialectUrlDdlAndPasswordRedaction(@TempDir Path tempDir) throws Exception {
        Path pwFile = tempDir.resolve("db.pw");
        Files.writeString(pwFile, "file-secret\n");
        DatabaseConfig fromFile = new DatabaseConfig(
                "", "db.internal", 0, "perms", "spuser", "", pwFile.toString(),
                "sp_", "verify-ca", 12, 4, 900L, 5);
        assertEquals("env-pw", fromFile.resolvePassword("env-pw", "global-pw"));
        assertEquals("global-pw", fromFile.resolvePassword("", "global-pw"));
        assertEquals("file-secret", fromFile.resolvePassword("", ""));

        DatabaseConfig inline = new DatabaseConfig(
                "", "db.internal", 0, "perms", "spuser", "super-secret-pw", "",
                "sp_", "require", 12, 4, 900L, 5);
        SqlLocalStorage.TargetSpec mySpec = SqlLocalStorage.buildTargetSpec(
                SqlLocalStorage.Dialect.MARIADB, tempDir, inline);
        assertTrue(mySpec.jdbcUrl().startsWith("jdbc:mariadb://db.internal:3306/perms"));
        assertTrue(mySpec.jdbcUrl().contains("sslMode=trust"));
        assertFalse(mySpec.redactedTarget().contains("super-secret-pw"));

        SqlLocalStorage.TargetSpec pgSpec = SqlLocalStorage.buildTargetSpec(
                SqlLocalStorage.Dialect.POSTGRESQL, tempDir, inline);
        assertTrue(pgSpec.jdbcUrl().startsWith("jdbc:postgresql://db.internal:5432/perms"));
        assertTrue(pgSpec.jdbcUrl().contains("sslmode=require"));
        assertFalse(pgSpec.redactedTarget().contains("super-secret-pw"));

        List<String> pgDdl = SqlLocalStorage.ddlStatements(SqlLocalStorage.Dialect.POSTGRESQL, "sp_");
        assertTrue(pgDdl.stream().anyMatch(s -> s.contains("BIGSERIAL PRIMARY KEY")));
        List<String> myDdl = SqlLocalStorage.ddlStatements(SqlLocalStorage.Dialect.MARIADB, "sp_");
        assertTrue(myDdl.stream().anyMatch(s -> s.contains("COLLATE utf8mb4_bin")));
    }

    @Test
    void liveExternalSqlIfConfigured(@TempDir Path tempDir) throws Exception {
        String pgHost = System.getenv("STONYPERMS_TEST_POSTGRES_HOST");
        if (pgHost != null && !pgHost.isBlank()) {
            int port = parseIntOr(System.getenv("STONYPERMS_TEST_POSTGRES_PORT"), 5432);
            runLiveSqlCheck("postgresql", tempDir, new DatabaseConfig(
                    "", pgHost, port,
                    orDefault(System.getenv("STONYPERMS_TEST_POSTGRES_DB"), "stonyperms"),
                    orDefault(System.getenv("STONYPERMS_TEST_POSTGRES_USER"), "stonyperms"),
                    orDefault(System.getenv("STONYPERMS_TEST_POSTGRES_PASS"), ""),
                    "java_pg_", "disable", 5, 2, 600L, 5));
        }
        String myHost = System.getenv("STONYPERMS_TEST_MYSQL_HOST");
        if (myHost != null && !myHost.isBlank()) {
            int port = parseIntOr(System.getenv("STONYPERMS_TEST_MYSQL_PORT"), 3306);
            runLiveSqlCheck("mariadb", tempDir, new DatabaseConfig(
                    "", myHost, port,
                    orDefault(System.getenv("STONYPERMS_TEST_MYSQL_DB"), "stonyperms"),
                    orDefault(System.getenv("STONYPERMS_TEST_MYSQL_USER"), "stonyperms"),
                    orDefault(System.getenv("STONYPERMS_TEST_MYSQL_PASS"), ""),
                    "java_my_", "disable", 5, 2, 600L, 5));
        }
    }

    private static void runLiveSqlCheck(String type, Path dir, DatabaseConfig dbCfg) throws Exception {
        StandaloneConfig cfg = new StandaloneConfig(
                type, dir, dbCfg, "default", "stored",
                StandaloneConfig.StackConfig.defaults(), true, StandaloneConfig.DEFAULT_WEB_URL, 60L);
        UUID id = UUID.randomUUID();
        try (LocalPermissionManager mgr = new LocalPermissionManager(cfg, null)) {
            mgr.createGroup("vip_live", 30, "&aVIP", "live-test");
            mgr.setGroupMeta("vip_live", "Shared", "JavaExact", 0L, 0L, Map.of(), "replace", "live-test");
            mgr.resolveOrCreateUser(id, "JavaLive", "vip_live", true, "live-test");
            mgr.setPermission(id.toString(), "live.perm", true, 0L, 0L, Map.of("server", "lobby"), "replace", "live-test");
        }
        try (LocalPermissionManager reopened = new LocalPermissionManager(cfg, null)) {
            assertEquals("JavaExact", reopened.fetchGroup("vip_live").meta().get("Shared"));
            assertEquals("true", reopened.check(id, "live.perm", Map.of("server", "lobby")).value());
            reopened.deleteUser(id.toString(), "live-test");
            reopened.deleteGroup("vip_live", "live-test");
        }
    }

    private static int parseIntOr(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }
}
