package dev.stonyperms.bridge.core;

import java.util.List;
import java.util.Map;

/** Full stored StonyPerms group record. */
public record GroupRecord(
        String name,
        String displayName,
        int weight,
        List<ParentEntry> parents,
        List<Node> nodes,
        Map<String, String> meta,
        List<PriorityMeta> prefixes,
        List<PriorityMeta> suffixes,
        List<MetaEntry> metaEntries) {

    public GroupRecord(
            String name,
            String displayName,
            int weight,
            List<ParentEntry> parents,
            List<Node> nodes,
            Map<String, String> meta,
            List<PriorityMeta> prefixes,
            List<PriorityMeta> suffixes) {
        this(name, displayName, weight, parents, nodes, meta, prefixes, suffixes, List.of());
    }

    public GroupRecord {
        name = name == null ? "" : name;
        displayName = displayName == null ? "" : displayName;
        parents = parents == null ? List.of() : List.copyOf(parents);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        meta = meta == null || meta.isEmpty() ? Map.of() : Map.copyOf(meta);
        prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
        suffixes = suffixes == null ? List.of() : List.copyOf(suffixes);
        metaEntries = metaEntries == null ? List.of() : List.copyOf(metaEntries);
    }

    public String effectiveDisplayName() {
        return displayName.isEmpty() ? name : displayName;
    }
}
