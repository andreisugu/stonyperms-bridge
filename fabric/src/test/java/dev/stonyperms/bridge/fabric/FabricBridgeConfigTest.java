package dev.stonyperms.bridge.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(config.isRemotePlainHttp());
    }

    @Test
    void envTokenWinsAndCustomValuesAreParsed() throws Exception {
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
        assertTrue(config.debug());
        assertTrue(config.isRemotePlainHttp());
    }

    @Test
    void missingTokenIsRejected() {
        assertThrows(FabricBridgeConfig.ConfigException.class,
                () -> FabricBridgeConfig.load(configDir, null));
    }
}
