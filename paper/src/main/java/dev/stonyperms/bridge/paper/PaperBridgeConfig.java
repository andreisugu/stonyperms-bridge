package dev.stonyperms.bridge.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record PaperBridgeConfig(
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
        boolean integrationsPlaceholderApi,
        boolean integrationsVault,
        boolean debug) {

    public static final int MIN_TOKEN_LENGTH = 16;

    /** Backward-compatible 9-arg constructor (defaults to read-only with SSE & integrations enabled). */
    public PaperBridgeConfig(
            URI apiUrl,
            String token,
            String serverName,
            int refreshSeconds,
            int staleGraceSeconds,
            boolean failClosed,
            boolean useStaleOnError,
            boolean applyToOps,
            boolean debug) {
        this(apiUrl, token, "", serverName, refreshSeconds, staleGraceSeconds, failClosed, useStaleOnError,
                applyToOps, false, "", true, 1000L, 30_000L, true, true, debug);
    }

    public static PaperBridgeConfig from(FileConfiguration config, Path dataFolder) throws PaperConfigException {
        return from(
                config,
                dataFolder,
                System.getenv("STONYPERMS_BRIDGE_TOKEN"),
                System.getenv("STONYPERMS_BRIDGE_WRITE_TOKEN"));
    }

    static PaperBridgeConfig from(FileConfiguration config, Path dataFolder, String envToken)
            throws PaperConfigException {
        return from(config, dataFolder, envToken, null);
    }

    static PaperBridgeConfig from(
            FileConfiguration config, Path dataFolder, String envToken, String envWriteToken)
            throws PaperConfigException {
        String configuredUrl = config.getString("api.url", "http://127.0.0.1:8737");
        String url = configuredUrl == null ? "http://127.0.0.1:8737" : configuredUrl.trim();
        URI apiUrl;
        try {
            apiUrl = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new PaperConfigException("api.url is not a valid URI", error);
        }
        String scheme = apiUrl.getScheme() == null ? "" : apiUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new PaperConfigException("api.url must use http or https");
        }
        if (apiUrl.getHost() == null) {
            throw new PaperConfigException("api.url must include a host");
        }

        String token = resolveRequiredToken(
                config.getString("api.token", ""),
                config.getString("api.token-file", ""),
                dataFolder,
                envToken,
                "STONYPERMS_BRIDGE_TOKEN, api.token, or api.token-file");
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new PaperConfigException("API token must be at least " + MIN_TOKEN_LENGTH + " characters");
        }

        boolean writeEnabled = config.getBoolean("write.enabled", false);
        String writeToken = resolveOptionalToken(
                config.getString("api.write-token", ""),
                config.getString("api.write-token-file", ""),
                dataFolder,
                envWriteToken);
        if (writeEnabled) {
            if (writeToken.isBlank()) {
                throw new PaperConfigException(
                        "write.enabled is true but no write token was found: set STONYPERMS_BRIDGE_WRITE_TOKEN, api.write-token, or api.write-token-file");
            }
            if (writeToken.length() < MIN_TOKEN_LENGTH) {
                throw new PaperConfigException(
                        "API write token must be at least " + MIN_TOKEN_LENGTH + " characters");
            }
        }

        String serverName = safeTrim(config.getString("server-name", ""));
        String defaultActor = safeTrim(config.getString("write.default-actor", ""));
        int refresh = Math.max(5, config.getInt("refresh-seconds", 30));
        int staleGrace = Math.max(0, config.getInt("stale-grace-seconds", 300));
        long sseMin = Math.max(100L, config.getLong("sse.reconnect-min-ms", 1000L));
        long sseMax = Math.max(sseMin, config.getLong("sse.reconnect-max-ms", 30_000L));

        return new PaperBridgeConfig(
                apiUrl,
                token,
                writeToken,
                serverName,
                refresh,
                staleGrace,
                config.getBoolean("fail-closed", true),
                config.getBoolean("use-stale-on-error", true),
                config.getBoolean("apply-to-ops", true),
                writeEnabled,
                defaultActor,
                config.getBoolean("sse.enabled", true),
                sseMin,
                sseMax,
                config.getBoolean("integrations.placeholderapi", true),
                config.getBoolean("integrations.vault", true),
                config.getBoolean("debug", false));
    }

    public String effectiveDefaultActor() {
        if (!defaultActor.isEmpty()) {
            return defaultActor;
        }
        return serverName.isEmpty() ? "paper" : "paper:" + serverName;
    }

    private static String resolveRequiredToken(
            String inline, String tokenFile, Path dataFolder, String envToken, String hint)
            throws PaperConfigException {
        String resolved = resolveOptionalToken(inline, tokenFile, dataFolder, envToken);
        if (resolved.isEmpty()) {
            throw new PaperConfigException("no API token: set " + hint);
        }
        return resolved;
    }

    private static String resolveOptionalToken(
            String inline, String tokenFile, Path dataFolder, String envToken)
            throws PaperConfigException {
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }
        if (inline != null && !inline.isBlank()) {
            return inline.trim();
        }
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path path = Path.of(tokenFile.trim());
            if (!path.isAbsolute()) {
                path = dataFolder.resolve(path);
            }
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException error) {
                throw new PaperConfigException("cannot read token file: " + path, error);
            }
        }
        return "";
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public boolean isRemotePlainHttp() {
        String scheme = apiUrl.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http")) {
            return false;
        }
        String host = apiUrl.getHost();
        if (host == null) {
            return true;
        }
        return !(host.equals("127.0.0.1") || host.equals("::1") || host.equalsIgnoreCase("localhost"));
    }

    public static final class PaperConfigException extends Exception {
        public PaperConfigException(String message) {
            super(message);
        }

        public PaperConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
