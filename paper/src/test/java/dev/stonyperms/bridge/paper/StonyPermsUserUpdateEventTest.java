package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class StonyPermsUserUpdateEventTest {

    @Test
    void exposesSyncEventMetadata() {
        Player player = mock(Player.class);
        Snapshot snapshot = new Snapshot(1, 7, "", 30,
                new UserInfo(UUID.randomUUID().toString(), "Steve", "vip", false, Map.of(), "", ""),
                List.of(), List.of());

        StonyPermsUserUpdateEvent event = new StonyPermsUserUpdateEvent(player, snapshot, 6L);
        assertFalse(event.isAsynchronous());
        assertSame(player, event.getPlayer());
        assertSame(snapshot, event.getSnapshot());
        assertEquals(6L, event.getPreviousRevision());
        assertSame(StonyPermsUserUpdateEvent.getHandlerList(), event.getHandlers());
    }
}
