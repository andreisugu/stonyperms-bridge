package dev.stonyperms.bridge.paper;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one PermissionAttachment per player. Materialized concrete values are
 * used instead of wildcard strings because Bukkit does not expand wildcards.
 */
public final class PaperPermissionApplier {

    private final Plugin plugin;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public PaperPermissionApplier(Plugin plugin) {
        this.plugin = plugin;
    }

    public void apply(Player player, Map<String, Boolean> values) {
        clear(player);
        if (values == null || values.isEmpty()) {
            player.recalculatePermissions();
            return;
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        if (attachment == null) {
            return;
        }
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            attachment.setPermission(key, entry.getValue());
        }
        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }

    public void clear(Player player) {
        PermissionAttachment old = attachments.remove(player.getUniqueId());
        if (old == null) {
            return;
        }
        try {
            old.remove();
        } catch (RuntimeException ignored) {
            // The attachment may already have been removed by the server.
        }
        player.recalculatePermissions();
    }

    public void clearAll() {
        attachments.clear();
    }

    public int size() {
        return attachments.size();
    }
}
