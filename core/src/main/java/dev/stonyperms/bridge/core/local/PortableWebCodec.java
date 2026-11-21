package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Encodes and decodes portable {@code sp1_} gzipped + unpadded base64url payloads
 * compatible with StonyPermsWeb ({@code gate-stonyperms/manager/web_sessions.go}).
 */
public final class PortableWebCodec {

    public static final String PORTABLE_PREFIX = "sp1_";
    public static final int MAX_PORTABLE_CODE_BYTES = 64 << 10; // 64 KiB

    private PortableWebCodec() {
    }

    public static final class EditorSavePayload {
        public List<JsonLocalStorage.StoredGroup> groups;
        public List<JsonLocalStorage.StoredTrack> tracks;
        public List<JsonLocalStorage.StoredUser> users;
        public List<String> deletedGroups;
        public List<String> deletedTracks;
        public List<String> deletedUsers;
        public Long baseRevision;
        public String actor;
    }

    public static final class VerboseSessionMetadata {
        public String uploadedBy;
        public String commandAlias;
        public long startTime;
        public long endTime;
        public long durationMs;
        public int totalCount;
        public int shownCount;
        public String filter;
        public boolean truncated;
        public long expiresAt;
    }

    public static final class VerboseSessionData {
        public String token;
        public VerboseSessionMetadata metadata;
        public List<LocalPermissionEngine.VerboseEntry> entries;
    }

    public static final class TreeSessionMetadata {
        public String uploadedBy;
        public String commandAlias;
        public long time;
        public String root;
        public int count;
        public long expiresAt;
    }

    public static final class TreeSessionData {
        public String token;
        public TreeSessionMetadata metadata;
        public List<String> permissions;
    }

    public static String encodeObject(Object payload) throws IOException {
        byte[] jsonBytes = JsonLocalStorage.COMPACT_GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos) {
            {
                this.def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            gz.write(jsonBytes);
        }
        return PORTABLE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
    }

    public static <T> T decodeObject(String code, Class<T> type) throws IOException {
        if (code == null) {
            throw new ApiException(400, "invalid_apply_code", "invalid or expired applyedits code");
        }
        String trimmed = code.trim();
        if (!trimmed.startsWith(PORTABLE_PREFIX)) {
            throw new ApiException(400, "invalid_apply_code", "invalid or expired applyedits code");
        }
        String encoded = trimmed.substring(PORTABLE_PREFIX.length());
        if (encoded.isEmpty()) {
            throw new ApiException(400, "invalid_apply_code", "invalid or expired applyedits code");
        }
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new ApiException(400, "invalid_apply_code", "invalid base64 payload");
        }
        byte[] decompressed;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = gz.read(buf)) != -1) {
                total += read;
                if (total > MAX_PORTABLE_CODE_BYTES) {
                    throw new ApiException(400, "payload_too_large",
                            "decompressed payload exceeds " + MAX_PORTABLE_CODE_BYTES + " bytes");
                }
                out.write(buf, 0, read);
            }
            decompressed = out.toByteArray();
        } catch (ApiException e) {
            throw e;
        } catch (IOException error) {
            throw new ApiException(400, "invalid_apply_code", "invalid gzip payload");
        }

        try {
            T parsed = JsonLocalStorage.COMPACT_GSON.fromJson(
                    new String(decompressed, StandardCharsets.UTF_8), type);
            if (parsed == null) {
                throw new ApiException(400, "invalid_apply_code", "empty JSON payload");
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new ApiException(400, "invalid_apply_code", "invalid JSON payload");
        }
    }

    public static EditorSavePayload decodeEditorPayload(String code) throws IOException {
        return decodeObject(code, EditorSavePayload.class);
    }

    public static String joinWebPath(String baseUrl, String suffix) {
        String base = (baseUrl == null || baseUrl.isBlank())
                ? StandaloneConfig.DEFAULT_WEB_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = StandaloneConfig.DEFAULT_WEB_URL;
        }
        String cleanSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        return base + cleanSuffix;
    }

    public static String urlEscapeSimple(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int c = raw & 0xff;
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (safe) {
                b.append((char) c);
            } else {
                b.append('%');
                b.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                b.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return b.toString();
    }
}
