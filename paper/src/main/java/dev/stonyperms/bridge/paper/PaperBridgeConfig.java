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
        String serverName,
        int refreshSeconds,
        int staleGraceSeconds,
        boolean failClosed,
        boolean useStaleOnError,
        boolean applyToOps,
        boolean debug) {

    public static final int MIN_TOKEN_LENGTH = 16;

    public static PaperBridgeConfig from(FileConfiguration config, Path dataFolder) throws PaperConfigException {
        return from(config, dataFolder, System.getenv("STONYPERMS_BRIDGE_TOKEN"));
    }

    static PaperBridgeConfig from(FileConfiguration config, Path dataFolder, String envToken)
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

        String token = resolveToken(config, dataFolder, envToken);
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new PaperConfigException("API token must be at least " + MIN_TOKEN_LENGTH + " characters");
        }

        int refresh = Math.max(5, config.getInt("refresh-seconds", 30));
        int staleGrace = Math.max(0, config.getInt("stale-grace-seconds", 300));

        return new PaperBridgeConfig(
                apiUrl,
                token,
                config.getString("server-name", "").trim(),
                refresh,
                staleGrace,
                config.getBoolean("fail-closed", true),
                config.getBoolean("use-stale-on-error", true),
                config.getBoolean("apply-to-ops", true),
                config.getBoolean("debug", false));
    }

    private static String resolveToken(FileConfiguration config, Path dataFolder, String envToken)
            throws PaperConfigException {
        String env = envToken;
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        String inline = config.getString("api.token", "");
        if (inline != null && !inline.isBlank()) {
            return inline.trim();
        }

        String tokenFile = config.getString("api.token-file", "");
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path path = Path.of(tokenFile);
            if (!path.isAbsolute()) {
                path = dataFolder.resolve(path);
            }
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException error) {
                throw new PaperConfigException("cannot read api.token-file: " + path, error);
            }
        }

        throw new PaperConfigException(
                "no API token: set STONYPERMS_BRIDGE_TOKEN, api.token, or api.token-file");
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
