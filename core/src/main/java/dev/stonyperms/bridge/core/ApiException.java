package dev.stonyperms.bridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.regex.Pattern;

/** Non-success HTTP response. Deliberately excludes raw response bodies and secrets. */
public final class ApiException extends IOException {

    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final int status;
    private final String errorCode;

    public ApiException(int status, String message) {
        this(status, null, message);
    }

    public ApiException(int status, String errorCode, String message) {
        super(formatMessage(status, sanitizeCode(errorCode), message));
        this.status = status;
        this.errorCode = sanitizeCode(errorCode);
    }

    public int status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    /** Parses only a safe structured {"error":"<code>"} identifier; never includes raw text. */
    public static String parseSafeErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(responseBody);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("error") || !obj.get("error").isJsonPrimitive() || !obj.get("error").getAsJsonPrimitive().isString()) {
                return null;
            }
            return sanitizeCode(obj.get("error").getAsString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String sanitizeCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return SAFE_ERROR_CODE.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String formatMessage(int status, String safeCode, String message) {
        String base = message == null || message.isBlank() ? "API request failed" : message;
        if (safeCode != null) {
            return base + " [" + safeCode + "] (HTTP " + status + ")";
        }
        return base + " (HTTP " + status + ")";
    }
}
