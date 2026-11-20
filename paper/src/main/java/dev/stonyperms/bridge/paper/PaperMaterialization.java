package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;
import org.bukkit.permissions.Permission;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PaperMaterialization {
    private PaperMaterialization() {
    }

    /**
     * Bukkit has no on-demand permission hook, so the adapter materializes the
     * known universe plus every literal node in the snapshot. Wildcard nodes
     * are resolved against that universe by PermissionResolver.
     */
    public static Set<String> permissions(Snapshot snapshot, Set<String> universe) {
        Set<String> permissions = new HashSet<>(universe);
        for (Node node : snapshot.nodes()) {
            if (!node.isWildcard()) {
                permissions.add(node.key());
            }
        }
        return permissions;
    }

    /**
     * Bukkit expands a registered permission into its declared children during
     * recalculation, and the overwrite order is not guaranteed. If an explicit
     * StonyPerms value disagrees with a parent's declared child value, drop the
     * parent from the attachment so the explicit child survives.
     */
    public static Map<String, Boolean> withoutConflictingParents(
            Map<String, Boolean> values, Map<String, Permission> registered) {
        Map<String, Boolean> result = new LinkedHashMap<>(values);
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            Permission parent = registered.get(entry.getKey());
            if (parent == null || parent.getChildren().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Boolean> child : parent.getChildren().entrySet()) {
                Boolean explicit = values.get(child.getKey().toLowerCase(Locale.ROOT));
                if (explicit == null || explicit != child.getValue()) {
                    result.remove(entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    /** Explicit denials for the fail-closed unavailable-snapshot path. */
    public static Map<String, Boolean> failClosedValues(Set<String> permissions) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String permission : permissions) {
            if (!"stonyperms.bridge.admin".equals(permission)) {
                values.put(permission, false);
            }
        }
        return values;
    }
}
