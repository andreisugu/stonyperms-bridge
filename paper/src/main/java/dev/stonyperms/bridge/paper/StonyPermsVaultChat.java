package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.PriorityMeta;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vault {@link Chat} adapter backed by {@link StonyPermsBridgeAPI}.
 * Reads are non-blocking against cached snapshots; writes fail closed when write.enabled is false.
 */
public final class StonyPermsVaultChat extends Chat {

    private static final int DEFAULT_VAULT_AFFIX_PRIORITY = 100;

    private final StonyPermsVaultPermission perms;
    private final StonyPermsBridgeAPI api;
    private final VaultPlayerLookup playerLookup;
    private final Function<String, Map<String, String>> worldContexts;

    public StonyPermsVaultChat(
            StonyPermsVaultPermission perms,
            StonyPermsBridgeAPI api,
            VaultPlayerLookup playerLookup,
            Function<String, Map<String, String>> worldContexts) {
        super(perms);
        this.perms = Objects.requireNonNull(perms, "perms");
        this.api = Objects.requireNonNull(api, "api");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.worldContexts = Objects.requireNonNull(worldContexts, "worldContexts");
    }

    @Override
    public String getName() {
        return "StonyPermsBridge";
    }

    @Override
    public boolean isEnabled() {
        return perms.isEnabled();
    }

    // -------------------------------------------------------------------------
    // Player prefixes & suffixes
    // -------------------------------------------------------------------------

    @Override
    public String getPlayerPrefix(String world, String playerName) {
        if (playerName == null) {
            return "";
        }
        return playerLookup.findUuidByName(playerName)
                .flatMap(uuid -> api.getPrefix(uuid, resolveContexts(world, uuid)))
                .orElse("");
    }

    @Override
    public String getPlayerPrefix(String world, OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        return api.getPrefix(player.getUniqueId(), resolveContexts(world, player.getUniqueId())).orElse("");
    }

    @Override
    public void setPlayerPrefix(String world, String playerName, String prefix) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank()) {
            return;
        }
        setPlayerAffix(resolveTarget(playerName), true, prefix, worldContexts.apply(world));
    }

    @Override
    public void setPlayerPrefix(String world, OfflinePlayer player, String prefix) {
        if (!api.isWriteEnabled() || player == null) {
            return;
        }
        setPlayerAffix(player.getUniqueId().toString(), true, prefix, worldContexts.apply(world));
    }

    @Override
    public String getPlayerSuffix(String world, String playerName) {
        if (playerName == null) {
            return "";
        }
        return playerLookup.findUuidByName(playerName)
                .flatMap(uuid -> api.getSuffix(uuid, resolveContexts(world, uuid)))
                .orElse("");
    }

    @Override
    public String getPlayerSuffix(String world, OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        return api.getSuffix(player.getUniqueId(), resolveContexts(world, player.getUniqueId())).orElse("");
    }

    @Override
    public void setPlayerSuffix(String world, String playerName, String suffix) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank()) {
            return;
        }
        setPlayerAffix(resolveTarget(playerName), false, suffix, worldContexts.apply(world));
    }

    @Override
    public void setPlayerSuffix(String world, OfflinePlayer player, String suffix) {
        if (!api.isWriteEnabled() || player == null) {
            return;
        }
        setPlayerAffix(player.getUniqueId().toString(), false, suffix, worldContexts.apply(world));
    }

    // -------------------------------------------------------------------------
    // Group prefixes & suffixes
    // -------------------------------------------------------------------------

    @Override
    public String getGroupPrefix(String world, String group) {
        return perms.cachedGroup(group)
                .map(g -> highestAffix(g.prefixes(), worldContexts.apply(world)))
                .orElse("");
    }

    @Override
    public void setGroupPrefix(String world, String group, String prefix) {
        if (!api.isWriteEnabled() || group == null || group.isBlank()) {
            return;
        }
        if (prefix == null) {
            api.removeGroupPrefix(group, DEFAULT_VAULT_AFFIX_PRIORITY, api.defaultActor(), null);
        } else {
            api.setGroupPrefix(
                    group, DEFAULT_VAULT_AFFIX_PRIORITY, prefix, 0L, 0L, worldContexts.apply(world), api.defaultActor(), null);
        }
    }

    @Override
    public String getGroupSuffix(String world, String group) {
        return perms.cachedGroup(group)
                .map(g -> highestAffix(g.suffixes(), worldContexts.apply(world)))
                .orElse("");
    }

    @Override
    public void setGroupSuffix(String world, String group, String suffix) {
        if (!api.isWriteEnabled() || group == null || group.isBlank()) {
            return;
        }
        if (suffix == null) {
            api.removeGroupSuffix(group, DEFAULT_VAULT_AFFIX_PRIORITY, api.defaultActor(), null);
        } else {
            api.setGroupSuffix(
                    group, DEFAULT_VAULT_AFFIX_PRIORITY, suffix, 0L, 0L, worldContexts.apply(world), api.defaultActor(), null);
        }
    }

    // -------------------------------------------------------------------------
    // Player info metadata
    // -------------------------------------------------------------------------

    @Override
    public int getPlayerInfoInteger(String world, String playerName, String node, int defaultValue) {
        return parseInteger(getPlayerInfoString(world, playerName, node, null), defaultValue);
    }

    @Override
    public int getPlayerInfoInteger(String world, OfflinePlayer player, String node, int defaultValue) {
        return parseInteger(getPlayerInfoString(world, player, node, null), defaultValue);
    }

    @Override
    public void setPlayerInfoInteger(String world, String playerName, String node, int value) {
        setPlayerInfoString(world, playerName, node, Integer.toString(value));
    }

    @Override
    public void setPlayerInfoInteger(String world, OfflinePlayer player, String node, int value) {
        setPlayerInfoString(world, player, node, Integer.toString(value));
    }

    @Override
    public double getPlayerInfoDouble(String world, String playerName, String node, double defaultValue) {
        return parseDouble(getPlayerInfoString(world, playerName, node, null), defaultValue);
    }

    @Override
    public double getPlayerInfoDouble(String world, OfflinePlayer player, String node, double defaultValue) {
        return parseDouble(getPlayerInfoString(world, player, node, null), defaultValue);
    }

    @Override
    public void setPlayerInfoDouble(String world, String playerName, String node, double value) {
        setPlayerInfoString(world, playerName, node, Double.toString(value));
    }

    @Override
    public void setPlayerInfoDouble(String world, OfflinePlayer player, String node, double value) {
        setPlayerInfoString(world, player, node, Double.toString(value));
    }

    @Override
    public boolean getPlayerInfoBoolean(String world, String playerName, String node, boolean defaultValue) {
        return parseBoolean(getPlayerInfoString(world, playerName, node, null), defaultValue);
    }

    @Override
    public boolean getPlayerInfoBoolean(String world, OfflinePlayer player, String node, boolean defaultValue) {
        return parseBoolean(getPlayerInfoString(world, player, node, null), defaultValue);
    }

    @Override
    public void setPlayerInfoBoolean(String world, String playerName, String node, boolean value) {
        setPlayerInfoString(world, playerName, node, Boolean.toString(value));
    }

    @Override
    public void setPlayerInfoBoolean(String world, OfflinePlayer player, String node, boolean value) {
        setPlayerInfoString(world, player, node, Boolean.toString(value));
    }

    @Override
    public String getPlayerInfoString(String world, String playerName, String node, String defaultValue) {
        if (playerName == null || node == null) {
            return defaultValue;
        }
        return playerLookup.findUuidByName(playerName)
                .flatMap(uuid -> api.getMeta(uuid, node, resolveContexts(world, uuid)))
                .orElse(defaultValue);
    }

    @Override
    public String getPlayerInfoString(String world, OfflinePlayer player, String node, String defaultValue) {
        if (player == null || node == null) {
            return defaultValue;
        }
        return api.getMeta(player.getUniqueId(), node, resolveContexts(world, player.getUniqueId())).orElse(defaultValue);
    }

    @Override
    public void setPlayerInfoString(String world, String playerName, String node, String value) {
        if (!api.isWriteEnabled() || playerName == null || playerName.isBlank() || node == null || node.isBlank()) {
            return;
        }
        String target = resolveTarget(playerName);
        if (value == null) {
            api.unsetMeta(target, node);
        } else {
            api.setMeta(target, node, value);
        }
    }

    @Override
    public void setPlayerInfoString(String world, OfflinePlayer player, String node, String value) {
        if (!api.isWriteEnabled() || player == null || node == null || node.isBlank()) {
            return;
        }
        String target = player.getUniqueId().toString();
        if (value == null) {
            api.unsetMeta(target, node);
        } else {
            api.setMeta(target, node, value);
        }
    }

    // -------------------------------------------------------------------------
    // Group info metadata
    // -------------------------------------------------------------------------

    @Override
    public int getGroupInfoInteger(String world, String group, String node, int defaultValue) {
        return parseInteger(getGroupInfoString(world, group, node, null), defaultValue);
    }

    @Override
    public void setGroupInfoInteger(String world, String group, String node, int value) {
        setGroupInfoString(world, group, node, Integer.toString(value));
    }

    @Override
    public double getGroupInfoDouble(String world, String group, String node, double defaultValue) {
        return parseDouble(getGroupInfoString(world, group, node, null), defaultValue);
    }

    @Override
    public void setGroupInfoDouble(String world, String group, String node, double value) {
        setGroupInfoString(world, group, node, Double.toString(value));
    }

    @Override
    public boolean getGroupInfoBoolean(String world, String group, String node, boolean defaultValue) {
        return parseBoolean(getGroupInfoString(world, group, node, null), defaultValue);
    }

    @Override
    public void setGroupInfoBoolean(String world, String group, String node, boolean value) {
        setGroupInfoString(world, group, node, Boolean.toString(value));
    }

    @Override
    public String getGroupInfoString(String world, String group, String node, String defaultValue) {
        if (group == null || node == null) {
            return defaultValue;
        }
        Optional<GroupRecord> rec = perms.cachedGroup(group);
        if (rec.isEmpty()) {
            return defaultValue;
        }
        String val = rec.get().meta().get(node);
        return val == null || val.isEmpty() ? defaultValue : val;
    }

    @Override
    public void setGroupInfoString(String world, String group, String node, String value) {
        if (!api.isWriteEnabled() || group == null || group.isBlank() || node == null || node.isBlank()) {
            return;
        }
        if (value == null) {
            api.unsetGroupMeta(group, node, api.defaultActor(), null);
        } else {
            api.setGroupMeta(group, node, value, api.defaultActor(), null);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setPlayerAffix(String target, boolean isPrefix, String value, Map<String, String> contexts) {
        if (value == null) {
            if (isPrefix) {
                api.removePrefix(target, DEFAULT_VAULT_AFFIX_PRIORITY);
            } else {
                api.removeSuffix(target, DEFAULT_VAULT_AFFIX_PRIORITY);
            }
        } else {
            if (isPrefix) {
                api.setPrefix(target, DEFAULT_VAULT_AFFIX_PRIORITY, value, 0L, contexts);
            } else {
                api.setSuffix(target, DEFAULT_VAULT_AFFIX_PRIORITY, value, 0L, contexts);
            }
        }
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

    private static String highestAffix(List<PriorityMeta> affixes, Map<String, String> query) {
        long now = Instant.now().getEpochSecond();
        return affixes.stream()
                .filter(pm -> pm.expiry() <= 0 || now < pm.expiry())
                .filter(pm -> matchesContext(pm.contexts(), query))
                .max(Comparator.comparingInt(PriorityMeta::priority))
                .map(PriorityMeta::value)
                .orElse("");
    }

    private static boolean matchesContext(Map<String, String> required, Map<String, String> query) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (query == null || query.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            String actual = query.get(entry.getKey());
            if (actual == null || !actual.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static int parseInteger(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if (raw.trim().equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.trim().equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }
}
