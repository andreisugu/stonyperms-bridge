package dev.stonyperms.bridge.core.local;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Configuration governing StonyPerms standalone local engine, storage, and web viewer URLs. */
public record StandaloneConfig(
        Path storageDir,
        String defaultGroup,
        String primaryGroupCalculation,
        StackConfig stackConfig,
        boolean auditLogEnabled,
        String webBaseUrl,
        long expiryCleanupSeconds) {

    public static final String DEFAULT_WEB_URL = "https://andreisugu.github.io/gate-stonyperms";

    public record StackConfig(
            boolean prefixEnabled,
            String prefixStartSpacer,
            String prefixMidSpacer,
            String prefixEndSpacer,
            boolean suffixEnabled,
            String suffixStartSpacer,
            String suffixMidSpacer,
            String suffixEndSpacer,
            boolean deduplicate) {

        public StackConfig {
            prefixStartSpacer = prefixStartSpacer == null ? "" : prefixStartSpacer;
            prefixMidSpacer = prefixMidSpacer == null ? "" : prefixMidSpacer;
            prefixEndSpacer = prefixEndSpacer == null ? "" : prefixEndSpacer;
            suffixStartSpacer = suffixStartSpacer == null ? "" : suffixStartSpacer;
            suffixMidSpacer = suffixMidSpacer == null ? "" : suffixMidSpacer;
            suffixEndSpacer = suffixEndSpacer == null ? "" : suffixEndSpacer;
        }

        public static StackConfig defaults() {
            return new StackConfig(false, "", "", "", false, "", "", "", true);
        }
    }

    public StandaloneConfig {
        storageDir = Objects.requireNonNull(storageDir, "storageDir");
        defaultGroup = (defaultGroup == null || defaultGroup.isBlank())
                ? "default" : defaultGroup.trim().toLowerCase(Locale.ROOT);
        String calc = primaryGroupCalculation == null ? "stored" : primaryGroupCalculation.trim().toLowerCase(Locale.ROOT);
        primaryGroupCalculation = switch (calc) {
            case "parents-by-weight", "all-parents-by-weight" -> calc;
            default -> "stored";
        };
        stackConfig = stackConfig == null ? StackConfig.defaults() : stackConfig;
        webBaseUrl = normalizeWebUrl(webBaseUrl);
        expiryCleanupSeconds = Math.max(0L, expiryCleanupSeconds);
    }

    public static StandaloneConfig defaults(Path storageDir) {
        return new StandaloneConfig(
                storageDir,
                "default",
                "stored",
                StackConfig.defaults(),
                true,
                DEFAULT_WEB_URL,
                60L);
    }

    private static String normalizeWebUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WEB_URL;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? DEFAULT_WEB_URL : trimmed;
    }
}
