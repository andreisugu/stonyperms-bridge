package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Isolated registrar so StonyPermsBridgePlugin never imports Vault classes directly. */
final class VaultRegistrar {

    private VaultRegistrar() {
    }

    static Runnable register(
            Plugin plugin,
            StonyPermsBridgeAPI api,
            Function<Player, Map<String, String>> playerContexts,
            Function<String, Map<String, String>> worldContexts) {
        VaultPlayerLookup lookup = new VaultPlayerLookup() {
            @Override
            public Optional<UUID> findUuidByName(String name) {
                if (name == null || name.isBlank()) {
                    return Optional.empty();
                }
                Player exact = Bukkit.getPlayerExact(name);
                if (exact != null) {
                    return Optional.of(exact.getUniqueId());
                }
                try {
                    UUID parsed = UUID.fromString(name.trim());
                    return Optional.of(parsed);
                } catch (IllegalArgumentException ignored) {
                }
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                return cached == null ? Optional.empty() : Optional.of(cached.getUniqueId());
            }

            @Override
            public Optional<String> onlineWorld(UUID uuid) {
                Player online = Bukkit.getPlayer(uuid);
                return online == null ? Optional.empty() : Optional.of(online.getWorld().getName());
            }
        };

        StonyPermsVaultPermission perms = new StonyPermsVaultPermission(
                plugin, api, lookup, playerContexts, worldContexts);
        StonyPermsVaultChat chat = new StonyPermsVaultChat(
                perms, api, lookup, worldContexts);

        ServicesManager services = plugin.getServer().getServicesManager();
        services.register(Permission.class, perms, plugin, ServicePriority.High);
        services.register(Chat.class, chat, plugin, ServicePriority.High);

        api.fetchAllGroups().thenAccept(perms::updateGroupsCache).exceptionally(ignored -> null);
        AutoCloseable sub = api.onChangeEvent(event -> {
            String type = event.type();
            if ("group_updated".equals(type) || "group_deleted".equals(type) || "reload".equals(type)) {
                api.fetchAllGroups().thenAccept(perms::updateGroupsCache).exceptionally(ignored -> null);
            }
        });

        plugin.getLogger().info("Registered Vault Permission and Chat services");
        return () -> {
            try {
                sub.close();
            } catch (Exception ignored) {
            }
            try {
                services.unregister(Permission.class, perms);
                services.unregister(Chat.class, chat);
            } catch (RuntimeException | NoClassDefFoundError ignored) {
            }
        };
    }
}
