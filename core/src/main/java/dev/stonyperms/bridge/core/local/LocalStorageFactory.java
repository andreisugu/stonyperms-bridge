package dev.stonyperms.bridge.core.local;

import java.io.IOException;
import java.util.Objects;

/** Opens the configured {@link LocalStorage} backend for standalone mode. */
public final class LocalStorageFactory {

    private LocalStorageFactory() {
    }

    public static LocalStorage open(StandaloneConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        String type = config.storageType();
        if ("json".equals(type)) {
            return new JsonLocalStorage(config.storageDir());
        }
        return new SqlLocalStorage(type, config.storageDir(), config.database());
    }
}
