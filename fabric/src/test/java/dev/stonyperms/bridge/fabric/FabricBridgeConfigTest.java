package dev.stonyperms.bridge.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricBridgeConfigTest {

    @TempDir
    Path configDir;

    @Test
    void createsDefaultConfigAndReadsTokenFile() throws Exception {
        Files.writeString(configDir.resolve("token"), "file-token-0123456789\n");

        FabricBridgeConfig config = FabricBridgeConfig.load(configDir, null);

        assertTrue(Files.exists(configDir.resolve(FabricBridgeConfig.FILE_NAME)));
        assertEquals("file-token-0123456789", config.token());
        assertEquals(30, config.refreshSeconds());
        assertTrue(config.failClosed());
        assertTrue(config.useStaleOnError());
        assertTrue(config.applyToOps());
        assertFalse(config.writeEnabled());
        assertTrue(config.sseEnabled());
        assertTrue(config.displayEnabled());
        assertTrue(config.chatEnabled());
        assertEquals("{prefix}{name}{suffix}&r&7: &f{message}", config.chatFormat());
        assertFalse(config.isRemotePlainHttp());
    }

    @Test
    void backwardCompatibleV01JsonDefaultsSafely() throws Exception {
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {
                  "api": {"url": "http://perms.internal:8737", "token": "inline-token-0123456789"},
                  "serverName": "survival",
                  "refreshSeconds": 10,
                  "staleGraceSeconds": 60,
                  "failClosed": false,
                  "useStaleOnError": false,
                  "debug": true
                }
                """);

        FabricBridgeConfig config = FabricBridgeConfig.load(configDir, "env-token-0123456789");

        assertEquals("env-token-0123456789", config.token());
        assertEquals("survival", config.serverName());
        assertEquals(10, config.refreshSeconds());
        assertEquals(60, config.staleGraceSeconds());
        assertFalse(config.failClosed());
        assertFalse(config.useStaleOnError());
        assertTrue(config.applyToOps());
        assertFalse(config.writeEnabled());
        assertTrue(config.sseEnabled());
        assertTrue(config.debug());
        assertTrue(config.isRemotePlainHttp());
    }

    @Test
    void parsesWriteSseAndApplyToOpsAndRejectsMissingWriteToken() throws Exception {
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {
                  "api": {"url": "http://127.0.0.1:8737", "token": "inline-token-0123456789"},
                  "write": {"enabled": true}
                }
                """);
        assertThrows(FabricBridgeConfig.ConfigException.class,
                () -> FabricBridgeConfig.load(configDir, null, null));

        Files.writeString(configDir.resolve("write-token"), "file-write-token-0123456789\n");
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {
                  "api": {
                    "url": "http://127.0.0.1:8737",
                    "token": "inline-token-0123456789",
                    "writeTokenFile": "write-token"
                  },
                  "serverName": "clankers",
                  "applyToOps": false,
                  "write": {"enabled": true, "defaultActor": ""},
                  "sse": {"enabled": false, "reconnectMinMs": 500, "reconnectMaxMs": 5000}
                }
                """);

        FabricBridgeConfig config = FabricBridgeConfig.load(configDir, null, null);
        assertFalse(config.applyToOps());
        assertTrue(config.writeEnabled());
        assertEquals("file-write-token-0123456789", config.writeToken());
        assertEquals("fabric:clankers", config.effectiveDefaultActor());
        assertFalse(config.sseEnabled());
        assertEquals(500L, config.sseReconnectMinMs());
        assertEquals(5000L, config.sseReconnectMaxMs());

        FabricBridgeConfig envOverride = FabricBridgeConfig.load(configDir, null, "env-write-token-0123456789");
        assertEquals("env-write-token-0123456789", envOverride.writeToken());
    }

    @Test
    void rejectsUrlWithoutHost() throws Exception {
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {"api": {"url": "http:/api", "token": "inline-token-0123456789"}}
                """);
        assertThrows(FabricBridgeConfig.ConfigException.class,
                () -> FabricBridgeConfig.load(configDir, null));
    }

    @Test
    void missingTokenIsRejected() {
        assertThrows(FabricBridgeConfig.ConfigException.class,
                () -> FabricBridgeConfig.load(configDir, null));
    }

    @Test
    void parsesStandaloneModeWithoutApiOrTokensAndSupportsPeek() throws Exception {
        assertFalse(FabricBridgeConfig.peekIsStandalone(configDir));
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {
                  "mode": "standalone",
                  "standalone": {
                    "storageDir": "local-sp-data",
                    "primaryGroupCalculation": "all-parents-by-weight",
                    "stack": {
                      "prefixEnabled": true,
                      "prefixMidSpacer": "|"
                    }
                  }
                }
                """);
        assertTrue(FabricBridgeConfig.peekIsStandalone(configDir));
        FabricBridgeConfig config = FabricBridgeConfig.load(configDir, null, null);
        assertTrue(config.isStandalone());
        assertEquals(FabricBridgeConfig.Mode.STANDALONE, config.mode());
        assertNull(config.apiUrl());
        assertTrue(config.writeEnabled());
        assertFalse(config.sseEnabled());
        assertFalse(config.isRemotePlainHttp());
        assertEquals(configDir.resolve("local-sp-data"), config.standalone().storageDir());
        assertEquals("all-parents-by-weight", config.standalone().primaryGroupCalculation());
        assertTrue(config.standalone().stackConfig().prefixEnabled());
        assertEquals("|", config.standalone().stackConfig().prefixMidSpacer());
    }

    @Test
    void rejectsInvalidMode() throws Exception {
        Files.writeString(configDir.resolve(FabricBridgeConfig.FILE_NAME), """
                {"mode": "bogus"}
                """);
        assertThrows(FabricBridgeConfig.ConfigException.class,
                () -> FabricBridgeConfig.load(configDir, null));
    }
}
