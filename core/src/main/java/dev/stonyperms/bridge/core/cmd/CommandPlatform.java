package dev.stonyperms.bridge.core.cmd;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Platform abstraction consumed by {@link BridgeCommandHandler}. */
public interface CommandPlatform {

    record OnlinePlayerRef(UUID uuid, String name, Map<String, String> contexts) {
    }

    boolean hasAdminPermission(Object sender);

    boolean hasWritePermission(Object sender);

    String actorName(Object sender);

    /** Sends a message to the sender, dispatching to the main thread if needed. */
    void sendMessage(Object sender, String message);

    List<String> onlinePlayerNames();

    Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid);

    void requestRefreshAll();

    void requestRefreshPlayer(UUID uuid);

    boolean reloadBridge();

    String statusSummary();

    Map<String, String> defaultContexts();
}
