package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.StonyPermsClient;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper adapter. Applies materialized StonyPerms snapshots as Bukkit
 * PermissionAttachments and refreshes them on join, world change, and a
 * configurable interval.
 */
public final class StonyPermsBridgePlugin extends JavaPlugin implements Listener, TabCompleter {

    private final PermissionResolver resolver = new PermissionResolver();
    private final PaperPermissionApplier applier = new PaperPermissionApplier(this);
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastFailureLogAt = new AtomicLong();

    private ExecutorService io;
    private PaperBridgeConfig bridgeConfig;
    private StonyPermsClient client;
    private BridgeService service;
    private PaperPermissionUniverse universe;
    private int refreshTaskId = -1;
    private volatile String lastError = "";

    @Override
    public void onEnable() {
        io = Executors.newFixedThreadPool(2, runnable -> {
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
        getLogger().info("StonyPerms bridge enabled for " + bridgeConfig.apiUrl());
    }

    @Override
    public void onDisable() {
        stopScheduler();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applier.clear(player);
        }
        if (client != null) {
            client.close();
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

        StonyPermsClient newClient = new StonyPermsClient(
                newConfig.apiUrl(),
                newConfig.token(),
                Duration.ofMillis(getConfig().getInt("api.connect-timeout-ms", 1500)),
                Duration.ofMillis(getConfig().getInt("api.request-timeout-ms", 2500)));
        SnapshotCache cache = new SnapshotCache(newConfig.staleGraceSeconds() * 1000L);
        BridgeService newService = new BridgeService(
                newClient, cache, resolver, newConfig.failClosed(), newConfig.useStaleOnError());

        stopScheduler();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applier.clear(player);
        }
        if (client != null) {
            client.close();
        }

        bridgeConfig = newConfig;
        client = newClient;
        service = newService;
        universe = new PaperPermissionUniverse(getServer().getPluginManager());
        lastError = "";

        if (newConfig.isRemotePlainHttp()) {
            getLogger().warning("api.url uses plain HTTP to a non-loopback host; prefer TLS or a private network");
        }

        startScheduler();
        return true;
    }

    private void startScheduler() {
        long ticks = 20L * bridgeConfig.refreshSeconds();
        refreshTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refreshAsync(player);
            }
        }, ticks, ticks).getTaskId();
    }

    private void stopScheduler() {
        if (refreshTaskId != -1) {
            getServer().getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyPlayer(event.getPlayer());
        refreshAsync(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applier.clear(event.getPlayer());
        refreshing.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshAsync(event.getPlayer());
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
                noteFailure(error);
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
        if (player.isOp() && !bridgeConfig.applyToOps()) {
            applier.clear(player);
            return;
        }
        Map<String, String> contexts = contexts(player);
        SnapshotCache.Lookup lookup = service.lookup(player.getUniqueId(), contexts);
        if (!lookup.present()) {
            applier.apply(player, Map.of());
            return;
        }
        Set<String> permissions = PaperMaterialization.permissions(lookup.snapshot(), universe.collect());
        service.materialize(player.getUniqueId(), contexts, permissions)
                .ifPresentOrElse(
                        values -> applier.apply(player, values),
                        () -> applier.apply(player, Map.of()));
    }

    private Map<String, String> contexts(Player player) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (!bridgeConfig.serverName().isEmpty()) {
            contexts.put("server", bridgeConfig.serverName());
        }
        contexts.put("world", player.getWorld().getName());
        return contexts;
    }

    private void noteFailure(Exception error) {
        long now = System.currentTimeMillis();
        long previous = lastFailureLogAt.get();
        if (bridgeConfig.debug() || now - previous > 300_000) {
            lastFailureLogAt.set(now);
            getLogger().warning("StonyPerms API refresh failed: " + lastError);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <status|refresh|check|reload>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sender.sendMessage("StonyPerms bridge");
                sender.sendMessage("  api: " + bridgeConfig.apiUrl());
                sender.sendMessage("  server context: "
                        + (bridgeConfig.serverName().isEmpty() ? "(none)" : bridgeConfig.serverName()));
                sender.sendMessage("  cached snapshots: " + service.cache().size());
                sender.sendMessage("  attachments: " + applier.size());
                sender.sendMessage("  fail-closed: " + bridgeConfig.failClosed()
                        + ", stale grace: " + bridgeConfig.staleGraceSeconds() + "s");
                if (!lastError.isEmpty()) {
                    sender.sendMessage("  last error: " + lastError);
                }
                return true;
            }
            case "refresh" -> {
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("Player not found: " + args[1]);
                        return true;
                    }
                    refreshAsync(target);
                    sender.sendMessage("Refresh requested for " + target.getName());
                } else {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        refreshAsync(player);
                    }
                    sender.sendMessage("Refresh requested for all online players");
                }
                return true;
            }
            case "check" -> {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /" + label + " check <player> <permission>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not found: " + args[1]);
                    return true;
                }
                String permission = args[2];
                Map<String, String> contexts = contexts(target);
                io.submit(() -> {
                    String message;
                    try {
                        CheckResult result = service.authoritativeCheck(target.getUniqueId(), permission, contexts);
                        message = permission + " = " + result.value() + " (" + result.source() + ")";
                    } catch (Exception error) {
                        message = "check failed: " + error.getClass().getSimpleName();
                    }
                    String finalMessage = message;
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(finalMessage));
                });
                return true;
            }
            case "reload" -> {
                reloadConfig();
                if (reloadBridge()) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        refreshAsync(player);
                    }
                    sender.sendMessage("StonyPerms bridge reloaded");
                } else {
                    sender.sendMessage("Reload failed; see console");
                }
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /" + label + " <status|refresh|check|reload>");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "refresh", "check", "reload"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("refresh") || args[0].equalsIgnoreCase("check"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
