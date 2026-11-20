package dev.stonyperms.bridge.core;

public record CheckResult(String value, String source, long revision) {
    public CheckResult {
        value = value == null ? "undefined" : value;
        source = source == null ? "" : source;
    }

    public Tri tri() {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> Tri.TRUE;
            case "false" -> Tri.FALSE;
            default -> Tri.UNDEFINED;
        };
    }
}
