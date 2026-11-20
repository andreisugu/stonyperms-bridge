package dev.stonyperms.bridge.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
