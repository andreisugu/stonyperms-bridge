package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Snapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Synchronous Bukkit event fired on the main server thread when a player's
 * applied StonyPerms snapshot updates.
 */
public final class StonyPermsUserUpdateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Snapshot snapshot;
    private final long previousRevision;

    public StonyPermsUserUpdateEvent(Player player, Snapshot snapshot, long previousRevision) {
        super(false);
        this.player = Objects.requireNonNull(player, "player");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.previousRevision = previousRevision;
    }

    public Player getPlayer() {
        return player;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public long getPreviousRevision() {
        return previousRevision;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
