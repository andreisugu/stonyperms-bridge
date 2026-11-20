package dev.stonyperms.bridge.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Coalesces duplicate refreshes for the same player/context and bounds the
 * number of queued or running HTTP refreshes.
 */
public final class RefreshGate {

    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final Semaphore permits;

    public RefreshGate(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    /** Returns true only when the caller acquired the right to submit work. */
    public boolean tryBegin(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        if (!permits.tryAcquire()) {
            inFlight.remove(key);
            return false;
        }
        return true;
    }

    /** Must be called exactly once for every successful tryBegin. */
    public void end(String key) {
        if (key == null) {
            return;
        }
        if (inFlight.remove(key) != null) {
            permits.release();
        }
    }

    public int inFlight() {
        return inFlight.size();
    }
}
