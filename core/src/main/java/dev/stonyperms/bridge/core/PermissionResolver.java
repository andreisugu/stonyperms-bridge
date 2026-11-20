package dev.stonyperms.bridge.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reimplements the evaluation order of gate-stonyperms/engine on top of a
 * resolved snapshot:
 *
 * <ol>
 *   <li>user direct exact</li>
 *   <li>user wildcard (most specific)</li>
 *   <li>each inherited group in descending weight: exact, then wildcard</li>
 * </ol>
 *
 * A higher-weight group's wildcard therefore beats a lower-weight group's
 * exact node, exactly as on the proxy.
 */
public final class PermissionResolver {

    public Decision resolve(Snapshot snapshot, String permission, Map<String, String> contexts) {
        return resolve(snapshot, permission, contexts, Instant.now().getEpochSecond());
    }

    public Decision resolve(Snapshot snapshot, String permission, Map<String, String> contexts, long nowEpochSeconds) {
        if (snapshot == null) {
            return Decision.undefined("bridge:no-snapshot");
        }
        if (permission == null) {
            return Decision.undefined("default");
        }
        String target = permission.toLowerCase(Locale.ROOT).trim();
        if (target.isEmpty()) {
            return Decision.undefined("default");
        }

        for (Map.Entry<String, List<Node>> entry : sources(snapshot).entrySet()) {
            String source = entry.getKey();
            List<Node> nodes = entry.getValue();
            if (nodes.isEmpty()) {
                continue;
            }

            for (Node node : nodes) {
                if (!node.isWildcard() && node.active(nowEpochSeconds, contexts) && node.key().equals(target)) {
                    return new Decision(Tri.of(node.value()), source);
                }
            }

            Node best = null;
            for (Node node : nodes) {
                if (!node.isWildcard() || !node.active(nowEpochSeconds, contexts) || !node.matchesPermission(target)) {
                    continue;
                }
                if (best == null || node.key().length() > best.key().length()) {
                    best = node;
                }
            }
            if (best != null) {
                return new Decision(Tri.of(best.value()), source + ":wildcard(" + best.key() + ")");
            }
        }

        return Decision.undefined("default");
    }

    /**
     * Materializes every requested permission that has a defined value. This is
     * used by the Paper adapter, which must publish concrete Bukkit
     * permissions instead of relying on wildcard expansion.
     */
    public Map<String, Boolean> materialize(
            Snapshot snapshot, Collection<String> permissions, Map<String, String> contexts) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (snapshot == null || permissions == null) {
            return result;
        }
        long now = Instant.now().getEpochSecond();
        for (String permission : permissions) {
            if (permission == null) {
                continue;
            }
            String target = permission.toLowerCase(Locale.ROOT).trim();
            if (target.isEmpty() || result.containsKey(target)) {
                continue;
            }
            Decision decision = resolve(snapshot, target, contexts, now);
            if (decision.defined()) {
                result.put(target, decision.value() == Tri.TRUE);
            }
        }
        return result;
    }

    private Map<String, List<Node>> sources(Snapshot snapshot) {
        Map<String, List<Node>> sources = new LinkedHashMap<>();
        sources.put("user", new ArrayList<>());
        for (GroupRef group : snapshot.groups()) {
            sources.putIfAbsent("group:" + group.name(), new ArrayList<>());
        }
        for (Node node : snapshot.nodes()) {
            String source = node.user() ? "user" : node.source();
            sources.computeIfAbsent(source, ignored -> new ArrayList<>()).add(node);
        }
        return sources;
    }
}
