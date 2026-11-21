package dev.stonyperms.bridge.core;

import java.util.Locale;

/** Parent assignment mode supported by Gate write endpoints. */
public enum ParentMode {
    ADD,
    SET,
    ACCUMULATE,
    REPLACE,
    DENY;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
