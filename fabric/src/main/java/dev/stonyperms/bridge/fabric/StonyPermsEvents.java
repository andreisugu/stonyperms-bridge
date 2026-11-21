package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.Snapshot;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/** Fabric events emitted when StonyPerms snapshots update or SSE change frames arrive. */
public final class StonyPermsEvents {

    public static final Event<UserUpdated> USER_UPDATED = EventFactory.createArrayBacked(
            UserUpdated.class,
            callbacks -> (uuid, snapshot, previousRevision) -> {
                for (UserUpdated callback : callbacks) {
                    callback.onUserUpdated(uuid, snapshot, previousRevision);
                }
            });

    public static final Event<ChangeReceived> CHANGE_RECEIVED = EventFactory.createArrayBacked(
            ChangeReceived.class,
            callbacks -> event -> {
                for (ChangeReceived callback : callbacks) {
                    callback.onChangeReceived(event);
                }
            });

    private StonyPermsEvents() {
    }

    @FunctionalInterface
    public interface UserUpdated {
        void onUserUpdated(UUID uuid, Snapshot snapshot, long previousRevision);
    }

    @FunctionalInterface
    public interface ChangeReceived {
        void onChangeReceived(ChangeEvent event);
    }
}
