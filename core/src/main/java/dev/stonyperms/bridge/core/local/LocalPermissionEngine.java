package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.Tri;
import dev.stonyperms.bridge.core.UserRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Authoritative Java permission calculation engine matching gate-stonyperms/engine
 * and the canonical StonyPerms specification.
 */
public final class LocalPermissionEngine {

    public static final int MAX_VERBOSE_ENTRIES = 2000;

    public record EngineCheckResult(Tri result, String source, Node node) {
    }

    public record VerboseEntry(String subject, String permission, String result, String source, long timestamp) {
    }

    public record VerboseRecording(
            boolean active,
            String actor,
            String targetFilter,
            String queryFilter,
            long startTime,
            long endTime,
            int totalCount,
            boolean truncated,
            List<VerboseEntry> entries) {
    }

    private final Object cfgLock = new Object();
    private volatile StandaloneConfig.StackConfig stackCfg;
    private volatile String primaryCalc;

    private final Object recLock = new Object();
    private volatile boolean recordActive = false;
    private String recordActor = "";
    private String recordTargetFilter = "";
    private String recordQueryFilter = "";
    private long recordStart = 0L;
    private long recordEnd = 0L;
    private int recordTotal = 0;
    private boolean recordTruncated = false;
    private final List<VerboseEntry> recordEntries = new ArrayList<>();

    public LocalPermissionEngine() {
        this.stackCfg = StandaloneConfig.StackConfig.defaults();
        this.primaryCalc = "stored";
    }

    public void setPrimaryGroupCalculation(String mode) {
        String clean = mode == null ? "stored" : mode.trim().toLowerCase(Locale.ROOT);
        switch (clean) {
            case "parents-by-weight", "all-parents-by-weight" -> {
            }
            default -> clean = "stored";
        }
        synchronized (cfgLock) {
            this.primaryCalc = clean;
        }
    }

    public String getPrimaryGroupCalculation() {
        String c = this.primaryCalc;
        return (c == null || c.isEmpty()) ? "stored" : c;
    }

    public void setStackConfig(StandaloneConfig.StackConfig cfg) {
        synchronized (cfgLock) {
            this.stackCfg = cfg == null ? StandaloneConfig.StackConfig.defaults() : cfg;
        }
    }

    public StandaloneConfig.StackConfig getStackConfig() {
        return stackCfg;
    }

    // -------------------------------------------------------------------------
    // Verbose check recording
    // -------------------------------------------------------------------------

    public void startVerboseRecording(String actor, String targetFilter, String queryFilter) {
        long nowMs = System.currentTimeMillis();
        synchronized (recLock) {
            recordActive = true;
            recordActor = actor == null ? "" : actor.trim();
            recordTargetFilter = targetFilter == null ? "" : targetFilter.trim();
            recordQueryFilter = queryFilter == null ? "" : queryFilter.trim();
            recordStart = nowMs;
            recordEnd = nowMs;
            recordTotal = 0;
            recordTruncated = false;
            recordEntries.clear();
        }
    }

    public VerboseRecording stopVerboseRecording() {
        long nowMs = System.currentTimeMillis();
        synchronized (recLock) {
            if (recordActive) {
                recordActive = false;
                if (nowMs > recordEnd) {
                    recordEnd = nowMs;
                }
            }
            return snapshotRecordingLocked();
        }
    }

    public VerboseRecording snapshotVerboseRecording() {
        synchronized (recLock) {
            return snapshotRecordingLocked();
        }
    }

    public boolean isVerboseRecording() {
        return recordActive;
    }

    public void recordCheck(String subject, String permission, Tri result, String source) {
        if (!recordActive) {
            return;
        }
        recordCheckAt(subject, permission, result, source, System.currentTimeMillis());
    }

    private void recordCheckAt(String subject, String permission, Tri result, String source, long nowMs) {
        String safeSubject = subject == null ? "" : subject;
        String safePerm = permission == null ? "" : permission;
        synchronized (recLock) {
            if (!recordActive || !matchesFilter(recordTargetFilter, recordQueryFilter, safeSubject, safePerm)) {
                return;
            }
            recordTotal++;
            if (nowMs > recordEnd) {
                recordEnd = nowMs;
            }
            if (recordEntries.size() < MAX_VERBOSE_ENTRIES) {
                recordEntries.add(new VerboseEntry(
                        safeSubject,
                        safePerm,
                        (result == null ? Tri.UNDEFINED : result).wireName(),
                        source == null ? "" : source,
                        nowMs));
            } else {
                recordTruncated = true;
            }
        }
    }

    private VerboseRecording snapshotRecordingLocked() {
        return new VerboseRecording(
                recordActive,
                recordActor,
                recordTargetFilter,
                recordQueryFilter,
                recordStart,
                recordEnd,
                recordTotal,
                recordTruncated,
                List.copyOf(recordEntries));
    }

    private static boolean matchesFilter(String target, String query, String subject, String perm) {
        if (target != null && !target.isEmpty() && !target.equalsIgnoreCase(subject)) {
            return false;
        }
        if (query != null && !query.isEmpty()) {
            Node probe = new Node(query, true, 0L, Map.of());
            if (!probe.matchesPermission(perm)
                    && !perm.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Permission Evaluation
    // -------------------------------------------------------------------------

    public Tri permissionValue(
            UserRecord user, Map<String, GroupRecord> groups, String targetPerm, Map<String, String> contexts) {
        return permissionValueAt(user, groups, targetPerm, contexts, Instant.now().getEpochSecond());
    }

    public Tri permissionValueAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            String targetPerm,
            Map<String, String> contexts,
            long nowEpochSeconds) {
        EngineCheckResult res = evaluateAt(user, groups, targetPerm, contexts, nowEpochSeconds);
        if (recordActive) {
            String subject = user == null ? "" : user.username();
            recordCheckAt(subject, targetPerm, res.result(), res.source(), nowEpochSeconds * 1000L);
        }
        return res.result();
    }

    public EngineCheckResult evaluate(
            UserRecord user, Map<String, GroupRecord> groups, String targetPerm, Map<String, String> contexts) {
        return evaluateAt(user, groups, targetPerm, contexts, Instant.now().getEpochSecond());
    }

    public EngineCheckResult evaluateAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            String targetPerm,
            Map<String, String> contexts,
            long nowEpochSeconds) {
        if (user == null) {
            return new EngineCheckResult(Tri.UNDEFINED, "none (nil user)", null);
        }
        String normPerm = targetPerm == null ? "" : targetPerm.trim().toLowerCase(Locale.ROOT);
        SelectedNode direct = selectBestNode(user.nodes(), normPerm, contexts, nowEpochSeconds);
        if (direct != null) {
            Tri val = direct.node.value() ? Tri.TRUE : Tri.FALSE;
            String src = direct.exact ? "user:direct" : "user:wildcard (" + direct.node.key() + ")";
            return new EngineCheckResult(val, src, direct.node);
        }

        List<GroupRecord> resolved = resolveInheritedGroups(user, groups, contexts, nowEpochSeconds);
        for (GroupRecord g : resolved) {
            SelectedNode grpBest = selectBestNode(g.nodes(), normPerm, contexts, nowEpochSeconds);
            if (grpBest != null) {
                Tri val = grpBest.node.value() ? Tri.TRUE : Tri.FALSE;
                String src = grpBest.exact
                        ? "group:" + g.name()
                        : "group:" + g.name() + ":wildcard (" + grpBest.node.key() + ")";
                return new EngineCheckResult(val, src, grpBest.node);
            }
        }
        return new EngineCheckResult(Tri.UNDEFINED, "default (undefined)", null);
    }

    public EngineCheckResult groupPermissionValue(
            GroupRecord group, Map<String, GroupRecord> groups, String targetPerm, Map<String, String> contexts) {
        return groupPermissionValueAt(group, groups, targetPerm, contexts, Instant.now().getEpochSecond());
    }

    public EngineCheckResult groupPermissionValueAt(
            GroupRecord group,
            Map<String, GroupRecord> groups,
            String targetPerm,
            Map<String, String> contexts,
            long nowEpochSeconds) {
        if (group == null) {
            return new EngineCheckResult(Tri.UNDEFINED, "none (nil group)", null);
        }
        String normPerm = targetPerm == null ? "" : targetPerm.trim().toLowerCase(Locale.ROOT);
        List<GroupRecord> resolved = resolveGroupHierarchy(group, groups, contexts, nowEpochSeconds);
        for (GroupRecord g : resolved) {
            SelectedNode best = selectBestNode(g.nodes(), normPerm, contexts, nowEpochSeconds);
            if (best != null) {
                Tri val = best.node.value() ? Tri.TRUE : Tri.FALSE;
                String src = best.exact
                        ? "group:" + g.name()
                        : "group:" + g.name() + ":wildcard (" + best.node.key() + ")";
                return new EngineCheckResult(val, src, best.node);
            }
        }
        return new EngineCheckResult(Tri.UNDEFINED, "default (undefined)", null);
    }

    private record SelectedNode(Node node, boolean exact, int spec) {
    }

    private static SelectedNode selectBestNode(
            List<Node> nodes, String targetPerm, Map<String, String> contexts, long nowEpochSeconds) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        Node best = null;
        boolean bestExact = false;
        int bestSpec = 0;
        for (Node n : nodes) {
            if (n.isExpired(nowEpochSeconds) || !n.matchesContext(contexts)) {
                continue;
            }
            Node.MatchResult mr = n.evaluateMatch(targetPerm);
            if (!mr.matched()) {
                continue;
            }
            boolean better = false;
            if (best == null) {
                better = true;
            } else if (mr.exact() != bestExact) {
                better = mr.exact();
            } else if (mr.exact()) {
                if (n.contexts().size() != best.contexts().size()) {
                    better = n.contexts().size() > best.contexts().size();
                } else if (mr.specificityScore() != bestSpec) {
                    better = mr.specificityScore() > bestSpec;
                } else if (!n.value() && best.value()) {
                    better = true;
                }
            } else {
                if (mr.specificityScore() != bestSpec) {
                    better = mr.specificityScore() > bestSpec;
                } else if (n.contexts().size() != best.contexts().size()) {
                    better = n.contexts().size() > best.contexts().size();
                } else if (!n.value() && best.value()) {
                    better = true;
                }
            }
            if (better) {
                best = n;
                bestExact = mr.exact();
                bestSpec = mr.specificityScore();
            }
        }
        return best == null ? null : new SelectedNode(best, bestExact, bestSpec);
    }

    // -------------------------------------------------------------------------
    // Group Inheritance & Primary Group Calculation
    // -------------------------------------------------------------------------

    public List<GroupRecord> resolveInheritedGroups(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        if (user == null || groups == null || groups.isEmpty()) {
            return List.of();
        }
        Set<String> visited = new HashSet<>();
        List<GroupRecord> result = new ArrayList<>();

        for (ParentEntry p : user.parents()) {
            if (!p.isExpired(nowEpochSeconds) && p.matchesContext(contexts)) {
                visitGroup(p.group(), groups, contexts, nowEpochSeconds, visited, result);
            }
        }
        String primary = user.primaryGroup() == null || user.primaryGroup().isBlank() ? "default" : user.primaryGroup();
        visitGroup(primary, groups, contexts, nowEpochSeconds, visited, result);

        result.sort(Comparator.<GroupRecord>comparingInt(GroupRecord::weight).reversed()
                .thenComparing(GroupRecord::name));
        return result;
    }

    private static void visitGroup(
            String rawName,
            Map<String, GroupRecord> groups,
            Map<String, String> contexts,
            long nowEpochSeconds,
            Set<String> visited,
            List<GroupRecord> out) {
        if (rawName == null) {
            return;
        }
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !visited.add(name)) {
            return;
        }
        GroupRecord g = groups.get(name);
        if (g == null) {
            return;
        }
        out.add(g);
        for (ParentEntry p : g.parents()) {
            if (!p.isExpired(nowEpochSeconds) && p.matchesContext(contexts)) {
                visitGroup(p.group(), groups, contexts, nowEpochSeconds, visited, out);
            }
        }
    }

    public List<GroupRecord> resolveGroupHierarchy(
            GroupRecord root, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        if (root == null) {
            return List.of();
        }
        Set<String> visited = new HashSet<>();
        List<GroupRecord> result = new ArrayList<>();
        visitGroupDirect(root, groups, contexts, nowEpochSeconds, visited, result);
        result.sort(Comparator.<GroupRecord>comparingInt(GroupRecord::weight).reversed()
                .thenComparing(GroupRecord::name));
        return result;
    }

    private static void visitGroupDirect(
            GroupRecord g,
            Map<String, GroupRecord> groups,
            Map<String, String> contexts,
            long nowEpochSeconds,
            Set<String> visited,
            List<GroupRecord> out) {
        if (g == null) {
            return;
        }
        String name = g.name().trim().toLowerCase(Locale.ROOT);
        if (!visited.add(name)) {
            return;
        }
        out.add(g);
        if (groups == null) {
            return;
        }
        for (ParentEntry p : g.parents()) {
            if (!p.isExpired(nowEpochSeconds) && p.matchesContext(contexts)) {
                GroupRecord parentGroup = groups.get(p.group().trim().toLowerCase(Locale.ROOT));
                if (parentGroup != null) {
                    visitGroupDirect(parentGroup, groups, contexts, nowEpochSeconds, visited, out);
                }
            }
        }
    }

    public String resolvePrimaryGroup(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, String defaultGroup) {
        return resolvePrimaryGroupAt(user, groups, contexts, defaultGroup, Instant.now().getEpochSecond());
    }

    public String resolvePrimaryGroupAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            Map<String, String> contexts,
            String defaultGroup,
            long nowEpochSeconds) {
        String def = (defaultGroup == null || defaultGroup.isEmpty()) ? "default" : defaultGroup;
        if (user == null) {
            return def;
        }
        String mode = getPrimaryGroupCalculation();
        if ("parents-by-weight".equals(mode) && groups != null) {
            GroupRecord bestGroup = null;
            for (ParentEntry p : user.parents()) {
                if (p.isExpired(nowEpochSeconds) || !p.matchesContext(contexts)) {
                    continue;
                }
                GroupRecord g = groups.get(p.group().trim().toLowerCase(Locale.ROOT));
                if (g != null && (bestGroup == null
                        || g.weight() > bestGroup.weight()
                        || (g.weight() == bestGroup.weight() && g.name().compareTo(bestGroup.name()) < 0))) {
                    bestGroup = g;
                }
            }
            if (bestGroup != null) {
                return bestGroup.name();
            }
        } else if ("all-parents-by-weight".equals(mode)) {
            List<GroupRecord> resolved = resolveInheritedGroups(user, groups, contexts, nowEpochSeconds);
            if (!resolved.isEmpty()) {
                return resolved.getFirst().name();
            }
        }
        String primary = user.primaryGroup() == null ? "" : user.primaryGroup().trim().toLowerCase(Locale.ROOT);
        return primary.isEmpty() ? def : primary;
    }

    // -------------------------------------------------------------------------
    // Prefixes & Suffixes
    // -------------------------------------------------------------------------

    public String getPrefix(UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts) {
        return getPrefixAt(user, groups, contexts, Instant.now().getEpochSecond());
    }

    public String getPrefixAt(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        if (user == null) {
            return "";
        }
        StandaloneConfig.StackConfig cfg = this.stackCfg;
        if (cfg.prefixEnabled()) {
            String stacked = getStackedPrefixAt(user, groups, contexts, nowEpochSeconds);
            if (!stacked.isEmpty()) {
                return stacked;
            }
        }
        return resolveAffixAt(user, groups, contexts, user.prefixes(), true, nowEpochSeconds);
    }

    public String getSuffix(UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts) {
        return getSuffixAt(user, groups, contexts, Instant.now().getEpochSecond());
    }

    public String getSuffixAt(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        if (user == null) {
            return "";
        }
        StandaloneConfig.StackConfig cfg = this.stackCfg;
        if (cfg.suffixEnabled()) {
            String stacked = getStackedSuffixAt(user, groups, contexts, nowEpochSeconds);
            if (!stacked.isEmpty()) {
                return stacked;
            }
        }
        return resolveAffixAt(user, groups, contexts, user.suffixes(), false, nowEpochSeconds);
    }

    public String getStackedPrefix(UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts) {
        return getStackedPrefixAt(user, groups, contexts, Instant.now().getEpochSecond());
    }

    public String getStackedPrefixAt(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        StandaloneConfig.StackConfig cfg = this.stackCfg;
        return formatStackedAffixAt(
                user, groups, contexts, user.prefixes(), true,
                cfg.prefixStartSpacer(), cfg.prefixMidSpacer(), cfg.prefixEndSpacer(), cfg.deduplicate(),
                nowEpochSeconds);
    }

    public String getStackedSuffix(UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts) {
        return getStackedSuffixAt(user, groups, contexts, Instant.now().getEpochSecond());
    }

    public String getStackedSuffixAt(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        StandaloneConfig.StackConfig cfg = this.stackCfg;
        return formatStackedAffixAt(
                user, groups, contexts, user.suffixes(), false,
                cfg.suffixStartSpacer(), cfg.suffixMidSpacer(), cfg.suffixEndSpacer(), cfg.deduplicate(),
                nowEpochSeconds);
    }

    private record RankedAffix(int priority, int ctxCount, int order, String value) {
    }

    private String formatStackedAffixAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            Map<String, String> contexts,
            List<PriorityMeta> direct,
            boolean isPrefix,
            String startSpacer,
            String midSpacer,
            String endSpacer,
            boolean deduplicate,
            long nowEpochSeconds) {
        if (user == null) {
            return "";
        }
        List<RankedAffix> all = new ArrayList<>();
        int order = 0;
        for (PriorityMeta item : direct) {
            if (!item.isExpired(nowEpochSeconds) && item.matchesContext(contexts) && !item.value().isEmpty()) {
                all.add(new RankedAffix(item.priority(), item.contexts().size(), order++, item.value()));
            }
        }
        for (GroupRecord g : resolveInheritedGroups(user, groups, contexts, nowEpochSeconds)) {
            List<PriorityMeta> items = isPrefix ? g.prefixes() : g.suffixes();
            for (PriorityMeta item : items) {
                if (!item.isExpired(nowEpochSeconds) && item.matchesContext(contexts) && !item.value().isEmpty()) {
                    all.add(new RankedAffix(item.priority(), item.contexts().size(), order++, item.value()));
                }
            }
        }
        if (all.isEmpty()) {
            return "";
        }
        all.sort(Comparator.<RankedAffix>comparingInt(RankedAffix::priority).reversed()
                .thenComparing(Comparator.<RankedAffix>comparingInt(RankedAffix::ctxCount).reversed())
                .thenComparingInt(RankedAffix::order));

        List<String> values = new ArrayList<>(all.size());
        Set<String> seen = deduplicate ? new HashSet<>(all.size()) : null;
        for (RankedAffix a : all) {
            if (seen != null && !seen.add(a.value())) {
                continue;
            }
            values.add(a.value());
        }
        if (values.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(midSpacer);
        for (String v : values) {
            joiner.add(v);
        }
        return startSpacer + joiner + endSpacer;
    }

    private String resolveAffixAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            Map<String, String> contexts,
            List<PriorityMeta> direct,
            boolean isPrefix,
            long nowEpochSeconds) {
        PriorityMeta bestDirect = highestActiveAffix(direct, contexts, nowEpochSeconds);
        if (bestDirect != null) {
            return bestDirect.value();
        }
        String primary = resolvePrimaryGroupAt(user, groups, contexts, "default", nowEpochSeconds);
        if (!primary.isEmpty() && !primary.equals("default") && groups != null) {
            GroupRecord pg = groups.get(primary);
            if (pg != null) {
                PriorityMeta bestPrimary = highestActiveAffix(isPrefix ? pg.prefixes() : pg.suffixes(), contexts, nowEpochSeconds);
                if (bestPrimary != null) {
                    return bestPrimary.value();
                }
            }
        }
        PriorityMeta highest = null;
        for (GroupRecord g : resolveInheritedGroups(user, groups, contexts, nowEpochSeconds)) {
            PriorityMeta cand = highestActiveAffix(isPrefix ? g.prefixes() : g.suffixes(), contexts, nowEpochSeconds);
            if (cand != null && (highest == null
                    || cand.priority() > highest.priority()
                    || (cand.priority() == highest.priority() && cand.contexts().size() > highest.contexts().size()))) {
                highest = cand;
            }
        }
        return highest != null ? highest.value() : "";
    }

    private static PriorityMeta highestActiveAffix(
            List<PriorityMeta> items, Map<String, String> contexts, long nowEpochSeconds) {
        PriorityMeta highest = null;
        for (PriorityMeta item : items) {
            if (item.isExpired(nowEpochSeconds) || !item.matchesContext(contexts)) {
                continue;
            }
            if (highest == null
                    || item.priority() > highest.priority()
                    || (item.priority() == highest.priority() && item.contexts().size() > highest.contexts().size())) {
                highest = item;
            }
        }
        return highest;
    }

    // -------------------------------------------------------------------------
    // Metadata Resolution
    // -------------------------------------------------------------------------

    public Optional<String> getMeta(UserRecord user, Map<String, GroupRecord> groups, String key) {
        return getMetaWithContext(user, groups, key, Map.of());
    }

    public Optional<String> getMetaWithContext(
            UserRecord user, Map<String, GroupRecord> groups, String key, Map<String, String> contexts) {
        return getMetaWithContextAt(user, groups, key, contexts, Instant.now().getEpochSecond());
    }

    public Optional<String> getMetaWithContextAt(
            UserRecord user,
            Map<String, GroupRecord> groups,
            String key,
            Map<String, String> contexts,
            long nowEpochSeconds) {
        if (key == null) {
            return Optional.empty();
        }
        Map<String, String> all = resolveAllMetaAt(user, groups, contexts, nowEpochSeconds);
        if (all.containsKey(key)) {
            return Optional.ofNullable(all.get(key));
        }
        List<String> keys = new ArrayList<>(all.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String k : keys) {
            if (k.equalsIgnoreCase(key)) {
                return Optional.ofNullable(all.get(k));
            }
        }
        return Optional.empty();
    }

    public Map<String, String> resolveAllMeta(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts) {
        return resolveAllMetaAt(user, groups, contexts, Instant.now().getEpochSecond());
    }

    public Map<String, String> resolveAllMetaAt(
            UserRecord user, Map<String, GroupRecord> groups, Map<String, String> contexts, long nowEpochSeconds) {
        Map<String, String> result = new LinkedHashMap<>();
        if (user == null) {
            return result;
        }
        applyMetaEntries(result, user.metaEntries(), user.meta(), contexts, nowEpochSeconds);
        for (GroupRecord g : resolveInheritedGroups(user, groups, contexts, nowEpochSeconds)) {
            applyMetaEntries(result, g.metaEntries(), g.meta(), contexts, nowEpochSeconds);
        }
        return result;
    }

    private static void applyMetaEntries(
            Map<String, String> result,
            List<MetaEntry> entries,
            Map<String, String> staticMeta,
            Map<String, String> contexts,
            long nowEpochSeconds) {
        if (entries != null && !entries.isEmpty()) {
            List<MetaEntry> matching = new ArrayList<>();
            for (MetaEntry e : entries) {
                if (!e.isExpired(nowEpochSeconds) && e.matchesContext(contexts)) {
                    matching.add(e);
                }
            }
            matching.sort(Comparator.<MetaEntry>comparingInt(m -> m.contexts().size()).reversed()
                    .thenComparing(Comparator.<MetaEntry>comparingInt(MetaEntry::priority).reversed()));
            for (MetaEntry e : matching) {
                result.putIfAbsent(e.key(), e.value());
            }
        }
        if (staticMeta != null && !staticMeta.isEmpty()) {
            List<String> keys = new ArrayList<>(staticMeta.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) {
                result.putIfAbsent(k, staticMeta.get(k));
            }
        }
    }
}
