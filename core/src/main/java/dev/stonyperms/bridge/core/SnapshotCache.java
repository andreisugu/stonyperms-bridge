package dev.stonyperms.bridge.core;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of resolved snapshots. Entries are fresh for the TTL
 * returned by the API and may be served stale for an operator-configured grace
 * period while the API is unavailable.
 */
public final class SnapshotCache {

    public record Key(UUID uuid, String contextKey) {
    }

    public record Entry(Snapshot snapshot, String etag, long fetchedAtMillis) {
    }

    public enum State {
        FRESH,
        STALE,
        MISSING
    }

    public record Lookup(State state, Snapshot snapshot, String etag) {
        public static Lookup missing() {
            return new Lookup(State.MISSING, null, null);
        }

        public boolean present() {
            return snapshot != null;
        }
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final long staleGraceMillis;

    public SnapshotCache(long staleGraceMillis) {
        this.staleGraceMillis = Math.max(0, staleGraceMillis);
    }

    public Lookup lookup(Key key, long nowMillis) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Lookup.missing();
        }

        long ttlMillis = entry.snapshot().effectiveTtlSeconds() * 1000L;
        long age = Math.max(0, nowMillis - entry.fetchedAtMillis());
        if (age <= ttlMillis) {
            return new Lookup(State.FRESH, entry.snapshot(), entry.etag());
        }
        if (age <= ttlMillis + staleGraceMillis) {
            return new Lookup(State.STALE, entry.snapshot(), entry.etag());
        }

        entries.remove(key, entry);
        return Lookup.missing();
    }

    public Entry entry(Key key) {
        return entries.get(key);
    }

    /** Stores a snapshot unless a newer revision is already cached. */
    public void put(Key key, Snapshot snapshot, String etag, long nowMillis) {
        if (snapshot == null) {
            return;
        }
        entries.compute(key, (ignored, existing) -> {
            if (existing != null && existing.snapshot().revision() > snapshot.revision()) {
                return existing;
            }
            return new Entry(snapshot, etag, nowMillis);
        });
    }

    /** Marks an entry as just validated, e.g. after an HTTP 304. */
    public void touch(Key key, long nowMillis) {
        entries.computeIfPresent(key, (ignored, existing) ->
                new Entry(existing.snapshot(), existing.etag(), nowMillis));
    }

    public void invalidate(UUID uuid) {
        entries.keySet().removeIf(key -> key.uuid().equals(uuid));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
