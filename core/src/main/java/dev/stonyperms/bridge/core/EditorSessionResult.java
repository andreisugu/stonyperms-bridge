package dev.stonyperms.bridge.core;

/** Result of creating a StonyPermsWeb permissions editor session via Gate HTTP API. */
public record EditorSessionResult(
        String token,
        String url,
        String applyCode,
        long expiresAt,
        boolean liveSave) {
    public EditorSessionResult {
        token = token == null ? "" : token;
        url = url == null ? "" : url;
        applyCode = applyCode == null ? "" : applyCode;
    }
}
