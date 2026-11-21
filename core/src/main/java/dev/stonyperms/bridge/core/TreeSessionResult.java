package dev.stonyperms.bridge.core;

/** Result of generating a StonyPermsWeb permission tree session. */
public record TreeSessionResult(String token, String url, long expiresAt, int count) {
    public TreeSessionResult {
        token = token == null ? "" : token;
        url = url == null ? "" : url;
    }
}
