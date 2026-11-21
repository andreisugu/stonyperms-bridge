package dev.stonyperms.bridge.paper;

import java.util.Optional;
import java.util.UUID;

/** Non-blocking player UUID and online-world lookup abstraction for Vault integration and tests. */
@FunctionalInterface
public interface VaultPlayerLookup {
    Optional<UUID> findUuidByName(String playerName);

    default Optional<String> onlineWorld(UUID uuid) {
        return Optional.empty();
    }
}
