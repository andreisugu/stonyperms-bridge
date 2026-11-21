package dev.stonyperms.bridge.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stonyperms.bridge.core.DisplayFormat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record FabricBridgeConfig(
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

    public static final int MIN_TOKEN_LENGTH = 16;
    public static final String FILE_NAME = "stonyperms-bridge.json";

    private static final String DEFAULT_CONFIG = """
            {
              "api": {
                "url": "http://127.0.0.1:8737",
                "token": "",
                "tokenFile": "token",
                "writeToken": "",
                "writeTokenFile": "",
                "connectTimeoutMs": 1500,
                "requestTimeoutMs": 2500
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
        this(apiUrl, token, "", serverName, refreshSeconds, staleGraceSeconds, failClosed, useStaleOnError,
                true, false, "", true, 1000L, 30_000L,
                true, true, DisplayFormat.DEFAULT_CHAT_FORMAT, debug, connectTimeoutMs, requestTimeoutMs);
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
        this(apiUrl, token, writeToken, serverName, refreshSeconds, staleGraceSeconds, failClosed, useStaleOnError,
                applyToOps, writeEnabled, defaultActor, sseEnabled, sseReconnectMinMs, sseReconnectMaxMs,
                true, true, DisplayFormat.DEFAULT_CHAT_FORMAT, debug, connectTimeoutMs, requestTimeoutMs);
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
            JsonObject api = object(root, "api");
            JsonObject write = object(root, "write");
            JsonObject sse = object(root, "sse");
            JsonObject display = object(root, "display");
            JsonObject chat = object(root, "chat");

            String url = string(api, "url", "http://127.0.0.1:8737").trim();
            URI apiUrl;
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

            String token = resolveToken(
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

            boolean writeEnabled = bool(write, "enabled", false);
            String writeToken = resolveToken(
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

            long sseMin = Math.max(100L, longVal(sse, "reconnectMinMs", 1000L));
            long sseMax = Math.max(sseMin, longVal(sse, "reconnectMaxMs", 30_000L));

            return new FabricBridgeConfig(
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
                    bool(sse, "enabled", true),
                    sseMin,
                    sseMax,
                    bool(display, "enabled", true),
                    bool(chat, "enabled", true),
                    DisplayFormat.normalizeChatFormat(string(chat, "format", DisplayFormat.DEFAULT_CHAT_FORMAT)),
                    bool(root, "debug", false),
                    Math.max(250, integer(api, "connectTimeoutMs", 1500)),
                    Math.max(500, integer(api, "requestTimeoutMs", 2500)));
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
