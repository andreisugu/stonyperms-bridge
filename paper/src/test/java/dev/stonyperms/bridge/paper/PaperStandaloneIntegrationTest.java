package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.local.LocalStonyPermsBackend;
import dev.stonyperms.bridge.core.local.StandaloneConfig;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperStandaloneIntegrationTest {

    @Test
    void standaloneBackendDrivesMaterializationApplierVaultAndPlaceholders(@TempDir Path tempDir) throws Exception {
        StandaloneConfig cfg = StandaloneConfig.defaults(tempDir);
        try (LocalStonyPermsBackend backend = LocalStonyPermsBackend.create(cfg, null)) {
            SnapshotCache cache = new SnapshotCache(60_000L);
            BridgeService service = new BridgeService(backend, cache, new PermissionResolver(), true, true);
            service.setVerboseObserver((uuid, perm, dec) ->
                    backend.recordResolvedCheck(uuid, perm, dec.value().name().toLowerCase(Locale.ROOT), dec.source()));
            DefaultStonyPermsBridgeAPI api = new DefaultStonyPermsBridgeAPI(
                    service, backend, Runnable::run, true, "paper", ignored -> {}, () -> {});

            api.createGroup("vip", 50, "&aVIP", "console", null).join();
            api.setGroupPermission("vip", "demo.fly", true, 0L, 0L, Map.of(), "console", null).join();
            api.setGroupPermission("vip", "demo.ban", false, 0L, 0L, Map.of(), "console", null).join();
            api.setGroupPrefix("vip", 50, "&a[VIP] ", 0L, 0L, Map.of(), "console", null).join();
            api.setGroupMeta("vip", "homes", "5", "console", null).join();

            UUID id = UUID.randomUUID();
            backend.ensureOnlineUser(id, "Alex", "console");
            api.addParent(id.toString(), "vip", ParentMode.ADD, 0L, Map.of(), true).join();

            Map<String, String> ctx = Map.of("world", "world");
            service.refresh(id, ctx);
            Snapshot snap = api.getCachedSnapshot(id, ctx).orElseThrow();

            Plugin plugin = mock(Plugin.class);
            Player player = mock(Player.class);
            PermissionAttachment attachment = mock(PermissionAttachment.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn("Alex");
            when(player.isOnline()).thenReturn(true);
            when(player.getPlayer()).thenReturn(player);
            when(player.addAttachment(plugin)).thenReturn(attachment);

            Set<String> perms = PaperMaterialization.permissions(snap, Set.of("demo.fly", "demo.ban", "demo.neutral"));
            Map<String, Boolean> mat = service.materialize(id, ctx, perms).orElseThrow();
            PaperPermissionApplier applier = new PaperPermissionApplier(plugin);
            applier.apply(player, mat);
            verify(attachment).setPermission("demo.fly", true);
            verify(attachment).setPermission("demo.ban", false);
            verify(attachment, never()).setPermission(eq("demo.neutral"), anyBoolean());

            VaultPlayerLookup lookup = name -> "Alex".equalsIgnoreCase(name) ? Optional.of(id) : Optional.empty();
            StonyPermsVaultPermission vaultPerm = new StonyPermsVaultPermission(
                    plugin, api, lookup, p -> ctx, w -> (w == null || w.isBlank()) ? Map.of() : Map.of("world", w));
            vaultPerm.updateGroupsCache(api.fetchAllGroups().join());
            Chat vaultChat = new StonyPermsVaultChat(vaultPerm, api, lookup, w -> (w == null || w.isBlank()) ? Map.of() : Map.of("world", w));

            OfflinePlayer off = mock(OfflinePlayer.class);
            when(off.getUniqueId()).thenReturn(id);
            when(off.getName()).thenReturn("Alex");
            assertEquals("vip", vaultPerm.getPrimaryGroup("world", off));
            assertTrue(vaultPerm.playerHas(player, "demo.fly"));
            assertTrue(vaultPerm.playerHas("world", off, "demo.fly"));
            assertEquals("&a[VIP] ", vaultChat.getPlayerPrefix("world", off));
            assertEquals("5", vaultChat.getPlayerInfoString("world", off, "homes", "0"));

            StonyPermsPlaceholderExpansion papi = new StonyPermsPlaceholderExpansion(
                    api, "0.2.0", "StonyPermsBridge", p -> ctx, Map.of());
            assertEquals("vip", papi.onRequest(player, "primary_group"));
            assertEquals("5", papi.onRequest(player, "meta_homes"));
            assertEquals("true", papi.onRequest(player, "has_permission_demo.fly"));
            assertTrue(api.isStandalone());
            assertEquals(List.of("vip"), List.of(vaultPerm.getPrimaryGroup("world", off)));
        }
    }
}
