package dev.stonyperms.bridge.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric provider for fabric-permissions-api. Permission checks are answered
 * from a cached StonyPerms snapshot, so the server thread never performs
 * network I/O.
 */
public final class StonyPermsBridgeMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("stonyperms-bridge");
    private static volatile FabricBridgeRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FabricBridgeRuntime current = runtime;
            if (current != null) {
                current.refreshAsync(handler.getPlayer());
            }
        });

        PermissionCheckEvent.EVENT.register((source, permission) -> {
            ServerPlayer player = player(source);
            FabricBridgeRuntime current = runtime;
            if (player == null || current == null) {
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

        registerCommands();
        LOGGER.info("StonyPerms bridge initialized (fabric-permissions-api provider)");
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            FabricBridgeConfig config = FabricBridgeConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricBridgeRuntime fresh = new FabricBridgeRuntime(config, LOGGER);
            runtime = fresh;
            fresh.start(server);
            LOGGER.info("StonyPerms bridge using {} (server context: {})",
                    config.apiUrl(), config.serverName().isEmpty() ? "(none)" : config.serverName());
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
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("spb")
                        .requires(source -> {
                            ServerPlayer player = source.getPlayer();
                            if (player == null) {
                                return true;
                            }
                            if (source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
                                return true;
                            }
                            return Permissions.check(source, "stonyperms.bridge.admin", false);
                        })
                        .then(Commands.literal("status").executes(context -> {
                            FabricBridgeRuntime current = runtime;
                            send(context.getSource(), current == null
                                    ? "StonyPerms bridge is not running"
                                    : current.status());
                            return 1;
                        }))
                        .then(Commands.literal("refresh")
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
                        .then(Commands.literal("reload").executes(context -> {
                            boolean reloaded = reload(context.getSource().getServer());
                            send(context.getSource(), reloaded
                                    ? "StonyPerms bridge reloaded"
                                    : "Reload failed; see server log");
                            return reloaded ? 1 : 0;
                        }))));
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
}
