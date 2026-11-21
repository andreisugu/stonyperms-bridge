package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Tri;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

/** PlaceholderAPI expansion exposing cached StonyPerms attributes and checks. */
public final class StonyPermsPlaceholderExpansion extends PlaceholderExpansion {

    private final StonyPermsBridgeAPI api;
    private final String version;
    private final String requiredPlugin;
    private final Function<Player, Map<String, String>> playerContexts;
    private final Map<String, String> defaultContexts;

    public StonyPermsPlaceholderExpansion(
            StonyPermsBridgeAPI api,
            String version,
            String requiredPlugin,
            Function<Player, Map<String, String>> playerContexts,
            Map<String, String> defaultContexts) {
        this.api = Objects.requireNonNull(api, "api");
        this.version = version == null ? "0.2.0" : version;
        this.requiredPlugin = requiredPlugin;
        this.playerContexts = Objects.requireNonNull(playerContexts, "playerContexts");
        this.defaultContexts = defaultContexts == null ? Map.of() : Map.copyOf(defaultContexts);
    }

    @Override
    public String getIdentifier() {
        return "stonyperms";
    }

    @Override
    public String getAuthor() {
        return "andreisugu";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getRequiredPlugin() {
        return requiredPlugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) {
            return "";
        }
        UUID uuid = player.getUniqueId();
        Map<String, String> contexts = resolveContexts(player);
        String lower = params.toLowerCase(Locale.ROOT);

        if (lower.equals("primary_group")) {
            return api.getPrimaryGroup(uuid, contexts);
        }
        if (lower.equals("primary_group_display")) {
            String primary = api.getPrimaryGroup(uuid, contexts);
            for (GroupRef ref : api.getGroups(uuid, contexts)) {
                if (ref.name().equalsIgnoreCase(primary)) {
                    return ref.displayName();
                }
            }
            return primary;
        }
        if (lower.equals("prefix")) {
            return api.getPrefix(uuid, contexts).orElse("");
        }
        if (lower.equals("suffix")) {
            return api.getSuffix(uuid, contexts).orElse("");
        }
        if (lower.equals("groups")) {
            List<GroupRef> groups = api.getGroups(uuid, contexts);
            StringJoiner joiner = new StringJoiner(",");
            for (GroupRef ref : groups) {
                joiner.add(ref.name());
            }
            return joiner.toString();
        }
        if (lower.equals("weight")) {
            int max = 0;
            for (GroupRef ref : api.getGroups(uuid, contexts)) {
                if (ref.weight() > max) {
                    max = ref.weight();
                }
            }
            return String.valueOf(max);
        }
        if (lower.startsWith("meta_")) {
            String key = params.substring("meta_".length());
            return key.isEmpty() ? "" : api.getMeta(uuid, key, contexts).orElse("");
        }
        if (lower.startsWith("in_group_")) {
            String group = params.substring("in_group_".length());
            return String.valueOf(!group.isEmpty() && api.inGroup(uuid, group, contexts));
        }
        if (lower.startsWith("has_permission_")) {
            String node = params.substring("has_permission_".length());
            if (node.isEmpty()) {
                return "false";
            }
            return String.valueOf(api.checkCached(uuid, node, contexts).value() == Tri.TRUE);
        }
        return null;
    }

    private Map<String, String> resolveContexts(OfflinePlayer player) {
        if (player.isOnline()) {
            Player online = player.getPlayer();
            if (online != null) {
                return playerContexts.apply(online);
            }
        }
        return defaultContexts;
    }
}
