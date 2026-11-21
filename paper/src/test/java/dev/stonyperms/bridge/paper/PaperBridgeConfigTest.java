package dev.stonyperms.bridge.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperBridgeConfigTest {

    @TempDir
    Path dataFolder;

    @Test
    void readsDefaultsAndInlineToken() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("api.token", "inline-token-0123456789");

        PaperBridgeConfig parsed = PaperBridgeConfig.from(config, dataFolder, null);

        assertEquals("http://127.0.0.1:8737", parsed.apiUrl().toString());
        assertEquals(30, parsed.refreshSeconds());
        assertEquals(300, parsed.staleGraceSeconds());
        assertTrue(parsed.failClosed());
        assertTrue(parsed.useStaleOnError());
        assertTrue(parsed.applyToOps());
        assertFalse(parsed.writeEnabled());
        assertTrue(parsed.sseEnabled());
        assertEquals(1000L, parsed.sseReconnectMinMs());
        assertEquals(30_000L, parsed.sseReconnectMaxMs());
        assertTrue(parsed.integrationsPlaceholderApi());
        assertTrue(parsed.integrationsVault());
        assertTrue(parsed.chatEnabled());
        assertEquals("{prefix}{name}{suffix}&r&7: &f{message}", parsed.chatFormat());
        assertTrue(parsed.tablistEnabled());
        assertFalse(parsed.isRemotePlainHttp());
    }

    @Test
    void envTokenWinsAndRemoteHttpIsFlagged() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("api.url", "http://perms.internal:8737");
        config.set("api.token", "inline-token-0123456789");

        PaperBridgeConfig parsed = PaperBridgeConfig.from(config, dataFolder, "env-token-0123456789");

        assertEquals("env-token-0123456789", parsed.token());
        assertTrue(parsed.isRemotePlainHttp());
    }

    @Test
    void readsTokenFileRelativeToDataFolder() throws Exception {
        Files.writeString(dataFolder.resolve("token"), "file-token-0123456789\n");
        YamlConfiguration config = new YamlConfiguration();
        config.set("api.token-file", "token");

        PaperBridgeConfig parsed = PaperBridgeConfig.from(config, dataFolder, null);

        assertEquals("file-token-0123456789", parsed.token());
    }

    @Test
    void rejectsUrlWithoutHost() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("api.url", "http:/api");
        config.set("api.token", "inline-token-0123456789");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(config, dataFolder, null));
    }

    @Test
    void rejectsMissingShortAndInvalidConfig() {
        YamlConfiguration missing = new YamlConfiguration();
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(missing, dataFolder, null));

        YamlConfiguration shortToken = new YamlConfiguration();
        shortToken.set("api.token", "short");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(shortToken, dataFolder, null));

        YamlConfiguration badScheme = new YamlConfiguration();
        badScheme.set("api.url", "ftp://example.invalid");
        badScheme.set("api.token", "inline-token-0123456789");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(badScheme, dataFolder, null));
    }

    @Test
    void writeEnabledRequiresValidWriteTokenAndParsesSseAndIntegrations() throws Exception {
        YamlConfiguration missingWrite = new YamlConfiguration();
        missingWrite.set("api.token", "inline-token-0123456789");
        missingWrite.set("write.enabled", true);
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(missingWrite, dataFolder, null, null));

        YamlConfiguration shortWrite = new YamlConfiguration();
        shortWrite.set("api.token", "inline-token-0123456789");
        shortWrite.set("write.enabled", true);
        shortWrite.set("api.write-token", "too-short");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(shortWrite, dataFolder, null, null));

        Files.writeString(dataFolder.resolve("write-token"), "file-write-token-0123456789\n");
        YamlConfiguration validFileWrite = new YamlConfiguration();
        validFileWrite.set("api.token", "inline-token-0123456789");
        validFileWrite.set("api.write-token-file", "write-token");
        validFileWrite.set("server-name", "lobby");
        validFileWrite.set("write.enabled", true);
        validFileWrite.set("sse.enabled", false);
        validFileWrite.set("sse.reconnect-min-ms", 500);
        validFileWrite.set("sse.reconnect-max-ms", 5000);
        validFileWrite.set("integrations.placeholderapi", false);
        validFileWrite.set("integrations.vault", false);

        PaperBridgeConfig parsed = PaperBridgeConfig.from(validFileWrite, dataFolder, null, null);
        assertTrue(parsed.writeEnabled());
        assertEquals("file-write-token-0123456789", parsed.writeToken());
        assertEquals("paper:lobby", parsed.effectiveDefaultActor());
        assertFalse(parsed.sseEnabled());
        assertEquals(500L, parsed.sseReconnectMinMs());
        assertEquals(5000L, parsed.sseReconnectMaxMs());
        assertFalse(parsed.integrationsPlaceholderApi());
        assertFalse(parsed.integrationsVault());

        PaperBridgeConfig envOverrides = PaperBridgeConfig.from(
                validFileWrite, dataFolder, null, "env-write-token-0123456789");
        assertEquals("env-write-token-0123456789", envOverrides.writeToken());
    }

    @Test
    void parsesStandaloneModeWithoutApiOrTokens() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("mode", "standalone");
        config.set("standalone.storage-dir", "local-data");
        config.set("standalone.primary-group-calculation", "parents-by-weight");
        config.set("standalone.stack.prefix.enabled", true);
        config.set("standalone.stack.prefix.mid-spacer", "|");

        PaperBridgeConfig parsed = PaperBridgeConfig.from(config, dataFolder, null, null);

        assertTrue(parsed.isStandalone());
        assertEquals(PaperBridgeConfig.Mode.STANDALONE, parsed.mode());
        assertNull(parsed.apiUrl());
        assertTrue(parsed.writeEnabled());
        assertFalse(parsed.sseEnabled());
        assertFalse(parsed.isRemotePlainHttp());
        assertEquals(dataFolder.resolve("local-data"), parsed.standalone().storageDir());
        assertEquals("parents-by-weight", parsed.standalone().primaryGroupCalculation());
        assertTrue(parsed.standalone().stackConfig().prefixEnabled());
        assertEquals("|", parsed.standalone().stackConfig().prefixMidSpacer());
    }

    @Test
    void rejectsInvalidMode() {
        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("mode", "invalid");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(invalid, dataFolder, null, null));
    }

    @Test
    void parsesStandaloneDatabaseSettingsAndRejectsInvalidDbConfig() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("mode", "standalone");
        config.set("standalone.storage-type", "postgres");
        config.set("standalone.database.host", "pg.local");
        config.set("standalone.database.port", 5432);
        config.set("standalone.database.database", "permsdb");
        config.set("standalone.database.username", "sp");
        config.set("standalone.database.table-prefix", "sp_paper_");
        config.set("standalone.database.ssl-mode", "require");

        PaperBridgeConfig parsed = PaperBridgeConfig.from(config, dataFolder, null, null);
        assertEquals("postgresql", parsed.standalone().storageType());
        assertEquals("pg.local", parsed.standalone().database().host());
        assertEquals(5432, parsed.standalone().database().port());
        assertEquals("sp_paper_", parsed.standalone().database().tablePrefix());

        YamlConfiguration badType = new YamlConfiguration();
        badType.set("mode", "standalone");
        badType.set("standalone.storage-type", "bad_engine");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(badType, dataFolder, null, null));

        YamlConfiguration badPrefix = new YamlConfiguration();
        badPrefix.set("mode", "standalone");
        badPrefix.set("standalone.storage-type", "sqlite");
        badPrefix.set("standalone.database.table-prefix", "bad-prefix!");
        assertThrows(PaperBridgeConfig.PaperConfigException.class,
                () -> PaperBridgeConfig.from(badPrefix, dataFolder, null, null));
    }
}
