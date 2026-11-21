package dev.stonyperms.bridge.core;

/** Result of uploading Gate's recorded verbose permission checks for web viewing. */
public record VerboseSessionResult(String token, String url, long expiresAt, int count) {
    public VerboseSessionResult {
        token = token == null ? "" : token;
        url = url == null ? "" : url;
    }
}
