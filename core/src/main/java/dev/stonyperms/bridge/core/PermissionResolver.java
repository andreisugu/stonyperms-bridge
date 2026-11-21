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
 *   <li>user nodes: exact > wildcard/pattern, breaking ties by context specificity</li>
 *   <li>each inherited group in descending weight: exact > wildcard/pattern, context specificity</li>
 * </ol>
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

            Node bestNode = null;
            Node.MatchResult bestMatch = null;
            for (Node node : nodes) {
                if (!node.active(nowEpochSeconds, contexts)) {
                    continue;
                }
                Node.MatchResult mr = node.evaluateMatch(target);
                if (!mr.matched()) {
                    continue;
                }
                if (bestNode == null || isBetterMatch(node, mr, bestNode, bestMatch)) {
                    bestNode = node;
                    bestMatch = mr;
                }
            }
            if (bestNode != null) {
                String src = bestMatch.exact() ? source : source + ":wildcard(" + bestNode.key() + ")";
                return new Decision(Tri.of(bestNode.value()), src);
            }
        }

        return Decision.undefined("default");
    }

    private static boolean isBetterMatch(
            Node candidate, Node.MatchResult candMr, Node current, Node.MatchResult curMr) {
        if (candMr.exact() != curMr.exact()) {
            return candMr.exact();
        }
        int candCtx = candidate.contexts().size();
        int curCtx = current.contexts().size();
        if (candMr.exact()) {
            if (candCtx != curCtx) {
                return candCtx > curCtx;
            }
            if (candMr.specificityScore() != curMr.specificityScore()) {
                return candMr.specificityScore() > curMr.specificityScore();
            }
            return !candidate.value() && current.value();
        }
        if (candMr.specificityScore() != curMr.specificityScore()) {
            return candMr.specificityScore() > curMr.specificityScore();
        }
        if (candCtx != curCtx) {
            return candCtx > curCtx;
        }
        return !candidate.value() && current.value();
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
