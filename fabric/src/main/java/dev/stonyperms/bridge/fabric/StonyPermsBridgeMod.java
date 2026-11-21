package dev.stonyperms.bridge.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.Decision;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.Tri;
import dev.stonyperms.bridge.core.UserInfo;
import dev.stonyperms.bridge.core.cmd.BridgeCommandHandler;
import dev.stonyperms.bridge.core.cmd.CommandPlatform;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import me.lucko.fabric.api.permissions.v0.OfflineOptionRequestEvent;
import me.lucko.fabric.api.permissions.v0.OfflinePermissionCheckEvent;
import me.lucko.fabric.api.permissions.v0.OptionRequestEvent;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric provider for fabric-permissions-api. Permission checks are answered
 * from a cached StonyPerms snapshot, so the server thread never performs
 * network I/O.
 */
public final class StonyPermsBridgeMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("stonyperms-bridge");
    private static volatile FabricBridgeRuntime runtime;

    public static Optional<StonyPermsBridgeAPI> api() {
        FabricBridgeRuntime current = runtime;
        return current == null ? Optional.empty() : Optional.of(current.api());
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FabricBridgeRuntime current = runtime;
            if (current != null) {
                ServerPlayer player = handler.getPlayer();
                if (current.config().isStandalone()) {
                    current.ensureAndRefreshSync(player);
                    current.snapshot(player).ifPresentOrElse(
                            snap -> FabricDisplayManager.apply(server, player, snap, current),
                            () -> {
                                if (current.isOpBypassed(server, player) || current.config().failClosed()) {
                                    FabricDisplayManager.clear(server, player.getScoreboardName());
                                }
                            });
                } else {
                    current.snapshot(player).ifPresentOrElse(
                            snap -> FabricDisplayManager.apply(server, player, snap, current),
                            () -> {
                                if (current.isOpBypassed(server, player) || current.config().failClosed()) {
                                    FabricDisplayManager.clear(server, player.getScoreboardName());
                                }
                            });
                    current.refreshAsync(player);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                FabricDisplayManager.clear(server, handler.getPlayer().getScoreboardName()));

        StonyPermsEvents.USER_UPDATED.register((uuid, snapshot, previousRevision) -> {
            FabricBridgeRuntime current = runtime;
            if (current == null) {
                return;
            }
            MinecraftServer srv = current.server();
            if (srv == null) {
                return;
            }
            ServerPlayer online = srv.getPlayerList().getPlayer(uuid);
            if (online != null) {
                FabricDisplayManager.apply(srv, online, snapshot, current);
            }
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            FabricBridgeRuntime current = runtime;
            if (sender == null || current == null || !current.config().chatEnabled()) {
                return true;
            }
            MinecraftServer srv = current.server();
            if (srv == null || current.isOpBypassed(srv, sender)) {
                return true;
            }
            UserInfo user = current.snapshot(sender).map(Snapshot::user).orElse(null);
            Component formatted = FabricDisplayManager.formatChatMessage(
                    current.config().chatFormat(),
                    user,
                    sender.getScoreboardName(),
                    message.signedContent());
            srv.getPlayerList().broadcastSystemMessage(formatted, false);
            return false;
        });

        PermissionCheckEvent.EVENT.register((source, permission) -> {
            ServerPlayer player = player(source);
            FabricBridgeRuntime current = runtime;
            if (player == null || current == null) {
                return TriState.DEFAULT;
            }
            MinecraftServer srv = source instanceof CommandSourceStack stack ? stack.getServer() : null;
            if (current.isOpBypassed(srv, player)) {
                return TriState.DEFAULT;
            }
            return FabricPermissionMapper.map(current.resolve(player, permission));
        });

        OptionRequestEvent.EVENT.register((source, key) -> {
            ServerPlayer player = player(source);
            FabricBridgeRuntime current = runtime;
            if (player == null || current == null) {
                return Optional.empty();
            }
            return current.option(player, key);
        });

        OfflinePermissionCheckEvent.EVENT.register((uuid, permission) -> {
            FabricBridgeRuntime current = runtime;
            if (current == null) {
                return CompletableFuture.completedFuture(TriState.DEFAULT);
            }
            return CompletableFuture.completedFuture(
                    FabricPermissionMapper.map(current.resolveOffline(uuid, permission)));
        });

        OfflineOptionRequestEvent.EVENT.register((uuid, key) -> {
            FabricBridgeRuntime current = runtime;
            if (current == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return CompletableFuture.completedFuture(current.optionOffline(uuid, key));
        });

        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> node) {
                FabricBridgeRuntime current = runtime;
                if (current == null || context == null || node == null) {
                    return null;
                }
                MinecraftServer srv = context.get(PermissionContext.SERVER);
                if (srv == null) {
                    srv = current.server();
                }
                ServerPlayer player = srv == null ? null : srv.getPlayerList().getPlayer(context.uuid());
                if (player != null && current.isOpBypassed(srv, player)) {
                    return null;
                }
                String raw = node.key().getNamespace() + "." + node.key().getPath();
                String normalized = node.key().getNamespace() + "." + node.key().getPath().replace('_', '.');
                Decision decision = player != null
                        ? current.resolve(player, normalized)
                        : current.resolveOffline(context.uuid(), normalized);
                if (!decision.defined() && !raw.equals(normalized)) {
                    decision = player != null
                            ? current.resolve(player, raw)
                            : current.resolveOffline(context.uuid(), raw);
                }
                if (!decision.defined()) {
                    return null;
                }
                try {
                    return node.cast(decision.value() == Tri.TRUE);
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        });

        registerCommands();
        LOGGER.info("StonyPerms bridge initialized (fabric-permissions-api provider)");
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            FabricBridgeConfig config = FabricBridgeConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricBridgeRuntime fresh = new FabricBridgeRuntime(config, LOGGER);
            runtime = fresh;
            fresh.start(server);
            if (config.isStandalone()) {
                LOGGER.info("StonyPerms standalone using {} (server context: {}, write={}, applyToOps={})",
                        fresh.localBackend() != null ? fresh.localBackend().manager().storage().describeTarget() : config.standalone().storageDir(),
                        config.serverName().isEmpty() ? "(none)" : config.serverName(),
                        fresh.api().isWriteEnabled(),
                        config.applyToOps());
            } else {
                LOGGER.info("StonyPerms bridge using {} (server context: {}, write={}, sse={}, applyToOps={})",
                        config.apiUrl(),
                        config.serverName().isEmpty() ? "(none)" : config.serverName(),
                        fresh.api().isWriteEnabled(),
                        config.sseEnabled(),
                        config.applyToOps());
            }
            if (config.isRemotePlainHttp()) {
                LOGGER.warn("api.url uses plain HTTP to a non-loopback host; prefer TLS or a private network");
            }
        } catch (Exception error) {
            runtime = null;
            LOGGER.error("StonyPerms bridge disabled: {}", error.getMessage());
        }
    }

    private void shutdown() {
        FabricBridgeRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
        }
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(buildCommandTree("spb"));
            if (FabricBridgeConfig.peekIsStandalone(FabricLoader.getInstance().getConfigDir())) {
                dispatcher.register(buildCommandTree("sp"));
                dispatcher.register(buildCommandTree("stonyperms"));
            }
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildCommandTree(
            String cmdLabel) {
        return Commands.literal(cmdLabel)
                .requires(source -> hasAdminPermission(source) || hasWritePermission(source))
                .then(Commands.literal("status")
                        .requires(StonyPermsBridgeMod::hasAdminPermission)
                        .executes(context -> {
                            FabricBridgeRuntime current = runtime;
                            send(context.getSource(), current == null
                                    ? "StonyPerms bridge is not running"
                                    : current.status());
                            return 1;
                        }))
                .then(Commands.literal("info")
                        .requires(StonyPermsBridgeMod::hasAdminPermission)
                        .executes(context -> {
                            FabricBridgeRuntime current = runtime;
                            send(context.getSource(), current == null
                                    ? "StonyPerms bridge is not running"
                                    : current.status());
                            return 1;
                        }))
                .then(Commands.literal("refresh")
                        .requires(StonyPermsBridgeMod::hasAdminPermission)
                        .executes(context -> {
                            FabricBridgeRuntime current = runtime;
                            if (current == null) {
                                send(context.getSource(), "StonyPerms bridge is not running");
                                return 0;
                            }
                            current.refreshAll(context.getSource().getServer());
                            send(context.getSource(), "Refresh requested for all online players");
                            return 1;
                        })
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    FabricBridgeRuntime current = runtime;
                                    if (current == null) {
                                        send(context.getSource(), "StonyPerms bridge is not running");
                                        return 0;
                                    }
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    current.refreshAsync(target);
                                    send(context.getSource(), "Refresh requested for " + target.getName().getString());
                                    return 1;
                                })))
                .then(Commands.literal("check")
                        .requires(StonyPermsBridgeMod::hasAdminPermission)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("permission", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            FabricBridgeRuntime current = runtime;
                                            if (current == null) {
                                                send(context.getSource(), "StonyPerms bridge is not running");
                                                return 0;
                                            }
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                            String permission = StringArgumentType.getString(context, "permission");
                                            current.authoritativeCheck(target, permission).whenComplete((result, error) -> {
                                                String message = error != null
                                                        ? "check failed: " + rootCause(error).getClass().getSimpleName()
                                                        : permission + " = " + result.value() + " (" + result.source() + ")";
                                                context.getSource().getServer().execute(
                                                        () -> send(context.getSource(), message));
                                            });
                                            return 1;
                                        }))))
                .then(Commands.literal("reload")
                        .requires(StonyPermsBridgeMod::hasAdminPermission)
                        .executes(context -> {
                            boolean reloaded = reload(context.getSource().getServer());
                            send(context.getSource(), reloaded
                                    ? "StonyPerms bridge reloaded"
                                    : "Reload failed; see server log");
                            return reloaded ? 1 : 0;
                        })
                        .then(Commands.literal("remote")
                                .requires(StonyPermsBridgeMod::hasWritePermission)
                                .executes(context -> dispatchShared(context, cmdLabel, "reload", "remote"))))
                .then(sharedSubtree(cmdLabel, "search", false))
                .then(sharedSubtree(cmdLabel, "sync", true))
                .then(sharedSubtree(cmdLabel, "networksync", true))
                .then(sharedSubtree(cmdLabel, "bulkupdate", true))
                .then(sharedSubtree(cmdLabel, "user", false))
                .then(sharedSubtree(cmdLabel, "group", false))
                .then(sharedSubtree(cmdLabel, "track", false))
                .then(sharedSubtree(cmdLabel, "log", true))
                .then(sharedSubtree(cmdLabel, "editor", true))
                .then(sharedSubtree(cmdLabel, "applyedits", true))
                .then(sharedSubtree(cmdLabel, "tree", true))
                .then(sharedSubtree(cmdLabel, "verbose", true));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> sharedSubtree(
            String cmdLabel, String literal, boolean writeOnly) {
        var builder = Commands.literal(literal);
        if (writeOnly) {
            builder.requires(StonyPermsBridgeMod::hasWritePermission);
        }
        return builder
                .executes(context -> dispatchShared(context, cmdLabel, literal, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(suggestFor(literal))
                        .executes(context -> dispatchShared(
                                context, cmdLabel, literal, StringArgumentType.getString(context, "args"))));
    }

    private static int dispatchShared(
            CommandContext<CommandSourceStack> context, String cmdLabel, String root, String rawTail) {
        FabricBridgeRuntime current = runtime;
        if (current == null) {
            send(context.getSource(), "StonyPerms bridge is not running");
            return 0;
        }
        String[] args = combineRootAndTail(root, rawTail, false);
        BridgeCommandHandler handler = new BridgeCommandHandler(
                current.api(), new FabricCommandPlatform(context.getSource().getServer(), current));
        handler.execute(context.getSource(), cmdLabel, args);
        return 1;
    }

    private static SuggestionProvider<CommandSourceStack> suggestFor(String root) {
        return (context, builder) -> {
            FabricBridgeRuntime current = runtime;
            if (current == null) {
                return builder.buildFuture();
            }
            String remaining = builder.getRemaining();
            String[] args = combineRootAndTail(root, remaining, true);
            BridgeCommandHandler handler = new BridgeCommandHandler(
                    current.api(), new FabricCommandPlatform(context.getSource().getServer(), current));
            List<String> suggestions = handler.tabComplete(context.getSource(), args);

            int lastSpace = remaining.lastIndexOf(' ');
            int offset = builder.getStart() + (lastSpace >= 0 ? lastSpace + 1 : 0);
            var offsetBuilder = builder.createOffset(offset);
            for (String candidate : suggestions) {
                offsetBuilder.suggest(candidate);
            }
            return offsetBuilder.buildFuture();
        };
    }

    static String[] combineRootAndTail(String root, String tail, boolean preserveTrailingEmpty) {
        List<String> parts = new ArrayList<>();
        parts.add(root);
        if (tail != null && !tail.isEmpty()) {
            String trimmedLeading = tail.stripLeading();
            if (!trimmedLeading.isEmpty()) {
                for (String tok : trimmedLeading.split("\\s+")) {
                    if (!tok.isEmpty()) {
                        parts.add(tok);
                    }
                }
            }
            if (preserveTrailingEmpty && tail.endsWith(" ")) {
                parts.add("");
            }
        } else if (preserveTrailingEmpty) {
            parts.add("");
        }
        return parts.toArray(String[]::new);
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        if (source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
            return true;
        }
        return Permissions.check(source, "stonyperms.bridge.admin", false)
                || Permissions.check(source, "stonyperms.admin", false)
                || Permissions.check(source, "stonyperms.*", false);
    }

    private static boolean hasWritePermission(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        if (source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
            return true;
        }
        return Permissions.check(source, "stonyperms.bridge.write", false)
                || Permissions.check(source, "stonyperms.admin", false)
                || Permissions.check(source, "stonyperms.*", false);
    }

    private static boolean reload(MinecraftServer server) {
        FabricBridgeRuntime old = runtime;
        try {
            FabricBridgeConfig config = FabricBridgeConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricBridgeRuntime fresh = new FabricBridgeRuntime(config, LOGGER);
            fresh.start(server);
            runtime = fresh;
            if (old != null) {
                old.close();
            }
            return true;
        } catch (Exception error) {
            LOGGER.error("StonyPerms bridge reload failed: {}", error.getMessage());
            return false;
        }
    }

    private static ServerPlayer player(SharedSuggestionProvider source) {
        if (source instanceof CommandSourceStack stack) {
            return stack.getPlayer();
        }
        return null;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record FabricCommandPlatform(MinecraftServer server, FabricBridgeRuntime runtime)
            implements CommandPlatform {

        @Override
        public boolean hasAdminPermission(Object sender) {
            return sender instanceof CommandSourceStack stack && StonyPermsBridgeMod.hasAdminPermission(stack);
        }

        @Override
        public boolean hasWritePermission(Object sender) {
            return sender instanceof CommandSourceStack stack && StonyPermsBridgeMod.hasWritePermission(stack);
        }

        @Override
        public String actorName(Object sender) {
            if (sender instanceof CommandSourceStack stack) {
                ServerPlayer player = stack.getPlayer();
                if (player != null) {
                    return player.getName().getString();
                }
                String textName = stack.getTextName();
                return (textName == null || textName.isBlank()) ? runtime.config().effectiveDefaultActor() : textName;
            }
            return runtime.config().effectiveDefaultActor();
        }

        @Override
        public void sendMessage(Object sender, String message) {
            if (!(sender instanceof CommandSourceStack stack)) {
                return;
            }
            if (server.isSameThread()) {
                send(stack, message);
            } else {
                server.execute(() -> send(stack, message));
            }
        }

        @Override
        public List<String> onlinePlayerNames() {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                names.add(p.getName().getString());
            }
            return names;
        }

        @Override
        public Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid) {
            if (nameOrUuid == null || nameOrUuid.isBlank()) {
                return Optional.empty();
            }
            ServerPlayer player = server.getPlayerList().getPlayerByName(nameOrUuid.trim());
            if (player == null) {
                try {
                    UUID uuid = UUID.fromString(nameOrUuid.trim());
                    player = server.getPlayerList().getPlayer(uuid);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (player == null) {
                return Optional.empty();
            }
            return Optional.of(new OnlinePlayerRef(
                    player.getUUID(), player.getName().getString(), runtime.contexts(player)));
        }

        @Override
        public void requestRefreshAll() {
            runtime.refreshServerAll();
        }

        @Override
        public void requestRefreshPlayer(UUID uuid) {
            runtime.refreshByUuid(uuid);
        }

        @Override
        public boolean reloadBridge() {
            return StonyPermsBridgeMod.reload(server);
        }

        @Override
        public String statusSummary() {
            return runtime.status();
        }

        @Override
        public java.util.Map<String, String> defaultContexts() {
            return runtime.defaultContexts();
        }

        @Override
        public java.util.Map<String, List<String>> collectExtraContexts() {
            java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> def = runtime.defaultContexts();
            if (def.containsKey("server")) {
                out.put("server", List.of(def.get("server")));
            }
            List<String> worlds = new ArrayList<>();
            for (var lvl : server.getAllLevels()) {
                worlds.add(lvl.dimension().toString());
            }
            if (!worlds.isEmpty()) {
                out.put("world", worlds);
            }
            return out;
        }
    }
}
