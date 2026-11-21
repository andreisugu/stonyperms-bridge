package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.Tri;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Vault {@link Permission} adapter backed by {@link StonyPermsBridgeAPI}.
 * Reads are non-blocking against cached snapshots; writes fail closed when write.enabled is false.
 */
public final class StonyPermsVaultPermission extends Permission {

    private final StonyPermsBridgeAPI api;
    private final VaultPlayerLookup playerLookup;
    private final Function<Player, Map<String, String>> playerContexts;
    private final Function<String, Map<String, String>> worldContexts;
    private final Map<String, GroupRecord> cachedGroups = new ConcurrentHashMap<>();

    public StonyPermsVaultPermission(
            Plugin plugin,
            StonyPermsBridgeAPI api,
            VaultPlayerLookup playerLookup,
            Function<Player, Map<String, String>> playerContexts,
            Function<String, Map<String, String>> worldContexts) {
        this.plugin = plugin;
        this.api = Objects.requireNonNull(api, "api");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.playerContexts = Objects.requireNonNull(playerContexts, "playerContexts");
        this.worldContexts = Objects.requireNonNull(worldContexts, "worldContexts");
    }

    public void updateGroupsCache(List<GroupRecord> groups) {
        if (groups == null) {
            return;
        }
        Map<String, GroupRecord> fresh = new LinkedHashMap<>();
        for (GroupRecord g : groups) {
            if (!g.name().isEmpty()) {
                fresh.put(g.name().toLowerCase(Locale.ROOT), g);
            }
        }
        cachedGroups.keySet().retainAll(fresh.keySet());
        cachedGroups.putAll(fresh);
    }

    Optional<GroupRecord> cachedGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cachedGroups.get(groupName.toLowerCase(Locale.ROOT)));
    }

    @Override
    public String getName() {
        return "StonyPermsBridge";
    }

    @Override
    public boolean isEnabled() {
        return plugin == null || plugin.isEnabled();
    }

    @Override
    public boolean hasSuperPermsCompat() {
        return true;
    }

    @Override
    public boolean hasGroupSupport() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Player permission checks (non-blocking)
    // -------------------------------------------------------------------------

    @Override
    public boolean playerHas(String world, String playerName, String permission) {
        if (playerName == null || permission == null) {
            return false;
        }
        return playerLookup.findUuidByName(playerName)
                .map(uuid -> api.checkCached(uuid, permission, resolveContexts(world, uuid)).value() == Tri.TRUE)
                .orElse(false);
    }

    @Override
    public boolean playerHas(String world, OfflinePlayer player, String permission) {
        if (player == null || permission == null) {
            return false;
        }
        return api.checkCached(player.getUniqueId(), permission, resolveContexts(world, player)).value() == Tri.TRUE;
    }

    @Override
    public boolean playerHas(Player player, String permission) {
        if (player == null || permission == null) {
            return false;
        }
        return api.checkCached(player.getUniqueId(), permission, playerContexts.apply(player)).value() == Tri.TRUE;
    }

    // -------------------------------------------------------------------------
    // Player permission mutations (fail closed when !api.isWriteEnabled())
    // -------------------------------------------------------------------------

    @Override
    public boolean playerAdd(String world, String playerName, String permission) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank() || permission == null || permission.isBlank()) {
            return false;
        }
        String target = resolveTarget(playerName);
        api.setPermission(target, permission, true, 0L, worldContexts.apply(world));
        return true;
    }

    @Override
    public boolean playerAdd(String world, OfflinePlayer player, String permission) {
        if (!api.isWriteEnabled() || player == null || permission == null || permission.isBlank()) {
            return false;
        }
        api.setPermission(player.getUniqueId().toString(), permission, true, 0L, worldContexts.apply(world));
        return true;
    }

    @Override
    public boolean playerRemove(String world, String playerName, String permission) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank() || permission == null || permission.isBlank()) {
            return false;
        }
        String target = resolveTarget(playerName);
        api.unsetPermission(target, permission, worldContexts.apply(world));
        return true;
    }

    @Override
    public boolean playerRemove(String world, OfflinePlayer player, String permission) {
        if (!api.isWriteEnabled() || player == null || permission == null || permission.isBlank()) {
            return false;
        }
        api.unsetPermission(player.getUniqueId().toString(), permission, worldContexts.apply(world));
        return true;
    }

    @Override
    public boolean playerAddTransient(OfflinePlayer player, String permission) {
        return false;
    }

    @Override
    public boolean playerAddTransient(String world, OfflinePlayer player, String permission) {
        return false;
    }

    @Override
    public boolean playerRemoveTransient(OfflinePlayer player, String permission) {
        return false;
    }

    @Override
    public boolean playerRemoveTransient(String world, OfflinePlayer player, String permission) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Group permission checks & mutations
    // -------------------------------------------------------------------------

    @Override
    public boolean groupHas(String world, String group, String permission) {
        if (group == null || permission == null) {
            return false;
        }
        GroupRecord record = cachedGroups.get(group.toLowerCase(Locale.ROOT));
        if (record == null) {
            return false;
        }
        String target = permission.toLowerCase(Locale.ROOT).trim();
        Map<String, String> ctx = worldContexts.apply(world);
        long now = Instant.now().getEpochSecond();

        for (Node n : record.nodes()) {
            if (!n.isWildcard() && n.active(now, ctx) && n.key().equals(target)) {
                return n.value();
            }
        }
        Node best = null;
        for (Node n : record.nodes()) {
            if (n.isWildcard() && n.active(now, ctx) && n.matchesPermission(target)) {
                if (best == null || n.key().length() > best.key().length()) {
                    best = n;
                }
            }
        }
        return best != null && best.value();
    }

    @Override
    public boolean groupAdd(String world, String group, String permission) {
        if (!api.isWriteEnabled() || group == null || group.isBlank() || permission == null || permission.isBlank()) {
            return false;
        }
        api.setGroupPermission(
                group, permission, true, 0L, 0L, worldContexts.apply(world), api.defaultActor(), null);
        return true;
    }

    @Override
    public boolean groupRemove(String world, String group, String permission) {
        if (!api.isWriteEnabled() || group == null || group.isBlank() || permission == null || permission.isBlank()) {
            return false;
        }
        api.unsetGroupPermission(group, permission, worldContexts.apply(world), api.defaultActor(), null);
        return true;
    }

    // -------------------------------------------------------------------------
    // Membership checks & mutations
    // -------------------------------------------------------------------------

    @Override
    public boolean playerInGroup(String world, String playerName, String group) {
        if (playerName == null || group == null) {
            return false;
        }
        return playerLookup.findUuidByName(playerName)
                .map(uuid -> api.inGroup(uuid, group, resolveContexts(world, uuid)))
                .orElse(false);
    }

    @Override
    public boolean playerInGroup(String world, OfflinePlayer player, String group) {
        if (player == null || group == null) {
            return false;
        }
        return api.inGroup(player.getUniqueId(), group, resolveContexts(world, player));
    }

    @Override
    public boolean playerAddGroup(String world, String playerName, String group) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank() || group == null || group.isBlank()) {
            return false;
        }
        api.addParent(resolveTarget(playerName), group, ParentMode.ADD, 0L, worldContexts.apply(world), false);
        return true;
    }

    @Override
    public boolean playerAddGroup(String world, OfflinePlayer player, String group) {
        if (!api.isWriteEnabled() || player == null || group == null || group.isBlank()) {
            return false;
        }
        api.addParent(player.getUniqueId().toString(), group, ParentMode.ADD, 0L, worldContexts.apply(world), false);
        return true;
    }

    @Override
    public boolean playerRemoveGroup(String world, String playerName, String group) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank() || group == null || group.isBlank()) {
            return false;
        }
        api.removeParent(resolveTarget(playerName), group, worldContexts.apply(world));
        return true;
    }

    @Override
    public boolean playerRemoveGroup(String world, OfflinePlayer player, String group) {
        if (!api.isWriteEnabled() || player == null || group == null || group.isBlank()) {
            return false;
        }
        api.removeParent(player.getUniqueId().toString(), group, worldContexts.apply(world));
        return true;
    }

    @Override
    public String[] getPlayerGroups(String world, String playerName) {
        if (playerName == null) {
            return new String[0];
        }
        return playerLookup.findUuidByName(playerName)
                .map(uuid -> api.getGroups(uuid, resolveContexts(world, uuid)).stream()
                        .map(GroupRef::name)
                        .toArray(String[]::new))
                .orElseGet(() -> new String[0]);
    }

    @Override
    public String[] getPlayerGroups(String world, OfflinePlayer player) {
        if (player == null) {
            return new String[0];
        }
        return api.getGroups(player.getUniqueId(), resolveContexts(world, player)).stream()
                .map(GroupRef::name)
                .toArray(String[]::new);
    }

    @Override
    public String getPrimaryGroup(String world, String playerName) {
        if (playerName == null) {
            return "default";
        }
        return playerLookup.findUuidByName(playerName)
                .map(uuid -> api.getPrimaryGroup(uuid, resolveContexts(world, uuid)))
                .orElse("default");
    }

    @Override
    public String getPrimaryGroup(String world, OfflinePlayer player) {
        if (player == null) {
            return "default";
        }
        return api.getPrimaryGroup(player.getUniqueId(), resolveContexts(world, player));
    }

    @Override
    public String[] getGroups() {
        return cachedGroups.keySet().toArray(String[]::new);
    }

    private Map<String, String> resolveContexts(String world, OfflinePlayer player) {
        if ((world == null || world.isBlank()) && player instanceof Player online) {
            return playerContexts.apply(online);
        }
        return resolveContexts(world, player.getUniqueId());
    }

    private Map<String, String> resolveContexts(String world, UUID uuid) {
        if (world != null && !world.isBlank()) {
            return worldContexts.apply(world);
        }
        return playerLookup.onlineWorld(uuid)
                .map(worldContexts)
                .orElseGet(() -> worldContexts.apply(null));
    }

    private String resolveTarget(String playerName) {
        return playerLookup.findUuidByName(playerName)
                .map(UUID::toString)
                .orElse(playerName);
    }
}
