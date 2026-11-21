package dev.stonyperms.bridge.core.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Relational database connection and pool settings for standalone mode. */
public record DatabaseConfig(
        String file,
        String host,
        int port,
        String database,
        String username,
        String password,
        String passwordFile,
        String tablePrefix,
        String sslMode,
        int maxPoolSize,
        int minIdle,
        long maxLifetimeSeconds,
        int connectTimeoutSeconds) {

    public static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{0,32}$");
    public static final String DEFAULT_TABLE_PREFIX = "stonyperms_";

    public DatabaseConfig {
        file = file == null ? "" : file.trim();
        host = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
        port = Math.max(0, port);
        database = (database == null || database.isBlank()) ? "stonyperms" : database.trim();
        username = (username == null || username.isBlank()) ? "stonyperms" : username.trim();
        password = password == null ? "" : password;
        passwordFile = passwordFile == null ? "" : passwordFile.trim();
        tablePrefix = (tablePrefix == null || tablePrefix.isEmpty()) ? DEFAULT_TABLE_PREFIX : tablePrefix;
        validateTablePrefix(tablePrefix);
        sslMode = (sslMode == null || sslMode.isBlank()) ? "prefer" : sslMode.trim();
        maxPoolSize = maxPoolSize <= 0 ? 10 : maxPoolSize;
        minIdle = minIdle <= 0 ? Math.min(5, maxPoolSize) : Math.min(minIdle, maxPoolSize);
        maxLifetimeSeconds = maxLifetimeSeconds <= 0 ? 1800L : maxLifetimeSeconds;
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 5 : connectTimeoutSeconds;
    }

    /** Convenience constructor without passwordFile. */
    public DatabaseConfig(
            String file,
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            String sslMode,
            int maxPoolSize,
            int minIdle,
            long maxLifetimeSeconds,
            int connectTimeoutSeconds) {
        this(file, host, port, database, username, password, "", tablePrefix, sslMode,
                maxPoolSize, minIdle, maxLifetimeSeconds, connectTimeoutSeconds);
    }

    public static DatabaseConfig defaults() {
        return new DatabaseConfig(
                "", "127.0.0.1", 0, "stonyperms", "stonyperms", "", "",
                DEFAULT_TABLE_PREFIX, "prefer", 10, 5, 1800L, 5);
    }

    public static void validateTablePrefix(String prefix) {
        if (prefix == null || !TABLE_PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "Invalid database table prefix (must match ^[a-zA-Z0-9_]{0,32}$): " + prefix);
        }
    }

    /**
     * Resolves DB password in priority order: STONYPERMS_BRIDGE_DB_PASSWORD env var,
     * STONYPERMS_DB_PASSWORD env var, inline password, then passwordFile.
     */
    public String resolvePassword() throws IOException {
        return resolvePassword(
                System.getenv("STONYPERMS_BRIDGE_DB_PASSWORD"),
                System.getenv("STONYPERMS_DB_PASSWORD"));
    }

    public String resolvePassword(String bridgeEnv, String globalEnv) throws IOException {
        if (bridgeEnv != null && !bridgeEnv.isEmpty()) {
            return trimTrailingNewlines(bridgeEnv);
        }
        if (globalEnv != null && !globalEnv.isEmpty()) {
            return trimTrailingNewlines(globalEnv);
        }
        if (!password.isEmpty()) {
            return password;
        }
        if (!passwordFile.isEmpty()) {
            String raw = Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8);
            return trimTrailingNewlines(raw);
        }
        return "";
    }

    private static String trimTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
