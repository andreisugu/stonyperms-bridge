package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.DisplayFormat;
import dev.stonyperms.bridge.core.local.DatabaseConfig;
import dev.stonyperms.bridge.core.local.StandaloneConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record PaperBridgeConfig(
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
        boolean integrationsPlaceholderApi,
        boolean integrationsVault,
        boolean chatEnabled,
        String chatFormat,
        boolean tablistEnabled,
        boolean debug,
        StandaloneConfig standalone) {

    public enum Mode {
        BRIDGE,
        STANDALONE
    }

    public static final int MIN_TOKEN_LENGTH = 16;

    /** Backward-compatible 9-arg constructor (defaults to read-only bridge mode). */
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
        this(Mode.BRIDGE, apiUrl, token, "", serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, applyToOps, false, "", true, 1000L, 30_000L, true, true,
                true, DisplayFormat.DEFAULT_CHAT_FORMAT, true, debug, null);
    }

    /** Backward-compatible 17-arg constructor. */
    public PaperBridgeConfig(
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
        this(Mode.BRIDGE, apiUrl, token, writeToken, serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, applyToOps, writeEnabled, defaultActor, sseEnabled, sseReconnectMinMs,
                sseReconnectMaxMs, integrationsPlaceholderApi, integrationsVault,
                true, DisplayFormat.DEFAULT_CHAT_FORMAT, true, debug, null);
    }

    /** Backward-compatible 20-arg constructor. */
    public PaperBridgeConfig(
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
            boolean chatEnabled,
            String chatFormat,
            boolean tablistEnabled,
            boolean debug) {
        this(Mode.BRIDGE, apiUrl, token, writeToken, serverName, refreshSeconds, staleGraceSeconds, failClosed,
                useStaleOnError, applyToOps, writeEnabled, defaultActor, sseEnabled, sseReconnectMinMs,
                sseReconnectMaxMs, integrationsPlaceholderApi, integrationsVault,
                chatEnabled, chatFormat, tablistEnabled, debug, null);
    }

    public boolean isStandalone() {
        return mode == Mode.STANDALONE;
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
        String rawMode = safeTrim(config.getString("mode", "bridge")).toLowerCase(Locale.ROOT);
        Mode mode;
        if (rawMode.isEmpty() || rawMode.equals("bridge")) {
            mode = Mode.BRIDGE;
        } else if (rawMode.equals("standalone")) {
            mode = Mode.STANDALONE;
        } else {
            throw new PaperConfigException("mode must be 'bridge' or 'standalone', got: " + rawMode);
        }

        URI apiUrl = null;
        String token = "";
        String writeToken = "";
        boolean writeEnabled;
        boolean sseEnabled;
        StandaloneConfig standaloneCfg = null;

        if (mode == Mode.STANDALONE) {
            writeEnabled = config.getBoolean("write.enabled", true);
            sseEnabled = false;
            String rawStorageDir = safeTrim(config.getString("standalone.storage-dir", "data"));
            if (rawStorageDir.isEmpty()) {
                rawStorageDir = "data";
            }
            Path storagePath = Path.of(rawStorageDir);
            if (!storagePath.isAbsolute()) {
                storagePath = dataFolder.resolve(storagePath);
            }
            String storageType;
            try {
                storageType = StandaloneConfig.normalizeStorageType(
                        config.getString("standalone.storage-type", "json"));
            } catch (IllegalArgumentException error) {
                throw new PaperConfigException(error.getMessage(), error);
            }
            String rawDbFile = safeTrim(config.getString("standalone.database.file", ""));
            if (!rawDbFile.isEmpty() && !":memory:".equals(rawDbFile) && !rawDbFile.startsWith("file:")) {
                Path dbPath = Path.of(rawDbFile);
                if (!dbPath.isAbsolute()) {
                    rawDbFile = dataFolder.resolve(dbPath).toString();
                }
            }
            String rawPwFile = safeTrim(config.getString("standalone.database.password-file", ""));
            if (!rawPwFile.isEmpty()) {
                Path pwPath = Path.of(rawPwFile);
                if (!pwPath.isAbsolute()) {
                    rawPwFile = dataFolder.resolve(pwPath).toString();
                }
            }
            DatabaseConfig dbCfg;
            try {
                dbCfg = new DatabaseConfig(
                        rawDbFile,
                        config.getString("standalone.database.host", "127.0.0.1"),
                        config.getInt("standalone.database.port", 0),
                        config.getString("standalone.database.database", "stonyperms"),
                        config.getString("standalone.database.username", "stonyperms"),
                        config.getString("standalone.database.password", ""),
                        rawPwFile,
                        config.getString("standalone.database.table-prefix", DatabaseConfig.DEFAULT_TABLE_PREFIX),
                        config.getString("standalone.database.ssl-mode", "prefer"),
                        config.getInt("standalone.database.max-pool-size", 10),
                        config.getInt("standalone.database.min-idle", 5),
                        config.getLong("standalone.database.max-lifetime-seconds", 1800L),
                        config.getInt("standalone.database.connect-timeout-seconds", 5));
            } catch (IllegalArgumentException error) {
                throw new PaperConfigException(error.getMessage(), error);
            }
            String defaultGroup = safeTrim(config.getString("standalone.default-group", "default"));
            String primaryCalc = safeTrim(config.getString("standalone.primary-group-calculation", "stored"));
            boolean auditLog = config.getBoolean("standalone.audit-log", true);
            String webBaseUrl = safeTrim(config.getString("standalone.web-base-url", StandaloneConfig.DEFAULT_WEB_URL));
            long expiryCleanupSeconds = Math.max(5L, config.getLong("standalone.expiry-cleanup-seconds", 60L));
            StandaloneConfig.StackConfig stack = new StandaloneConfig.StackConfig(
                    config.getBoolean("standalone.stack.prefix.enabled", false),
                    config.getString("standalone.stack.prefix.start-spacer", ""),
                    config.getString("standalone.stack.prefix.mid-spacer", " "),
                    config.getString("standalone.stack.prefix.end-spacer", ""),
                    config.getBoolean("standalone.stack.suffix.enabled", false),
                    config.getString("standalone.stack.suffix.start-spacer", ""),
                    config.getString("standalone.stack.suffix.mid-spacer", " "),
                    config.getString("standalone.stack.suffix.end-spacer", ""),
                    config.getBoolean("standalone.stack.deduplicate", true));
            standaloneCfg = new StandaloneConfig(
                    storageType, storagePath, dbCfg, defaultGroup, primaryCalc, stack,
                    auditLog, webBaseUrl, expiryCleanupSeconds);
        } else {
            String configuredUrl = config.getString("api.url", "http://127.0.0.1:8737");
            String url = configuredUrl == null ? "http://127.0.0.1:8737" : configuredUrl.trim();
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

            token = resolveRequiredToken(
                    config.getString("api.token", ""),
                    config.getString("api.token-file", ""),
                    dataFolder,
                    envToken,
                    "STONYPERMS_BRIDGE_TOKEN, api.token, or api.token-file");
            if (token.length() < MIN_TOKEN_LENGTH) {
                throw new PaperConfigException("API token must be at least " + MIN_TOKEN_LENGTH + " characters");
            }

            writeEnabled = config.getBoolean("write.enabled", false);
            writeToken = resolveOptionalToken(
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
            sseEnabled = config.getBoolean("sse.enabled", true);
        }

        String serverName = safeTrim(config.getString("server-name", ""));
        String defaultActor = safeTrim(config.getString("write.default-actor", ""));
        int refresh = Math.max(5, config.getInt("refresh-seconds", 30));
        int staleGrace = Math.max(0, config.getInt("stale-grace-seconds", 300));
        long sseMin = Math.max(100L, config.getLong("sse.reconnect-min-ms", 1000L));
        long sseMax = Math.max(sseMin, config.getLong("sse.reconnect-max-ms", 30_000L));

        return new PaperBridgeConfig(
                mode,
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
                sseEnabled,
                sseMin,
                sseMax,
                config.getBoolean("integrations.placeholderapi", true),
                config.getBoolean("integrations.vault", true),
                config.getBoolean("chat.enabled", true),
                DisplayFormat.normalizeChatFormat(config.getString("chat.format", DisplayFormat.DEFAULT_CHAT_FORMAT)),
                config.getBoolean("tablist.enabled", true),
                config.getBoolean("debug", false),
                standaloneCfg);
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
        if (isStandalone() || apiUrl == null) {
            return false;
        }
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
