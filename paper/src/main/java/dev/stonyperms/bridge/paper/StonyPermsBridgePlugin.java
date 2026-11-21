package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.Contexts;
import dev.stonyperms.bridge.core.DisplayFormat;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsBackend;
import dev.stonyperms.bridge.core.StonyPermsClient;
import dev.stonyperms.bridge.core.StonyPermsEventStream;
import dev.stonyperms.bridge.core.local.LocalStonyPermsBackend;
import dev.stonyperms.bridge.core.cmd.BridgeCommandHandler;
import dev.stonyperms.bridge.core.cmd.CommandPlatform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper adapter. Applies materialized StonyPerms snapshots as Bukkit
 * PermissionAttachments, connects to Gate SSE events, and exposes
 * {@link StonyPermsBridgeAPI} + optional Vault/PlaceholderAPI integrations.
 */
public final class StonyPermsBridgePlugin extends JavaPlugin implements Listener, TabCompleter {

    private static volatile StonyPermsBridgeAPI activeApi;

    private final PermissionResolver resolver = new PermissionResolver();
    private final PaperPermissionApplier applier = new PaperPermissionApplier(this);
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> managedKeys = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> reapplyTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Snapshot> appliedSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong lastFailureLogAt = new AtomicLong();

    private ExecutorService io;
    private PaperBridgeConfig bridgeConfig;
    private StonyPermsBackend backend;
    private LocalStonyPermsBackend localBackend;
    private BridgeService service;
    private DefaultStonyPermsBridgeAPI api;
    private StonyPermsEventStream eventStream;
    private BridgeCommandHandler commandHandler;
    private Command standaloneCommand;
    private PaperPermissionUniverse universe;
    private Runnable placeholderCleanup = () -> {};
    private Runnable vaultCleanup = () -> {};
    private int refreshTaskId = -1;
    private volatile String lastError = "";

    public static Optional<StonyPermsBridgeAPI> getApi() {
        return Optional.ofNullable(activeApi);
    }

    public static Optional<StonyPermsBridgeAPI> getService() {
        return Optional.ofNullable(activeApi);
    }

    @Override
    public void onEnable() {
        io = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "stonyperms-bridge");
            thread.setDaemon(true);
            return thread;
        });
        saveDefaultConfig();

        if (!reloadBridge()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("spb") != null) {
            getCommand("spb").setExecutor(this);
            getCommand("spb").setTabCompleter(this);
        }
        if (bridgeConfig.isStandalone()) {
            String targetDesc = localBackend != null ? localBackend.manager().storage().describeTarget() : bridgeConfig.standalone().storageDir().toString();
            getLogger().info("StonyPerms standalone enabled (storage=" + targetDesc + ", write=" + api.isWriteEnabled() + ")");
        } else {
            getLogger().info("StonyPerms bridge enabled for " + bridgeConfig.apiUrl()
                    + " (write=" + api.isWriteEnabled() + ", sse=" + bridgeConfig.sseEnabled() + ")");
        }
    }

    @Override
    public void onDisable() {
        activeApi = null;
        stopScheduler();
        stopEventStream();
        unregisterStandaloneCommand();
        runCleanups();
        getServer().getServicesManager().unregisterAll(this);
        for (BukkitTask task : reapplyTasks.values()) {
            task.cancel();
        }
        reapplyTasks.clear();
        managedKeys.clear();
        appliedSnapshots.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applier.clear(player);
            clearPlayerDisplay(player);
        }
        localBackend = null;
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {
            }
            backend = null;
        }
        if (io != null) {
            io.shutdownNow();
        }
    }

    private boolean reloadBridge() {
        PaperBridgeConfig newConfig;
        try {
            newConfig = PaperBridgeConfig.from(getConfig(), getDataFolder().toPath());
        } catch (PaperBridgeConfig.PaperConfigException error) {
            getLogger().severe("Invalid StonyPerms bridge config: " + error.getMessage());
            return false;
        }

        Duration connectTimeout = Duration.ofMillis(Math.max(250, getConfig().getInt("api.connect-timeout-ms", 1500)));
        Duration requestTimeout = Duration.ofMillis(Math.max(500, getConfig().getInt("api.request-timeout-ms", 2500)));
        StonyPermsBackend newBackend;
        LocalStonyPermsBackend newLocalBackend = null;
        if (newConfig.isStandalone()) {
            try {
                newLocalBackend = LocalStonyPermsBackend.create(newConfig.standalone(), this::handleChangeEvent);
            } catch (IOException error) {
                getLogger().log(Level.SEVERE,
                        "Failed to initialize StonyPerms standalone storage: " + error.getMessage(), error);
                return false;
            }
            newBackend = newLocalBackend;
        } else {
            newBackend = new StonyPermsClient(
                    newConfig.apiUrl(),
                    newConfig.token(),
                    newConfig.writeToken(),
                    connectTimeout,
                    requestTimeout);
        }
        SnapshotCache cache = new SnapshotCache(newConfig.staleGraceSeconds() * 1000L);
        BridgeService newService = new BridgeService(
                newBackend, cache, resolver, newConfig.failClosed(), newConfig.useStaleOnError());
        if (newLocalBackend != null) {
            LocalStonyPermsBackend capture = newLocalBackend;
            newService.setVerboseObserver((uuid, perm, dec) ->
                    capture.recordResolvedCheck(uuid, perm, dec.value().name().toLowerCase(Locale.ROOT), dec.source()));
        }

        stopScheduler();
        stopEventStream();
        unregisterStandaloneCommand();
        runCleanups();
        getServer().getServicesManager().unregisterAll(this);
        for (BukkitTask task : reapplyTasks.values()) {
            task.cancel();
        }
        reapplyTasks.clear();
        managedKeys.clear();
        appliedSnapshots.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applier.clear(player);
            clearPlayerDisplay(player);
        }
        localBackend = null;
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {
            }
            backend = null;
        }

        bridgeConfig = newConfig;
        backend = newBackend;
        localBackend = newLocalBackend;
        service = newService;
        universe = new PaperPermissionUniverse(getServer().getPluginManager());
        lastError = "";

        DefaultStonyPermsBridgeAPI newApi = new DefaultStonyPermsBridgeAPI(
                newService,
                newBackend,
                io,
                newConfig.writeEnabled(),
                newConfig.effectiveDefaultActor(),
                this::refreshByUuid,
                this::refreshAllAsync);
        api = newApi;
        activeApi = newApi;
        commandHandler = new BridgeCommandHandler(newApi, new PaperCommandPlatform());

        getServer().getServicesManager().register(
                StonyPermsBridgeAPI.class, newApi, this, ServicePriority.Normal);

        if (newConfig.integrationsPlaceholderApi()
                && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderCleanup = PlaceholderApiRegistrar.register(
                    this, newApi, this::contexts, defaultContexts());
        }
        if (newConfig.integrationsVault()
                && getServer().getPluginManager().isPluginEnabled("Vault")) {
            vaultCleanup = VaultRegistrar.register(
                    this, newApi, this::contexts, this::worldContexts);
        }

        if (newConfig.isRemotePlainHttp()) {
            getLogger().warning("api.url uses plain HTTP to a non-loopback host; prefer TLS or a private network");
        }

        if (newConfig.isStandalone()) {
            registerStandaloneCommand();
            for (Player player : Bukkit.getOnlinePlayers()) {
                refreshSyncStandalone(player, true);
            }
        } else if (newConfig.sseEnabled()) {
            StonyPermsEventStream stream = new StonyPermsEventStream(
                    newConfig.apiUrl(),
                    newConfig.token(),
                    connectTimeout,
                    newConfig.sseReconnectMinMs(),
                    newConfig.sseReconnectMaxMs(),
                    this::handleChangeEvent);
            eventStream = stream;
            stream.start();
        }

        startScheduler();
        return true;
    }

    private void registerStandaloneCommand() {
        unregisterStandaloneCommand();
        Command cmd = new Command(
                "sp",
                "StonyPerms standalone permission administration",
                "/sp <status|info|refresh|check|search|reload|user|group|track|log|editor|applyedits|tree|verbose>",
                List.of("stonyperms")) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return StonyPermsBridgePlugin.this.onCommand(sender, this, commandLabel, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return StonyPermsBridgePlugin.this.onTabComplete(sender, this, alias, args);
            }
        };
        if (Bukkit.getCommandMap().register("stonyperms", cmd)) {
            standaloneCommand = cmd;
        }
    }

    private void unregisterStandaloneCommand() {
        Command cmd = standaloneCommand;
        standaloneCommand = null;
        if (cmd == null) {
            return;
        }
        try {
            var commandMap = Bukkit.getCommandMap();
            cmd.unregister(commandMap);
            commandMap.getKnownCommands().entrySet().removeIf(entry -> entry.getValue() == cmd);
        } catch (RuntimeException ignored) {
        }
    }

    private void runCleanups() {
        try {
            placeholderCleanup.run();
        } catch (RuntimeException ignored) {
        }
        placeholderCleanup = () -> {};
        try {
            vaultCleanup.run();
        } catch (RuntimeException ignored) {
        }
        vaultCleanup = () -> {};
    }

    private void stopEventStream() {
        StonyPermsEventStream current = eventStream;
        eventStream = null;
        if (current != null) {
            current.close();
        }
    }

    private void handleChangeEvent(ChangeEvent event) {
        DefaultStonyPermsBridgeAPI currentApi = api;
        if (currentApi != null) {
            currentApi.notifyChangeEvent(event);
        }
        if (!isEnabled()) {
            return;
        }
        String type = event.type();
        if ("user_updated".equals(type) || "user_deleted".equals(type)) {
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }
                Player target = null;
                if (!event.uuid().isEmpty()) {
                    try {
                        target = Bukkit.getPlayer(UUID.fromString(event.uuid()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (target == null && !event.username().isEmpty()) {
                    target = Bukkit.getPlayerExact(event.username());
                }
                if (target != null && target.isOnline()) {
                    if ("user_deleted".equals(type) && service != null) {
                        service.cache().invalidate(target.getUniqueId());
                    }
                    refreshAsync(target);
                }
            });
        } else if ("group_updated".equals(type)
                || "group_deleted".equals(type)
                || "reload".equals(type)
                || "expiry_cleanup".equals(type)) {
            getServer().getScheduler().runTask(this, () -> {
                if (isEnabled()) {
                    refreshAllOnline();
                }
            });
        }
    }

    private void startScheduler() {
        long ticks = 20L * bridgeConfig.refreshSeconds();
        refreshTaskId = getServer().getScheduler().runTaskTimer(
                this, this::refreshAllOnline, ticks, ticks).getTaskId();
    }

    private void stopScheduler() {
        if (refreshTaskId != -1) {
            getServer().getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    private void refreshAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshAsync(player);
        }
    }

    private void refreshAllAsync() {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            refreshAllOnline();
        } else {
            getServer().getScheduler().runTask(this, this::refreshAllOnline);
        }
    }

    private void refreshByUuid(UUID uuid) {
        if (uuid == null || !isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                refreshAsync(online);
            }
        } else {
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    refreshAsync(online);
                }
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (bridgeConfig != null && bridgeConfig.isStandalone() && localBackend != null) {
            refreshSyncStandalone(player, true);
            return;
        }
        applyPlayer(player);
        refreshAsync(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelReapply(id);
        managedKeys.remove(id);
        appliedSnapshots.remove(id);
        applier.clear(event.getPlayer());
        clearPlayerDisplay(event.getPlayer());
        refreshing.remove(id);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        PaperBridgeConfig config = bridgeConfig;
        if (config == null || !config.chatEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isOp() && !config.applyToOps()) {
            return;
        }
        Snapshot snap = appliedSnapshots.get(player.getUniqueId());
        UserInfo user = snap != null ? snap.user() : null;
        event.renderer((source, sourceDisplayName, message, viewer) ->
                renderChatMessage(config.chatFormat(), user, source.getName(), message));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (bridgeConfig != null && bridgeConfig.isStandalone() && localBackend != null) {
            refreshSyncStandalone(player, false);
            return;
        }
        applyPlayer(player);
        refreshAsync(player);
    }

    private void refreshSyncStandalone(Player player, boolean ensureUser) {
        LocalStonyPermsBackend local = localBackend;
        BridgeService currentService = service;
        if (local == null || currentService == null) {
            return;
        }
        try {
            if (ensureUser) {
                local.ensureOnlineUser(
                        player.getUniqueId(), player.getName(), bridgeConfig.effectiveDefaultActor());
            }
            currentService.refresh(player.getUniqueId(), contexts(player));
            lastError = "";
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            noteFailure();
        }
        applyPlayer(player);
    }

    private void refreshAsync(Player player) {
        UUID id = player.getUniqueId();
        if (!refreshing.add(id)) {
            return;
        }
        Map<String, String> contexts = contexts(player);
        io.submit(() -> {
            try {
                service.refresh(id, contexts);
                lastError = "";
            } catch (Exception error) {
                lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
                noteFailure();
            } finally {
                refreshing.remove(id);
            }
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline()) {
                    applyPlayer(online);
                }
            });
        });
    }

    private void applyPlayer(Player player) {
        UUID id = player.getUniqueId();
        if (player.isOp() && !bridgeConfig.applyToOps()) {
            cancelReapply(id);
            appliedSnapshots.remove(id);
            applier.clear(player);
            clearPlayerDisplay(player);
            return;
        }
        Map<String, String> contexts = contexts(player);
        SnapshotCache.Key key = new SnapshotCache.Key(id, Contexts.key(contexts));
        SnapshotCache.Lookup lookup = service.lookup(id, contexts);
        if (!lookup.present() || (lookup.state() == SnapshotCache.State.STALE && !bridgeConfig.useStaleOnError())) {
            cancelReapply(id);
            appliedSnapshots.remove(id);
            applyUnavailable(player);
            return;
        }

        Snapshot snapshot = lookup.snapshot();
        Set<String> permissions = PaperMaterialization.permissions(snapshot, universe.collect());
        service.materialize(id, contexts, permissions).ifPresentOrElse(values -> {
            Map<String, Boolean> safe = PaperMaterialization.withoutConflictingParents(
                    values, universe.registered());
            managedKeys.put(id, Set.copyOf(values.keySet()));
            applier.apply(player, safe);
            applyPlayerDisplay(player, snapshot.user());
            scheduleReapply(player, key, snapshot);

            Snapshot prev = appliedSnapshots.put(id, snapshot);
            if (prev == null || prev.revision() != snapshot.revision() || !prev.equals(snapshot)) {
                long prevRev = prev == null ? -1L : prev.revision();
                if (api != null) {
                    api.notifyUserUpdated(id, snapshot);
                }
                getServer().getPluginManager().callEvent(new StonyPermsUserUpdateEvent(player, snapshot, prevRev));
            }
        }, () -> {
            cancelReapply(id);
            appliedSnapshots.remove(id);
            applyUnavailable(player);
        });
    }

    private void applyUnavailable(Player player) {
        UUID id = player.getUniqueId();
        if (!bridgeConfig.failClosed()) {
            applier.apply(player, Map.of());
            return;
        }
        Set<String> permissions = new java.util.HashSet<>(universe.collect());
        permissions.addAll(managedKeys.getOrDefault(id, Set.of()));
        applier.apply(player, PaperMaterialization.failClosedValues(permissions));
        clearPlayerDisplay(player);
    }

    private void applyPlayerDisplay(Player player, UserInfo user) {
        PaperBridgeConfig config = bridgeConfig;
        if (config == null || !config.tablistEnabled()) {
            return;
        }
        String formatted = DisplayFormat.translateAlternateColorCodes(
                DisplayFormat.formatDisplayName(user, player.getName()));
        Component display = LegacyComponentSerializer.legacySection().deserialize(formatted);
        player.displayName(display);
        player.playerListName(display);
    }

    private void clearPlayerDisplay(Player player) {
        PaperBridgeConfig config = bridgeConfig;
        if (config == null || !config.tablistEnabled()) {
            return;
        }
        player.displayName(null);
        player.playerListName(null);
    }

    static Component renderChatMessage(String format, UserInfo user, String username, Component message) {
        DisplayFormat.ChatParts parts = DisplayFormat.formatChatParts(format, user, username);
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component before = legacy.deserialize(DisplayFormat.translateAlternateColorCodes(parts.beforeMessage()));
        Component after = parts.afterMessage().isEmpty()
                ? Component.empty()
                : legacy.deserialize(DisplayFormat.translateAlternateColorCodes(parts.afterMessage()));
        NamedTextColor msgColor = namedColorForCode(DisplayFormat.trailingColorCode(parts.beforeMessage()));
        Component msgPart = msgColor == null ? message : message.colorIfAbsent(msgColor);
        return Component.text().append(before).append(msgPart).append(after).build();
    }

    static NamedTextColor namedColorForCode(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private void scheduleReapply(Player player, SnapshotCache.Key key, Snapshot snapshot) {
        UUID id = player.getUniqueId();
        cancelReapply(id);
        SnapshotCache.Entry entry = service.cache().entry(key);
        long delayMillis = PaperReapply.nextDelayMillis(
                entry,
                snapshot,
                System.currentTimeMillis(),
                bridgeConfig.staleGraceSeconds() * 1000L,
                bridgeConfig.useStaleOnError(),
                5 * 60 * 1000L);
        long ticks = Math.max(1L, Math.min(delayMillis, 5 * 60 * 1000L) / 50L);
        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            reapplyTasks.remove(id);
            if (!isEnabled()) {
                return;
            }
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                applyPlayer(online);
            }
        }, ticks);
        reapplyTasks.put(id, task);
    }

    private void cancelReapply(UUID id) {
        BukkitTask old = reapplyTasks.remove(id);
        if (old != null) {
            old.cancel();
        }
    }

    private Map<String, String> contexts(Player player) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (!bridgeConfig.serverName().isEmpty()) {
            contexts.put("server", bridgeConfig.serverName());
        }
        contexts.put("world", player.getWorld().getName());
        return contexts;
    }

    private Map<String, String> worldContexts(String worldName) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (!bridgeConfig.serverName().isEmpty()) {
            contexts.put("server", bridgeConfig.serverName());
        }
        if (worldName != null && !worldName.isBlank()) {
            contexts.put("world", worldName);
        }
        return contexts;
    }

    private Map<String, String> defaultContexts() {
        return bridgeConfig.serverName().isEmpty()
                ? Map.of()
                : Map.of("server", bridgeConfig.serverName());
    }

    private void noteFailure() {
        long now = System.currentTimeMillis();
        long previous = lastFailureLogAt.get();
        if (bridgeConfig.debug() || now - previous > 300_000) {
            lastFailureLogAt.set(now);
            getLogger().warning("StonyPerms API refresh failed: " + lastError);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BridgeCommandHandler handler = commandHandler;
        if (handler == null) {
            sender.sendMessage("StonyPerms bridge is not initialized");
            return true;
        }
        return handler.execute(sender, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        BridgeCommandHandler handler = commandHandler;
        return handler == null ? List.of() : handler.tabComplete(sender, args);
    }

    private final class PaperCommandPlatform implements CommandPlatform {

        @Override
        public boolean hasAdminPermission(Object sender) {
            return sender instanceof CommandSender cs
                    && (cs.hasPermission("stonyperms.bridge.admin")
                    || cs.hasPermission("stonyperms.admin")
                    || cs.hasPermission("stonyperms.*"));
        }

        @Override
        public boolean hasWritePermission(Object sender) {
            return sender instanceof CommandSender cs
                    && (cs.hasPermission("stonyperms.bridge.write")
                    || cs.hasPermission("stonyperms.admin")
                    || cs.hasPermission("stonyperms.*"));
        }

        @Override
        public String actorName(Object sender) {
            if (sender instanceof Player player) {
                return player.getName();
            }
            if (sender instanceof CommandSender cs) {
                String name = cs.getName();
                return (name == null || name.isBlank()) ? bridgeConfig.effectiveDefaultActor() : name;
            }
            return bridgeConfig.effectiveDefaultActor();
        }

        @Override
        public void sendMessage(Object sender, String message) {
            if (!(sender instanceof CommandSender cs)) {
                return;
            }
            if (Bukkit.isPrimaryThread() || !isEnabled()) {
                cs.sendMessage(message);
            } else {
                getServer().getScheduler().runTask(StonyPermsBridgePlugin.this, () -> cs.sendMessage(message));
            }
        }

        @Override
        public List<String> onlinePlayerNames() {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }

        @Override
        public Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid) {
            if (nameOrUuid == null || nameOrUuid.isBlank()) {
                return Optional.empty();
            }
            Player player = Bukkit.getPlayerExact(nameOrUuid.trim());
            if (player == null) {
                try {
                    UUID uuid = UUID.fromString(nameOrUuid.trim());
                    player = Bukkit.getPlayer(uuid);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (player == null || !player.isOnline()) {
                return Optional.empty();
            }
            return Optional.of(new OnlinePlayerRef(player.getUniqueId(), player.getName(), contexts(player)));
        }

        @Override
        public void requestRefreshAll() {
            refreshAllAsync();
        }

        @Override
        public void requestRefreshPlayer(UUID uuid) {
            refreshByUuid(uuid);
        }

        @Override
        public boolean reloadBridge() {
            reloadConfig();
            return StonyPermsBridgePlugin.this.reloadBridge();
        }

        @Override
        public String statusSummary() {
            if (bridgeConfig.isStandalone() && localBackend != null) {
                var mgr = localBackend.manager();
                StringBuilder sb = new StringBuilder();
                sb.append("StonyPerms standalone\n");
                sb.append("  mode: standalone\n");
                sb.append("  storage: ").append(mgr.storage().describeTarget()).append('\n');
                sb.append("  revision: ").append(mgr.revision())
                        .append(" (users=").append(mgr.usersCount())
                        .append(", groups=").append(mgr.groupsCount())
                        .append(", tracks=").append(mgr.tracksCount()).append(")\n");
                sb.append("  server context: ")
                        .append(bridgeConfig.serverName().isEmpty() ? "(none)" : bridgeConfig.serverName()).append('\n');
                sb.append("  cached snapshots: ").append(service.cache().size()).append('\n');
                sb.append("  attachments: ").append(applier.size()).append('\n');
                sb.append("  fail-closed: ").append(bridgeConfig.failClosed()).append('\n');
                sb.append("  write: ").append(api.isWriteEnabled()
                        ? "enabled (actor=" + bridgeConfig.effectiveDefaultActor() + ")"
                        : "disabled");
                if (!lastError.isEmpty()) {
                    sb.append("\n  last error: ").append(lastError);
                }
                return sb.toString();
            }
            String sseState;
            StonyPermsEventStream stream = eventStream;
            if (!bridgeConfig.sseEnabled() || stream == null) {
                sseState = "disabled";
            } else if (stream.connected()) {
                sseState = "connected";
            } else if (!stream.lastError().isEmpty()) {
                sseState = "disconnected (" + stream.lastError() + ")";
            } else {
                sseState = "connecting";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("StonyPerms bridge\n");
            sb.append("  api: ").append(bridgeConfig.apiUrl()).append('\n');
            sb.append("  server context: ")
                    .append(bridgeConfig.serverName().isEmpty() ? "(none)" : bridgeConfig.serverName()).append('\n');
            sb.append("  cached snapshots: ").append(service.cache().size()).append('\n');
            sb.append("  attachments: ").append(applier.size()).append('\n');
            sb.append("  fail-closed: ").append(bridgeConfig.failClosed())
                    .append(", stale grace: ").append(bridgeConfig.staleGraceSeconds()).append("s\n");
            sb.append("  write: ").append(api.isWriteEnabled() ? "enabled (actor=" + bridgeConfig.effectiveDefaultActor() + ")" : "disabled")
                    .append('\n');
            sb.append("  sse: ").append(sseState);
            if (!lastError.isEmpty()) {
                sb.append("\n  last error: ").append(lastError);
            }
            return sb.toString();
        }

        @Override
        public Map<String, String> defaultContexts() {
            return StonyPermsBridgePlugin.this.defaultContexts();
        }

        @Override
        public List<String> collectKnownPermissions() {
            return universe == null ? List.of() : universe.collect().stream().sorted().toList();
        }

        @Override
        public Map<String, List<String>> collectExtraContexts() {
            Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            if (!bridgeConfig.serverName().isEmpty()) {
                out.put("server", List.of(bridgeConfig.serverName()));
            }
            List<String> worlds = Bukkit.getWorlds().stream().map(org.bukkit.World::getName).sorted().toList();
            if (!worlds.isEmpty()) {
                out.put("world", worlds);
            }
            return out;
        }

        @Override
        public void sendUrlMessage(Object sender, String prefix, String url, String hoverHint) {
            if (!(sender instanceof CommandSender cs)) {
                return;
            }
            Component comp = Component.text(prefix + " ", NamedTextColor.GREEN)
                    .append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text(hoverHint, NamedTextColor.YELLOW))));
            if (Bukkit.isPrimaryThread() || !isEnabled()) {
                cs.sendMessage(comp);
            } else {
                getServer().getScheduler().runTask(StonyPermsBridgePlugin.this, () -> cs.sendMessage(comp));
            }
        }
    }
}
