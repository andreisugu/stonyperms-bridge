package dev.stonyperms.bridge.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record FabricBridgeConfig(
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

    public static final int MIN_TOKEN_LENGTH = 16;
    public static final String FILE_NAME = "stonyperms-bridge.json";

    private static final String DEFAULT_CONFIG = """
            {
              "api": {
                "url": "http://127.0.0.1:8737",
                "token": "",
                "tokenFile": "token",
                "connectTimeoutMs": 1500,
                "requestTimeoutMs": 2500
              },
              "serverName": "",
              "refreshSeconds": 30,
              "staleGraceSeconds": 300,
              "failClosed": true,
              "useStaleOnError": true,
              "debug": false
            }
            """;

    public static FabricBridgeConfig load(Path configDir) throws ConfigException {
        return load(configDir, System.getenv("STONYPERMS_BRIDGE_TOKEN"));
    }

    static FabricBridgeConfig load(Path configDir, String envToken) throws ConfigException {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject api = root.has("api") && root.get("api").isJsonObject()
                    ? root.getAsJsonObject("api")
                    : new JsonObject();

            String url = string(api, "url", "http://127.0.0.1:8737");
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

            String token = resolveToken(api, configDir, envToken);
            if (token.length() < MIN_TOKEN_LENGTH) {
                throw new ConfigException("API token must be at least " + MIN_TOKEN_LENGTH + " characters");
            }

            return new FabricBridgeConfig(
                    apiUrl,
                    token,
                    string(root, "serverName", "").trim(),
                    Math.max(5, integer(root, "refreshSeconds", 30)),
                    Math.max(0, integer(root, "staleGraceSeconds", 300)),
                    bool(root, "failClosed", true),
                    bool(root, "useStaleOnError", true),
                    bool(root, "debug", false),
                    Math.max(250, integer(api, "connectTimeoutMs", 1500)),
                    Math.max(500, integer(api, "requestTimeoutMs", 2500)));
        } catch (IOException error) {
            throw new ConfigException("cannot read " + file, error);
        } catch (RuntimeException error) {
            throw new ConfigException("invalid JSON in " + file + ": " + error.getMessage(), error);
        }
    }

    private static String resolveToken(JsonObject api, Path configDir, String envToken) throws ConfigException {
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }
        String inline = string(api, "token", "");
        if (!inline.isBlank()) {
            return inline.trim();
        }
        String tokenFile = string(api, "tokenFile", "");
        if (!tokenFile.isBlank()) {
            Path path = Path.of(tokenFile);
            if (!path.isAbsolute()) {
                path = configDir.resolve(path);
            }
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException error) {
                throw new ConfigException("cannot read api.tokenFile: " + path, error);
            }
        }
        throw new ConfigException("no API token: set STONYPERMS_BRIDGE_TOKEN, api.token, or api.tokenFile");
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
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
