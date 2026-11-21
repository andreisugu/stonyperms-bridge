package dev.stonyperms.bridge.paper;

import com.sun.net.httpserver.HttpServer;
import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.UserInfo;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StonyPermsVaultTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void readsCachedNonBlockingAndFailsClosedOnWriteDisabled() throws Exception {
        UUID steveId = UUID.randomUUID();
        List<String> httpHits = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        executor = Executors.newSingleThreadExecutor();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        SnapshotCache cache = new SnapshotCache(60_000);
        Map<String, String> ctx = Map.of("server", "lobby", "world", "hub");
        Snapshot snap = new Snapshot(
                1, 3, "", 30,
                new UserInfo(steveId.toString(), "Steve", "vip", false,
                        Map.of("tokens", "42", "rate", "2.5", "featured", "true"), "&a[VIP] ", " &7*"),
                List.of(new GroupRef("vip", "VIP", 50), new GroupRef("default", "Default", 0)),
                List.of(new Node("vault.node", true, ctx, 0, "group:vip", 50, false)));
        cache.put(new SnapshotCache.Key(steveId, Contexts.key(ctx)), snap, null, System.currentTimeMillis());

        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        VaultPlayerLookup lookup = new VaultPlayerLookup() {
            @Override
            public Optional<UUID> findUuidByName(String playerName) {
                return "Steve".equalsIgnoreCase(playerName) ? Optional.of(steveId) : Optional.empty();
            }

            @Override
            public Optional<String> onlineWorld(UUID uuid) {
                return steveId.equals(uuid) ? Optional.of("hub") : Optional.empty();
            }
        };

        try (StonyPermsClient client = new StonyPermsClient(base, "read-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI roApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, cache, new PermissionResolver(), true, true),
                    client, executor, false, "paper", ignored -> {}, () -> {});

            StonyPermsVaultPermission perms = new StonyPermsVaultPermission(
                    plugin, roApi, lookup, p -> ctx, w -> w == null ? Map.of("server", "lobby") : Map.of("server", "lobby", "world", w));
            perms.updateGroupsCache(List.of(new GroupRecord(
                    "vip", "VIP", 50, List.of(),
                    List.of(new Node("group.perm", true, Map.of(), 0, "group:vip", 50, false)),
                    Map.of("color", "green"),
                    List.of(new PriorityMeta(50, "&a[VIP] ", 0, Map.of())),
                    List.of())));
            StonyPermsVaultChat chat = new StonyPermsVaultChat(
                    perms, roApi, lookup, w -> w == null ? Map.of("server", "lobby") : Map.of("server", "lobby", "world", w));

            Player online = mock(Player.class);
            World world = mock(World.class);
            when(world.getName()).thenReturn("hub");
            when(online.getUniqueId()).thenReturn(steveId);
            when(online.getWorld()).thenReturn(world);

            assertTrue(perms.playerHas("hub", "Steve", "vault.node"));
            assertTrue(perms.playerHas((String) null, "Steve", "vault.node"));
            assertTrue(perms.playerHas(online, "vault.node"));
            assertTrue(perms.playerInGroup("hub", "Steve", "vip"));
            assertEquals("vip", perms.getPrimaryGroup("hub", "Steve"));
            assertArrayEquals(new String[]{"vip", "default"}, perms.getPlayerGroups("hub", "Steve"));
            assertArrayEquals(new String[]{"vip"}, perms.getGroups());
            assertTrue(perms.groupHas("hub", "vip", "group.perm"));

            assertEquals("&a[VIP] ", chat.getPlayerPrefix((String) null, "Steve"));
            assertEquals(" &7*", chat.getPlayerSuffix("hub", "Steve"));
            assertEquals(42, chat.getPlayerInfoInteger("hub", "Steve", "tokens", 0));
            assertEquals(2.5, chat.getPlayerInfoDouble("hub", "Steve", "rate", 0.0));
            assertTrue(chat.getPlayerInfoBoolean("hub", "Steve", "featured", false));
            assertEquals("&a[VIP] ", chat.getGroupPrefix("hub", "vip"));
            assertEquals("green", chat.getGroupInfoString("hub", "vip", "color", ""));

            // Fail-closed writes
            assertFalse(perms.playerAdd("hub", "Steve", "extra.perm"));
            assertFalse(perms.playerRemove("hub", "Steve", "extra.perm"));
            assertFalse(perms.playerAddGroup("hub", "Steve", "mvp"));
            assertFalse(perms.playerRemoveGroup("hub", "Steve", "vip"));
            assertFalse(perms.groupAdd("hub", "vip", "extra.perm"));
            assertFalse(perms.groupRemove("hub", "vip", "extra.perm"));
            chat.setPlayerPrefix("hub", "Steve", "&c[Admin] ");
            chat.setPlayerInfoString("hub", "Steve", "tokens", "99");
            assertTrue(httpHits.isEmpty());
        }
    }

    @Test
    void dispatchesAsyncWritesWhenWriteEnabled() throws Exception {
        UUID steveId = UUID.randomUUID();
        CountDownLatch fourWrites = new CountDownLatch(4);
        List<String> calls = new CopyOnWriteArrayList<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String resp = exchange.getRequestURI().getPath().startsWith("/v1/users/")
                    ? "{\"revision\":10,\"user\":{\"uuid\":\"" + steveId + "\",\"username\":\"Steve\",\"primaryGroup\":\"vip\"}}"
                    : "{\"revision\":10,\"group\":{\"name\":\"vip\",\"weight\":50}}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            fourWrites.countDown();
        });
        server.start();

        executor = Executors.newSingleThreadExecutor();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(steveId);

        try (StonyPermsClient client = new StonyPermsClient(
                base, "read-token-0123456789", "write-token-0123456789", Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            DefaultStonyPermsBridgeAPI rwApi = new DefaultStonyPermsBridgeAPI(
                    new BridgeService(client, new SnapshotCache(60_000), new PermissionResolver(), true, true),
                    client, executor, true, "vault", ignored -> {}, () -> {});

            StonyPermsVaultPermission perms = new StonyPermsVaultPermission(
                    plugin, rwApi, name -> Optional.of(steveId), p -> Map.of(), w -> Map.of());
            StonyPermsVaultChat chat = new StonyPermsVaultChat(perms, rwApi, name -> Optional.of(steveId), w -> Map.of());

            assertTrue(perms.playerAdd("hub", offline, "vault.fly"));
            assertTrue(perms.playerAddGroup("hub", offline, "vip"));
            chat.setPlayerPrefix("hub", offline, "&a[VIP] ");
            chat.setPlayerInfoString("hub", offline, "badge", "star");

            assertTrue(fourWrites.await(5, TimeUnit.SECONDS));
            assertTrue(calls.contains("POST /v1/users/" + steveId + "/permissions"));
            assertTrue(calls.contains("POST /v1/users/" + steveId + "/parents"));
            assertTrue(calls.contains("POST /v1/users/" + steveId + "/prefixes"));
            assertTrue(calls.contains("PUT /v1/users/" + steveId + "/meta/badge"));
        }
    }
}
