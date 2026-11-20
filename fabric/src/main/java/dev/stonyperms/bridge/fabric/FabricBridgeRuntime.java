package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.Decision;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.RefreshGate;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the HTTP client, snapshot cache, and refresh scheduler for one server. */
public final class FabricBridgeRuntime implements AutoCloseable {

    private final FabricBridgeConfig config;
    private final Logger logger;
    private final StonyPermsClient client;
    private final SnapshotCache cache;
    private final BridgeService service;
    private final ExecutorService io;
    private final ExecutorService checks;
    private final ScheduledExecutorService scheduler;
    private final RefreshGate refreshGate = new RefreshGate(64);
    private final Map<UUID, LastSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong lastFailureLogAt = new AtomicLong();
    private volatile String lastError = "";

    public FabricBridgeRuntime(FabricBridgeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.client = new StonyPermsClient(
                config.apiUrl(),
                config.token(),
                Duration.ofMillis(config.connectTimeoutMs()),
                Duration.ofMillis(config.requestTimeoutMs()));
        this.cache = new SnapshotCache(config.staleGraceSeconds() * 1000L);
        this.service = new BridgeService(
                client, cache, new PermissionResolver(), config.failClosed(), config.useStaleOnError());
        this.io = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "stonyperms-bridge-http");
            thread.setDaemon(true);
            return thread;
        });
        this.checks = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "stonyperms-bridge-check");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stonyperms-bridge-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start(MinecraftServer server) {
        scheduler.scheduleAtFixedRate(
                () -> server.execute(() -> refreshAll(server)),
                0,
                config.refreshSeconds(),
                TimeUnit.SECONDS);
    }

    public void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshAsync(player);
        }
    }

    public void refreshAsync(ServerPlayer player) {
        UUID id = player.getUUID();
        Map<String, String> contexts = contexts(player);
        String key = id + "|" + Contexts.key(contexts);
        if (!refreshGate.tryBegin(key)) {
            return;
        }
        try {
            io.submit(() -> {
                try {
                    service.refresh(id, contexts);
                    SnapshotCache.Lookup lookup = service.lookup(id, contexts);
                    if (lookup.present()) {
                        lastSnapshots.compute(id, (ignored, existing) -> {
                            Snapshot snapshot = lookup.snapshot();
                            if (existing != null && existing.snapshot().revision() > snapshot.revision()) {
                                return existing;
                            }
                            return new LastSnapshot(snapshot, contexts);
                        });
                    }
                    lastError = "";
                } catch (Exception error) {
                    lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
                    noteFailure();
                } finally {
                    refreshGate.end(key);
                }
            });
        } catch (RuntimeException error) {
            refreshGate.end(key);
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            noteFailure();
        }
    }

    public Decision resolve(ServerPlayer player, String permission) {
        return service.resolve(player.getUUID(), contexts(player), permission);
    }

    public Optional<String> option(ServerPlayer player, String key) {
        return service.option(player.getUUID(), contexts(player), key);
    }

    public Decision resolveOffline(UUID uuid, String permission) {
        LastSnapshot last = lastSnapshots.get(uuid);
        Map<String, String> contexts = last == null ? Map.of() : last.contexts();
        return service.resolve(uuid, contexts, permission);
    }

    public Optional<String> optionOffline(UUID uuid, String key) {
        LastSnapshot last = lastSnapshots.get(uuid);
        Map<String, String> contexts = last == null ? Map.of() : last.contexts();
        return service.option(uuid, contexts, key);
    }

    public CompletableFuture<CheckResult> authoritativeCheck(ServerPlayer player, String permission) {
        Map<String, String> contexts = contexts(player);
        UUID id = player.getUUID();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return service.authoritativeCheck(id, permission, contexts);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, checks);
    }

    public String status() {
        String error = lastError.isEmpty() ? "none" : lastError;
        return "StonyPerms bridge: api=" + config.apiUrl()
                + ", server=" + (config.serverName().isEmpty() ? "(none)" : config.serverName())
                + ", cached=" + cache.size()
                + ", refreshing=" + refreshGate.inFlight()
                + ", failClosed=" + config.failClosed()
                + ", staleGrace=" + config.staleGraceSeconds() + "s"
                + ", lastError=" + error;
    }

    public FabricBridgeConfig config() {
        return config;
    }

    private Map<String, String> contexts(ServerPlayer player) {
        String world = player.level().dimension().identifier().toString();
        return FabricContext.of(config.serverName(), world);
    }

    private void noteFailure() {
        long now = System.currentTimeMillis();
        long previous = lastFailureLogAt.get();
        if (config.debug() || now - previous > 300_000) {
            lastFailureLogAt.set(now);
            logger.warn("StonyPerms API refresh failed: {}", lastError);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        io.shutdownNow();
        checks.shutdownNow();
        client.close();
    }

    private record LastSnapshot(Snapshot snapshot, Map<String, String> contexts) {
    }
}
