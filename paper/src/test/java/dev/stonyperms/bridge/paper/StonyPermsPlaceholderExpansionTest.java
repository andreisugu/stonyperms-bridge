package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.UserInfo;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StonyPermsPlaceholderExpansionTest {

    @Test
    void resolvesAllSupportedPlaceholdersAndUnknownReturnsNull() {
        UUID id = UUID.randomUUID();
        Map<String, String> onlineCtx = Map.of("server", "lobby", "world", "hub");
        Map<String, String> offlineCtx = Map.of("server", "lobby");

        SnapshotCache cache = new SnapshotCache(60_000);
        Snapshot onlineSnap = new Snapshot(
                1, 4, "", 30,
                new UserInfo(id.toString(), "Steve", "vip", false, Map.of("rank_tier", "gold"), "&a[VIP] ", " &7[S]"),
                List.of(new GroupRef("vip", "&aVIP+", 50), new GroupRef("default", "Default", 0)),
                List.of(new Node("lobby.fly", true, onlineCtx, 0, "group:vip", 50, false)));
        cache.put(new SnapshotCache.Key(id, Contexts.key(onlineCtx)), onlineSnap, null, System.currentTimeMillis());

        try (StonyPermsClient client = new StonyPermsClient(
                URI.create("http://127.0.0.1:8737"), "read-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI api = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, cache, new PermissionResolver(), true, true),
                    client, Runnable::run, false, "paper", ignored -> {}, () -> {});

            StonyPermsPlaceholderExpansion expansion = new StonyPermsPlaceholderExpansion(
                    api, "0.2.0-SNAPSHOT", "StonyPermsBridge",
                    p -> onlineCtx, offlineCtx);

            Player online = mock(Player.class);
            when(online.getUniqueId()).thenReturn(id);
            when(online.isOnline()).thenReturn(true);
            when(online.getPlayer()).thenReturn(online);

            assertEquals("stonyperms", expansion.getIdentifier());
            assertEquals("vip", expansion.onRequest(online, "primary_group"));
            assertEquals("&aVIP+", expansion.onRequest(online, "primary_group_display"));
            assertEquals("&a[VIP] ", expansion.onRequest(online, "prefix"));
            assertEquals(" &7[S]", expansion.onRequest(online, "suffix"));
            assertEquals("vip,default", expansion.onRequest(online, "groups"));
            assertEquals("50", expansion.onRequest(online, "weight"));
            assertEquals("gold", expansion.onRequest(online, "meta_rank_tier"));
            assertEquals("", expansion.onRequest(online, "meta_missing"));
            assertEquals("true", expansion.onRequest(online, "in_group_vip"));
            assertEquals("false", expansion.onRequest(online, "in_group_admin"));
            assertEquals("true", expansion.onRequest(online, "has_permission_lobby.fly"));
            assertEquals("false", expansion.onRequest(online, "has_permission_other.node"));
            assertNull(expansion.onRequest(online, "unknown_placeholder"));

            OfflinePlayer offline = mock(OfflinePlayer.class);
            when(offline.getUniqueId()).thenReturn(UUID.randomUUID());
            when(offline.isOnline()).thenReturn(false);
            assertEquals("default", expansion.onRequest(offline, "primary_group"));
            assertEquals("0", expansion.onRequest(offline, "weight"));
        }
    }
}
