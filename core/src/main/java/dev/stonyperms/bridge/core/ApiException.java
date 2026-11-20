package dev.stonyperms.bridge.core;

import java.io.IOException;

/** Non-success HTTP response. The message deliberately excludes the body. */
public final class ApiException extends IOException {
    private final int status;

    public ApiException(int status, String message) {
        super(message + " (HTTP " + status + ")");
        this.status = status;
    }

    public int status() {
        return status;
    }
}
