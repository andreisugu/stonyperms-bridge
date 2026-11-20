package dev.stonyperms.bridge.core;

import java.util.Objects;

/** A resolved permission value and the source that produced it. */
public record Decision(Tri value, String source) {
    public Decision {
        Objects.requireNonNull(value, "value");
        source = source == null ? "" : source;
    }

    public static Decision undefined(String source) {
        return new Decision(Tri.UNDEFINED, source);
    }

    public boolean defined() {
        return value != Tri.UNDEFINED;
    }
}
