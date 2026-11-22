package dev.stonyperms.bridge.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stonyperms.bridge.core.DisplayFormat;
import dev.stonyperms.bridge.core.local.DatabaseConfig;
import dev.stonyperms.bridge.core.local.StandaloneConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public record FabricBridgeConfig(
        Mode mode,
        URI apiUrl,
        String token,
        String writeToken,
        String serverName,
        int refreshSeconds,
        int staleGraceSeconds,
        boolean failClosed,
        boolean useStaleOnError,
        boolean applyToOps,
        boolean writeEnabled,
        String defaultActor,
        boolean sseEnabled,
        long sseReconnectMinMs,
        long sseReconnectMaxMs,
        boolean displayEnabled,
        boolean chatEnabled,
        String chatFormat,
        boolean debug,
        int connectTimeoutMs,
        int requestTimeoutMs,
        StandaloneConfig standalone) {

    public enum Mode {
        BRIDGE,
        STANDALONE
    }

    public static final int MIN_TOKEN_LENGTH = 16;
    public static final String FILE_NAME = "stonyperms-bridge.json";
    private static final Logger LOGGER = LoggerFactory.getLogger("stonyperms-bridge");

    private static final String DEFAULT_CONFIG = """
            {
              "configVersion": 2,
              "mode": "bridge",
              "api": {
                "url": "http://127.0.0.1:8737",
                "token": "",
                "tokenFile": "token",
                "writeToken": "",
                "writeTokenFile": "",
                "connectTimeoutMs": 1500,
                "requestTimeoutMs": 2500
              },
              "standalone": {
                "storageType": "json",
                "storageDir": "stonyperms/data",
                "database": {
                  "file": "",
                  "host": "127.0.0.1",
                  "port": 0,
                  "database": "stonyperms",
                  "username": "stonyperms",
                  "password": "",
                  "passwordFile": "",
                  "tablePrefix": "stonyperms_",
                  "sslMode": "prefer",
                  "maxPoolSize": 10,
                  "minIdle": 5,
                  "maxLifetimeSeconds": 1800,
                  "connectTimeoutSeconds": 5
                },
                "defaultGroup": "default",
                "primaryGroupCalculation": "stored",
                "auditLog": true,
                "webBaseUrl": "https://andreisugu.github.io/gate-stonyperms",
                "expiryCleanupSeconds": 60,
                "stack": {
                  "prefixEnabled": false,
                  "prefixStartSpacer": "",
                  "prefixMidSpacer": " ",
                  "prefixEndSpacer": "",
                  "suffixEnabled": false,
                  "suffixStartSpacer": "",
                  "suffixMidSpacer": " ",
                  "suffixEndSpacer": "",
                  "deduplicate": true
                }
              },
              "serverName": "",
              "refreshSeconds": 30,
              "staleGraceSeconds": 300,
              "failClosed": true,
              "useStaleOnError": true,
              "applyToOps": true,
              "write": {
                "enabled": false,
                "defaultActor": ""
              },
              "sse": {
                "enabled": true,
                "reconnectMinMs": 1000,
                "reconnectMaxMs": 30000
              },
              "display": {
                "enabled": true
              },
              "chat": {
                "enabled": true,
                "format": "{prefix}{name}{suffix}&r&7: &f{message}"
              },
              "debug": false
            }
            """;

    /** Backward-compatible 10-arg constructor. */
    public FabricBridgeConfig(
            URI apiUrl,
            String token,
            String serverName,
            int refreshSeconds,
            int staleGraceSeconds,
            boolean failClosed,
            boolean useStaleOnError,
            boolean debug,
            int connectTimeoutMs,
            int requestTimeoutMs) {
        this(Mode.BRIDGE, apiUrl, token, "", serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, true, false, "", true, 1000L, 30_000L,
                true, true, DisplayFormat.DEFAULT_CHAT_FORMAT, debug, connectTimeoutMs, requestTimeoutMs, null);
    }

    /** Backward-compatible 17-arg constructor. */
    public FabricBridgeConfig(
            URI apiUrl,
            String token,
            String writeToken,
            String serverName,
            int refreshSeconds,
            int staleGraceSeconds,
            boolean failClosed,
            boolean useStaleOnError,
            boolean applyToOps,
            boolean writeEnabled,
            String defaultActor,
            boolean sseEnabled,
            long sseReconnectMinMs,
            long sseReconnectMaxMs,
            boolean debug,
            int connectTimeoutMs,
            int requestTimeoutMs) {
        this(Mode.BRIDGE, apiUrl, token, writeToken, serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, applyToOps, writeEnabled, defaultActor, sseEnabled, sseReconnectMinMs,
                sseReconnectMaxMs, true, true, DisplayFormat.DEFAULT_CHAT_FORMAT, debug,
                connectTimeoutMs, requestTimeoutMs, null);
    }

    /** Backward-compatible 20-arg constructor. */
    public FabricBridgeConfig(
            URI apiUrl,
            String token,
            String writeToken,
            String serverName,
            int refreshSeconds,
            int staleGraceSeconds,
            boolean failClosed,
            boolean useStaleOnError,
            boolean applyToOps,
            boolean writeEnabled,
            String defaultActor,
            boolean sseEnabled,
            long sseReconnectMinMs,
            long sseReconnectMaxMs,
            boolean displayEnabled,
            boolean chatEnabled,
            String chatFormat,
            boolean debug,
            int connectTimeoutMs,
            int requestTimeoutMs) {
        this(Mode.BRIDGE, apiUrl, token, writeToken, serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, applyToOps, writeEnabled, defaultActor, sseEnabled, sseReconnectMinMs,
                sseReconnectMaxMs, displayEnabled, chatEnabled, chatFormat, debug,
                connectTimeoutMs, requestTimeoutMs, null);
    }

    public boolean isStandalone() {
        return mode == Mode.STANDALONE;
    }

    /** Non-throwing check of existing config used during early Brigadier command registration. */
    public static boolean peekIsStandalone(Path configDir) {
        if (configDir == null) {
            return false;
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String rawMode = string(root, "mode", "bridge").trim().toLowerCase(Locale.ROOT);
            return "standalone".equals(rawMode);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static JsonObject migrateConfig(Path file, JsonObject root) {
        int version = 0;
        try {
            if (root.has("configVersion")) {
                version = root.get("configVersion").getAsInt();
            }
        } catch (RuntimeException ignored) {
            version = 0;
        }
        if (version >= 2) {
            return root;
        }
        try {
            String mode = root.has("mode") && root.get("mode").isJsonPrimitive()
                    ? root.get("mode").getAsString() : "bridge";
            boolean standalone = "standalone".equalsIgnoreCase(mode);
            JsonObject existingWrite = object(root, "write");
            boolean hadWriteEnabled = existingWrite.has("enabled");
            Path backup = file.resolveSibling(file.getFileName() + ".bak-v" + version + "-" + System.currentTimeMillis());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            JsonObject defaults = JsonParser.parseString(DEFAULT_CONFIG).getAsJsonObject();
            deepMergeDefaults(root, defaults);
            if (standalone && !hadWriteEnabled) {
                root.getAsJsonObject("write").addProperty("enabled", true);
            }
            root.addProperty("configVersion", 2);
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(file, pretty, StandardCharsets.UTF_8);
            LOGGER.info("Migrated {} v{} -> v2 (backup: {})", FILE_NAME, version, backup.getFileName());
        } catch (Exception error) {
            LOGGER.warn("Could not migrate {}: {}", FILE_NAME, error.getMessage());
        }
        return root;
    }

    private static void deepMergeDefaults(JsonObject target, JsonObject defaults) {
        for (var entry : defaults.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (!target.has(key)) {
                target.add(key, value.deepCopy());
                continue;
            }
            if (value.isJsonObject() && target.get(key).isJsonObject()) {
                deepMergeDefaults(target.getAsJsonObject(key), value.getAsJsonObject());
            }
        }
    }

    public static FabricBridgeConfig load(Path configDir) throws ConfigException {
        return load(
                configDir,
                System.getenv("STONYPERMS_BRIDGE_TOKEN"),
                System.getenv("STONYPERMS_BRIDGE_WRITE_TOKEN"));
    }

    static FabricBridgeConfig load(Path configDir, String envToken) throws ConfigException {
        return load(configDir, envToken, null);
    }

    static FabricBridgeConfig load(Path configDir, String envToken, String envWriteToken) throws ConfigException {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            root = migrateConfig(file, root);
            JsonObject api = object(root, "api");
            JsonObject write = object(root, "write");
            JsonObject sse = object(root, "sse");
            JsonObject display = object(root, "display");
            JsonObject chat = object(root, "chat");
            JsonObject standalone = object(root, "standalone");

            String rawMode = string(root, "mode", "bridge").trim().toLowerCase(Locale.ROOT);
            Mode mode;
            if (rawMode.isEmpty() || rawMode.equals("bridge")) {
                mode = Mode.BRIDGE;
            } else if (rawMode.equals("standalone")) {
                mode = Mode.STANDALONE;
            } else {
                throw new ConfigException("mode must be 'bridge' or 'standalone', got: " + rawMode);
            }

            URI apiUrl = null;
            String token = "";
            String writeToken = "";
            boolean writeEnabled;
            boolean sseEnabled;
            StandaloneConfig standaloneCfg = null;

            if (mode == Mode.STANDALONE) {
                writeEnabled = bool(write, "enabled", true);
                sseEnabled = false;
                String rawStorage = string(standalone, "storageDir", "stonyperms/data").trim();
                if (rawStorage.isEmpty()) {
                    rawStorage = "stonyperms/data";
                }
                Path storagePath = Path.of(rawStorage);
                if (!storagePath.isAbsolute()) {
                    storagePath = configDir.resolve(storagePath);
                }
                String storageType;
                try {
                    storageType = StandaloneConfig.normalizeStorageType(
                            string(standalone, "storageType", "json"));
                } catch (IllegalArgumentException error) {
                    throw new ConfigException(error.getMessage(), error);
                }
                JsonObject dbObj = object(standalone, "database");
                String rawDbFile = string(dbObj, "file", "").trim();
                if (!rawDbFile.isEmpty() && !":memory:".equals(rawDbFile) && !rawDbFile.startsWith("file:")) {
                    Path dbPath = Path.of(rawDbFile);
                    if (!dbPath.isAbsolute()) {
                        rawDbFile = configDir.resolve(dbPath).toString();
                    }
                }
                String rawPwFile = string(dbObj, "passwordFile", "").trim();
                if (!rawPwFile.isEmpty()) {
                    Path pwPath = Path.of(rawPwFile);
                    if (!pwPath.isAbsolute()) {
                        rawPwFile = configDir.resolve(pwPath).toString();
                    }
                }
                DatabaseConfig dbCfg;
                try {
                    dbCfg = new DatabaseConfig(
                            rawDbFile,
                            string(dbObj, "host", "127.0.0.1"),
                            integer(dbObj, "port", 0),
                            string(dbObj, "database", "stonyperms"),
                            string(dbObj, "username", "stonyperms"),
                            string(dbObj, "password", ""),
                            rawPwFile,
                            string(dbObj, "tablePrefix", DatabaseConfig.DEFAULT_TABLE_PREFIX),
                            string(dbObj, "sslMode", "prefer"),
                            integer(dbObj, "maxPoolSize", 10),
                            integer(dbObj, "minIdle", 5),
                            longVal(dbObj, "maxLifetimeSeconds", 1800L),
                            integer(dbObj, "connectTimeoutSeconds", 5));
                } catch (IllegalArgumentException error) {
                    throw new ConfigException(error.getMessage(), error);
                }
                JsonObject stackObj = object(standalone, "stack");
                StandaloneConfig.StackConfig stackCfg = new StandaloneConfig.StackConfig(
                        bool(stackObj, "prefixEnabled", false),
                        string(stackObj, "prefixStartSpacer", ""),
                        string(stackObj, "prefixMidSpacer", " "),
                        string(stackObj, "prefixEndSpacer", ""),
                        bool(stackObj, "suffixEnabled", false),
                        string(stackObj, "suffixStartSpacer", ""),
                        string(stackObj, "suffixMidSpacer", " "),
                        string(stackObj, "suffixEndSpacer", ""),
                        bool(stackObj, "deduplicate", true));
                standaloneCfg = new StandaloneConfig(
                        storageType,
                        storagePath,
                        dbCfg,
                        string(standalone, "defaultGroup", "default").trim(),
                        string(standalone, "primaryGroupCalculation", "stored").trim(),
                        stackCfg,
                        bool(standalone, "auditLog", true),
                        string(standalone, "webBaseUrl", StandaloneConfig.DEFAULT_WEB_URL).trim(),
                        Math.max(5L, longVal(standalone, "expiryCleanupSeconds", 60L)));
            } else {
                String url = string(api, "url", "http://127.0.0.1:8737").trim();
                try {
                    apiUrl = URI.create(url);
                } catch (IllegalArgumentException error) {
                    throw new ConfigException("api.url is not a valid URI", error);
                }
                String scheme = apiUrl.getScheme() == null ? "" : apiUrl.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new ConfigException("api.url must use http or https");
                }
                if (apiUrl.getHost() == null) {
                    throw new ConfigException("api.url must include a host");
                }

                token = resolveToken(
                        string(api, "token", ""),
                        string(api, "tokenFile", ""),
                        configDir,
                        envToken);
                if (token.isEmpty()) {
                    throw new ConfigException("no API token: set STONYPERMS_BRIDGE_TOKEN, api.token, or api.tokenFile");
                }
                if (token.length() < MIN_TOKEN_LENGTH) {
                    throw new ConfigException("API token must be at least " + MIN_TOKEN_LENGTH + " characters");
                }

                writeEnabled = bool(write, "enabled", false);
                writeToken = resolveToken(
                        string(api, "writeToken", ""),
                        string(api, "writeTokenFile", ""),
                        configDir,
                        envWriteToken);
                if (writeEnabled) {
                    if (writeToken.isEmpty()) {
                        throw new ConfigException(
                                "write.enabled is true but no write token was found: set STONYPERMS_BRIDGE_WRITE_TOKEN, api.writeToken, or api.writeTokenFile");
                    }
                    if (writeToken.length() < MIN_TOKEN_LENGTH) {
                        throw new ConfigException(
                                "API write token must be at least " + MIN_TOKEN_LENGTH + " characters");
                    }
                }
                sseEnabled = bool(sse, "enabled", true);
            }

            long sseMin = Math.max(100L, longVal(sse, "reconnectMinMs", 1000L));
            long sseMax = Math.max(sseMin, longVal(sse, "reconnectMaxMs", 30_000L));

            return new FabricBridgeConfig(
                    mode,
                    apiUrl,
                    token,
                    writeToken,
                    string(root, "serverName", "").trim(),
                    Math.max(5, integer(root, "refreshSeconds", 30)),
                    Math.max(0, integer(root, "staleGraceSeconds", 300)),
                    bool(root, "failClosed", true),
                    bool(root, "useStaleOnError", true),
                    bool(root, "applyToOps", true),
                    writeEnabled,
                    string(write, "defaultActor", "").trim(),
                    sseEnabled,
                    sseMin,
                    sseMax,
                    bool(display, "enabled", true),
                    bool(chat, "enabled", true),
                    DisplayFormat.normalizeChatFormat(string(chat, "format", DisplayFormat.DEFAULT_CHAT_FORMAT)),
                    bool(root, "debug", false),
                    Math.max(250, integer(api, "connectTimeoutMs", 1500)),
                    Math.max(500, integer(api, "requestTimeoutMs", 2500)),
                    standaloneCfg);
        } catch (IOException error) {
            throw new ConfigException("cannot read " + file, error);
        } catch (RuntimeException error) {
            throw new ConfigException("invalid JSON in " + file + ": " + error.getMessage(), error);
        }
    }

    public String effectiveDefaultActor() {
        if (!defaultActor.isEmpty()) {
            return defaultActor;
        }
        return serverName.isEmpty() ? "fabric" : "fabric:" + serverName;
    }

    private static String resolveToken(String inline, String tokenFile, Path configDir, String envToken)
            throws ConfigException {
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }
        if (inline != null && !inline.isBlank()) {
            return inline.trim();
        }
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path path = Path.of(tokenFile.trim());
            if (!path.isAbsolute()) {
                path = configDir.resolve(path);
            }
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException error) {
                throw new ConfigException("cannot read token file: " + path, error);
            }
        }
        return "";
    }

    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key)
                : new JsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static long longVal(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    public boolean isRemotePlainHttp() {
        if (isStandalone() || apiUrl == null) {
            return false;
        }
        if (apiUrl.getScheme() == null || !apiUrl.getScheme().equalsIgnoreCase("http")) {
            return false;
        }
        String host = apiUrl.getHost();
        if (host == null) {
            return true;
        }
        return !(host.equals("127.0.0.1") || host.equals("::1") || host.equalsIgnoreCase("localhost"));
    }

    public static final class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
