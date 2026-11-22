package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.Decision;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.RefreshGate;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsBackend;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.StonyPermsEventStream;
import dev.stonyperms.bridge.core.local.LocalStonyPermsBackend;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the HTTP client, SSE stream, API, snapshot cache, and refresh scheduler for one server. */
public final class FabricBridgeRuntime implements AutoCloseable {

    private final FabricBridgeConfig config;
    private final Logger logger;
    private final StonyPermsBackend backend;
    private final LocalStonyPermsBackend localBackend;
    private final SnapshotCache cache;
    private final BridgeService service;
    private final DefaultStonyPermsBridgeAPI api;
    private final ExecutorService io;
    private final ExecutorService checks;
    private final ScheduledExecutorService scheduler;
    private final RefreshGate refreshGate = new RefreshGate(64);
    private final Map<UUID, LastSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    private final java.util.Set<String> observedPermissions = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastFailureLogAt = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile MinecraftServer server;
    private volatile StonyPermsEventStream eventStream;
    private volatile String lastError = "";

    public FabricBridgeRuntime(FabricBridgeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        if (config.isStandalone()) {
            try {
                this.localBackend = LocalStonyPermsBackend.create(config.standalone(), this::handleSseEvent);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "Failed to initialize standalone StonyPerms storage: " + error.getMessage(), error);
            }
            this.backend = this.localBackend;
        } else {
            this.localBackend = null;
            this.backend = new StonyPermsClient(
                    config.apiUrl(),
                    config.token(),
                    config.writeToken(),
                    Duration.ofMillis(config.connectTimeoutMs()),
                    Duration.ofMillis(config.requestTimeoutMs()));
        }
        this.cache = new SnapshotCache(config.staleGraceSeconds() * 1000L);
        this.service = new BridgeService(
                backend, cache, new PermissionResolver(), config.failClosed(), config.useStaleOnError());
        if (this.localBackend != null) {
            LocalStonyPermsBackend capture = this.localBackend;
            this.service.setVerboseObserver((uuid, perm, dec) ->
                    capture.recordResolvedCheck(uuid, perm, dec.value().name().toLowerCase(Locale.ROOT), dec.source()));
        }
        this.io = Executors.newFixedThreadPool(4, runnable -> {
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
        this.api = new DefaultStonyPermsBridgeAPI(
                service,
                backend,
                io,
                config.writeEnabled(),
                config.effectiveDefaultActor(),
                this::refreshByUuid,
                this::refreshServerAll);
    }

    public StonyPermsBridgeAPI api() {
        return api;
    }

    public MinecraftServer server() {
        return server;
    }

    public Optional<Snapshot> snapshot(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        SnapshotCache.Lookup lookup = service.lookup(player.getUUID(), contexts(player));
        if (!lookup.present() || (lookup.state() == SnapshotCache.State.STALE && !config.useStaleOnError())) {
            return Optional.empty();
        }
        return Optional.of(lookup.snapshot());
    }

    public void start(MinecraftServer server) {
        this.server = server;
        if (!config.isStandalone() && config.sseEnabled()) {
            StonyPermsEventStream stream = new StonyPermsEventStream(
                    config.apiUrl(),
                    config.token(),
                    Duration.ofMillis(config.connectTimeoutMs()),
                    config.sseReconnectMinMs(),
                    config.sseReconnectMaxMs(),
                    this::handleSseEvent);
            this.eventStream = stream;
            stream.start();
        }
        scheduler.scheduleAtFixedRate(
                () -> {
                    if (server != null && !closed.get()) {
                        server.execute(() -> refreshAll(server));
                    }
                },
                0,
                config.refreshSeconds(),
                TimeUnit.SECONDS);
        if (!config.isStandalone()) {
            scheduler.scheduleWithFixedDelay(
                    () -> {
                        MinecraftServer srv = this.server;
                        if (srv != null && !closed.get()) {
                            srv.execute(this::reportCatalog);
                        }
                    },
                    5,
                    300,
                    TimeUnit.SECONDS);
        }
    }

    /** Records permission nodes observed from mods for the backend catalog. */
    public void recordObservedPermission(String permission) {
        if (permission == null) {
            return;
        }
        String clean = permission.trim();
        if (clean.isEmpty() || clean.length() > 256 || observedPermissions.size() >= 20000) {
            return;
        }
        observedPermissions.add(clean);
    }

    private void reportCatalog() {
        MinecraftServer srv = this.server;
        if (!(backend instanceof StonyPermsClient client) || srv == null || closed.get()) {
            return;
        }
        TreeSet<String> permissions = new TreeSet<>(observedPermissions);
        TreeSet<String> dimensionSet = new TreeSet<>();
        for (var level : srv.getAllLevels()) {
            dimensionSet.add(level.dimension().identifier().getPath());
        }
        List<String> dimensions = List.copyOf(dimensionSet);
        Map<String, List<String>> contexts = new LinkedHashMap<>();
        String serverName = config.serverName();
        if (serverName != null && !serverName.isBlank()) {
            contexts.put("server", List.of(serverName));
        } else {
            serverName = "fabric";
        }
        contexts.put("world", dimensions);
        contexts.put("dimension-type", dimensions);
        contexts.put("gamemode", List.of("survival", "creative", "adventure", "spectator"));
        String catalogServer = serverName;
        io.submit(() -> {
            try {
                client.reportCatalog(catalogServer, permissions, contexts);
            } catch (Exception error) {
                if (config.debug()) {
                    logger.debug("Backend catalog push failed: {}", error.getMessage());
                }
            }
        });
    }

    public boolean shouldBypassForOp(boolean isOp) {
        return isOp && !config.applyToOps();
    }

    public boolean isOpBypassed(MinecraftServer srv, ServerPlayer player) {
        if (config.applyToOps() || srv == null || player == null) {
            return false;
        }
        return isOpBypassed(srv, player.getGameProfile());
    }

    public boolean isOpBypassed(MinecraftServer srv, GameProfile profile) {
        if (config.applyToOps() || srv == null || profile == null) {
            return false;
        }
        return srv.getPlayerList().isOp(new NameAndId(profile));
    }

    public void refreshAll(MinecraftServer server) {
        if (server == null || closed.get()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshAsync(player);
        }
    }

    public void refreshByUuid(UUID uuid) {
        MinecraftServer srv = this.server;
        if (uuid == null || srv == null || closed.get()) {
            return;
        }
        srv.execute(() -> {
            if (closed.get()) {
                return;
            }
            ServerPlayer player = srv.getPlayerList().getPlayer(uuid);
            if (player != null) {
                refreshAsync(player);
            }
        });
    }

    public void refreshServerAll() {
        MinecraftServer srv = this.server;
        if (srv == null || closed.get()) {
            return;
        }
        srv.execute(() -> refreshAll(srv));
    }

    public void ensureAndRefreshSync(ServerPlayer player) {
        if (player == null || closed.get()) {
            return;
        }
        UUID id = player.getUUID();
        Map<String, String> contexts = contexts(player);
        try {
            if (localBackend != null) {
                localBackend.ensureOnlineUser(id, player.getName().getString(), config.effectiveDefaultActor());
            }
            service.refresh(id, contexts);
            SnapshotCache.Lookup lookup = service.lookup(id, contexts);
            if (lookup.present()) {
                recordSnapshotUpdate(id, lookup.snapshot(), contexts);
            }
            lastError = "";
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            noteFailure();
        }
    }

    public void refreshAsync(ServerPlayer player) {
        UUID id = player.getUUID();
        Map<String, String> contexts = contexts(player);
        refreshAsync(id, contexts);
    }

    void refreshAsync(UUID id, Map<String, String> contexts) {
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
                        recordSnapshotUpdate(id, lookup.snapshot(), contexts);
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

    void recordSnapshotUpdate(UUID id, Snapshot snapshot, Map<String, String> contexts) {
        LastSnapshot previous = lastSnapshots.put(id, new LastSnapshot(snapshot, contexts));
        if (previous == null
                || previous.snapshot().revision() != snapshot.revision()
                || !previous.snapshot().equals(snapshot)) {
            long prevRev = previous == null ? -1L : previous.snapshot().revision();
            api.notifyUserUpdated(id, snapshot);
            MinecraftServer srv = this.server;
            if (srv != null && !closed.get()) {
                srv.execute(() -> {
                    StonyPermsEvents.USER_UPDATED.invoker().onUserUpdated(id, snapshot, prevRev);
                    resendCommandTree(srv, id);
                });
            } else {
                StonyPermsEvents.USER_UPDATED.invoker().onUserUpdated(id, snapshot, prevRev);
            }
        }
    }

    /**
     * Vanilla builds each player's command tree at join time. When the first snapshot for a
     * session arrives after that, commands gated by permissions are already marked restricted
     * on the client, so they execute but never autocomplete. Resending the tree after the
     * snapshot lands makes the full command set available without a reconnect.
     */
    private void resendCommandTree(MinecraftServer srv, UUID id) {
        if (srv == null || id == null || closed.get()) {
            return;
        }
        ServerPlayer player = srv.getPlayerList().getPlayer(id);
        if (player == null) {
            return;
        }
        try {
            srv.getCommands().sendCommands(player);
            logger.debug("Resent command tree to {} after snapshot refresh", player.getScoreboardName());
        } catch (RuntimeException error) {
            logger.warn("Failed to resend command tree to {}: {}", player.getScoreboardName(), error.getMessage());
        }
    }

    void handleSseEvent(ChangeEvent event) {
        if (event == null || closed.get()) {
            return;
        }
        api.notifyChangeEvent(event);
        MinecraftServer srv = this.server;
        if (srv != null) {
            srv.execute(() -> {
                if (closed.get()) {
                    return;
                }
                StonyPermsEvents.CHANGE_RECEIVED.invoker().onChangeReceived(event);
                dispatchSseRefresh(srv, event);
            });
        } else {
            StonyPermsEvents.CHANGE_RECEIVED.invoker().onChangeReceived(event);
        }
    }

    private void dispatchSseRefresh(MinecraftServer srv, ChangeEvent event) {
        String type = event.type();
        if ("user_updated".equals(type) || "user_deleted".equals(type)) {
            ServerPlayer target = null;
            if (!event.uuid().isEmpty()) {
                try {
                    target = srv.getPlayerList().getPlayer(UUID.fromString(event.uuid()));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (target == null && !event.username().isEmpty()) {
                target = srv.getPlayerList().getPlayerByName(event.username());
            }
            if (target != null) {
                if ("user_deleted".equals(type)) {
                    cache.invalidate(target.getUUID());
                }
                refreshAsync(target);
            }
        } else if ("group_updated".equals(type)
                || "group_deleted".equals(type)
                || "reload".equals(type)
                || "expiry_cleanup".equals(type)) {
            refreshAll(srv);
        }
    }

    public Decision resolve(ServerPlayer player, String permission) {
        return service.resolve(player.getUUID(), contexts(player), permission);
    }

    public Optional<String> option(ServerPlayer player, String key) {
        return service.option(player.getUUID(), contexts(player), key);
    }

    public Decision resolveOffline(UUID uuid, String permission) {
        Map<String, String> contexts = ensureOfflineContextsAndRefresh(uuid);
        return service.resolve(uuid, contexts, permission);
    }

    public Optional<String> optionOffline(UUID uuid, String key) {
        Map<String, String> contexts = ensureOfflineContextsAndRefresh(uuid);
        return service.option(uuid, contexts, key);
    }

    private Map<String, String> ensureOfflineContextsAndRefresh(UUID uuid) {
        LastSnapshot last = lastSnapshots.get(uuid);
        Map<String, String> contexts = last == null
                ? (config.isStandalone() ? defaultContexts() : Map.of())
                : last.contexts();
        if (config.isStandalone() && localBackend != null && uuid != null) {
            SnapshotCache.Lookup lookup = service.lookup(uuid, contexts);
            if (!lookup.present()) {
                try {
                    service.refresh(uuid, contexts);
                } catch (Exception error) {
                    lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
                }
            }
        }
        return contexts;
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
        if (config.isStandalone() && localBackend != null) {
            var mgr = localBackend.manager();
            return "StonyPerms standalone: storage=" + mgr.storage().describeTarget()
                    + ", revision=" + mgr.revision()
                    + ", users=" + mgr.usersCount()
                    + ", groups=" + mgr.groupsCount()
                    + ", tracks=" + mgr.tracksCount()
                    + ", server=" + (config.serverName().isEmpty() ? "(none)" : config.serverName())
                    + ", cached=" + cache.size()
                    + ", failClosed=" + config.failClosed()
                    + ", applyToOps=" + config.applyToOps()
                    + ", write=" + (api.isWriteEnabled() ? "enabled" : "disabled")
                    + ", lastError=" + error;
        }
        String sseState;
        StonyPermsEventStream stream = eventStream;
        if (!config.sseEnabled() || stream == null) {
            sseState = "disabled";
        } else if (stream.connected()) {
            sseState = "connected";
        } else if (!stream.lastError().isEmpty()) {
            sseState = "disconnected(" + stream.lastError() + ")";
        } else {
            sseState = "connecting";
        }
        return "StonyPerms bridge: api=" + config.apiUrl()
                + ", server=" + (config.serverName().isEmpty() ? "(none)" : config.serverName())
                + ", cached=" + cache.size()
                + ", refreshing=" + refreshGate.inFlight()
                + ", failClosed=" + config.failClosed()
                + ", applyToOps=" + config.applyToOps()
                + ", write=" + (api.isWriteEnabled() ? "enabled" : "disabled")
                + ", sse=" + sseState
                + ", staleGrace=" + config.staleGraceSeconds() + "s"
                + ", lastError=" + error;
    }

    public LocalStonyPermsBackend localBackend() {
        return localBackend;
    }

    public FabricBridgeConfig config() {
        return config;
    }

    public Map<String, String> contexts(ServerPlayer player) {
        String world = player.level().dimension().identifier().toString();
        Map<String, String> contexts = new LinkedHashMap<>(FabricContext.of(config.serverName(), world));
        contexts.put("dimension-type", player.level().dimension().identifier().getPath());
        String gamemode = player.isCreative() ? "creative"
                : player.isSpectator() ? "spectator"
                : "survival";
        contexts.put("gamemode", gamemode);
        return contexts;
    }

    public Map<String, String> defaultContexts() {
        return config.serverName().isEmpty() ? Map.of() : Map.of("server", config.serverName());
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
        closed.set(true);
        StonyPermsEventStream stream = eventStream;
        eventStream = null;
        if (stream != null) {
            stream.close();
        }
        scheduler.shutdownNow();
        io.shutdownNow();
        checks.shutdownNow();
        try {
            backend.close();
        } catch (Exception ignored) {
        }
    }

    private record LastSnapshot(Snapshot snapshot, Map<String, String> contexts) {
    }
}
