package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.DurationParser;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.Tri;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

/** PlaceholderAPI expansion exposing cached StonyPerms attributes, expiry times, and checks. */
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

        if (lower.equals("primary_group") || lower.equals("primary_group_name")) {
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
        if (lower.equals("groups") || lower.equals("inherited_groups")) {
            List<GroupRef> groups = api.getGroups(uuid, contexts);
            StringJoiner joiner = new StringJoiner(",");
            for (GroupRef ref : groups) {
                joiner.add(ref.name());
            }
            return joiner.toString();
        }
        if (lower.equals("highest_group_by_weight")) {
            return api.getGroups(uuid, contexts).stream()
                    .max(Comparator.comparingInt(GroupRef::weight))
                    .map(GroupRef::name)
                    .orElse("");
        }
        if (lower.equals("lowest_group_by_weight")) {
            return api.getGroups(uuid, contexts).stream()
                    .min(Comparator.comparingInt(GroupRef::weight))
                    .map(GroupRef::name)
                    .orElse("");
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
        if (lower.startsWith("inherits_group_")) {
            String group = params.substring("inherits_group_".length());
            return String.valueOf(!group.isEmpty() && api.inGroup(uuid, group, contexts));
        }
        if (lower.startsWith("has_permission_")) {
            String node = params.substring("has_permission_".length());
            if (node.isEmpty()) {
                return "false";
            }
            return String.valueOf(api.checkCached(uuid, node, contexts).value() == Tri.TRUE);
        }
        if (lower.startsWith("check_permission_")) {
            String node = params.substring("check_permission_".length());
            if (node.isEmpty()) {
                return "undefined";
            }
            return api.checkCached(uuid, node, contexts).value().name().toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("expiry_time_")) {
            String perm = params.substring("expiry_time_".length()).toLowerCase(Locale.ROOT).trim();
            return resolveExpiryTime(uuid, perm, contexts);
        }
        if (lower.startsWith("context_")) {
            String key = params.substring("context_".length()).trim();
            if (key.isEmpty()) {
                return "";
            }
            String exact = contexts.get(key);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, String> entry : contexts.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return "";
        }
        return null;
    }

    private String resolveExpiryTime(UUID uuid, String perm, Map<String, String> contexts) {
        if (perm.isEmpty()) {
            return "";
        }
        Optional<Snapshot> snapshotOpt = api.getCachedSnapshot(uuid, contexts);
        if (snapshotOpt.isEmpty()) {
            return "";
        }
        long nowEpoch = Instant.now().getEpochSecond();
        Node best = null;
        for (Node n : snapshotOpt.get().nodes()) {
            if (!n.active(nowEpoch, contexts) || !n.evaluateMatch(perm).matched()) {
                continue;
            }
            if (n.expiry() > nowEpoch && (best == null || n.key().equals(perm))) {
                best = n;
                if (n.user() && n.key().equals(perm)) {
                    break;
                }
            }
        }
        if (best == null || best.expiry() <= nowEpoch) {
            return "";
        }
        return DurationParser.formatDuration(best.expiry() - nowEpoch);
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
