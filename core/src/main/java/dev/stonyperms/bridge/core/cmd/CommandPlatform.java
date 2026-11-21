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

    /** Returns registered plugin/platform permission keys for Web Editor autocomplete and Tree Viewer. */
    default List<String> collectKnownPermissions() {
        return List.of();
    }

    /** Returns backend context suggestions such as world names and server name. */
    default Map<String, List<String>> collectExtraContexts() {
        return Map.of();
    }

    /** Sends a clickable URL message on platforms that support rich text components. */
    default void sendUrlMessage(Object sender, String prefix, String url, String hoverHint) {
        sendMessage(sender, prefix + " " + url);
    }
}
