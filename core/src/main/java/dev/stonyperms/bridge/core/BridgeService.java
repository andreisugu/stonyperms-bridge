package dev.stonyperms.bridge.core;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-neutral orchestration: refresh snapshots, serve cached values, and
 * apply the configured fail-closed/stale policy.
 */
public final class BridgeService {

    private final SnapshotSource source;
    private final SnapshotCache cache;
    private final PermissionResolver resolver;
    @FunctionalInterface
    public interface VerboseObserver {
        void onResolve(UUID uuid, String permission, Decision decision);
    }

    private final boolean failClosed;
    private final boolean useStaleOnError;
    private volatile VerboseObserver verboseObserver;

    public BridgeService(
            SnapshotSource source,
            SnapshotCache cache,
            PermissionResolver resolver,
            boolean failClosed,
            boolean useStaleOnError) {
        this.source = source;
        this.cache = cache;
        this.resolver = resolver;
        this.failClosed = failClosed;
        this.useStaleOnError = useStaleOnError;
    }

    public SnapshotCache.Lookup lookup(UUID uuid, Map<String, String> contexts) {
        SnapshotCache.Key key = new SnapshotCache.Key(uuid, Contexts.key(contexts));
        return cache.lookup(key, System.currentTimeMillis());
    }

    public void setVerboseObserver(VerboseObserver verboseObserver) {
        this.verboseObserver = verboseObserver;
    }

    public Decision resolve(UUID uuid, Map<String, String> contexts, String permission) {
        SnapshotCache.Lookup lookup = lookup(uuid, contexts);
        Decision decision = usable(lookup)
                ? resolver.resolve(lookup.snapshot(), permission, contexts)
                : unavailableDecision();
        VerboseObserver obs = this.verboseObserver;
        if (obs != null) {
            obs.onResolve(uuid, permission, decision);
        }
        return decision;
    }

    public Optional<Map<String, Boolean>> materialize(
            UUID uuid, Map<String, String> contexts, java.util.Collection<String> permissions) {
        SnapshotCache.Lookup lookup = lookup(uuid, contexts);
        if (!usable(lookup)) {
            return Optional.empty();
        }
        return Optional.of(resolver.materialize(lookup.snapshot(), permissions, contexts));
    }

    public Optional<String> option(UUID uuid, Map<String, String> contexts, String key) {
        if (key == null) {
            return Optional.empty();
        }
        SnapshotCache.Lookup lookup = lookup(uuid, contexts);
        if (!usable(lookup)) {
            return Optional.empty();
        }
        UserInfo user = lookup.snapshot().user();
        String value = switch (key) {
            case "prefix" -> user.prefix();
            case "suffix" -> user.suffix();
            default -> user.meta().get(key);
        };
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Fetches a fresh snapshot (or validates the cached one with an ETag). */
    public void refresh(UUID uuid, Map<String, String> contexts) throws IOException, InterruptedException {
        SnapshotCache.Key key = new SnapshotCache.Key(uuid, Contexts.key(contexts));
        SnapshotCache.Entry existing = cache.entry(key);
        SnapshotSource.FetchResult result = source.fetchSnapshot(
                uuid, contexts, existing == null ? null : existing.etag());

        long now = System.currentTimeMillis();
        if (result.notModified()) {
            if (existing != null) {
                cache.touch(key, now);
                return;
            }
            result = source.fetchSnapshot(uuid, contexts, null);
            if (result.notModified() || result.snapshot() == null) {
                return;
            }
        }
        cache.put(key, result.snapshot(), result.etag(), now);
    }

    public CheckResult authoritativeCheck(UUID uuid, String permission, Map<String, String> contexts)
            throws IOException, InterruptedException {
        return source.check(uuid, permission, contexts);
    }

    public long revision() throws IOException, InterruptedException {
        return source.fetchRevision();
    }

    public SnapshotCache cache() {
        return cache;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public boolean useStaleOnError() {
        return useStaleOnError;
    }

    private boolean usable(SnapshotCache.Lookup lookup) {
        return lookup.present() && (lookup.state() != SnapshotCache.State.STALE || useStaleOnError);
    }

    private Decision unavailableDecision() {
        return new Decision(failClosed ? Tri.FALSE : Tri.UNDEFINED, "bridge:unavailable");
    }
}
