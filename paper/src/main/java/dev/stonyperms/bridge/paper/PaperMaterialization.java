package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;

import java.util.HashSet;
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
}
