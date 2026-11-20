package dev.stonyperms.bridge.core;

/** The three-valued result used by the StonyPerms engine. */
public enum Tri {
    TRUE,
    FALSE,
    UNDEFINED;

    public boolean orElse(boolean fallback) {
        return switch (this) {
            case TRUE -> true;
            case FALSE -> false;
            case UNDEFINED -> fallback;
        };
    }

    public static Tri of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
