package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.function.Function;

/** Isolated registrar so StonyPermsBridgePlugin never imports PlaceholderAPI directly. */
final class PlaceholderApiRegistrar {

    private PlaceholderApiRegistrar() {
    }

    static Runnable register(
            Plugin plugin,
            StonyPermsBridgeAPI api,
            Function<Player, Map<String, String>> playerContexts,
            Map<String, String> defaultContexts) {
        StonyPermsPlaceholderExpansion expansion = new StonyPermsPlaceholderExpansion(
                api,
                plugin.getPluginMeta().getVersion(),
                plugin.getName(),
                playerContexts,
                defaultContexts);
        if (!expansion.register()) {
            plugin.getLogger().warning("Failed to register PlaceholderAPI expansion");
            return () -> {};
        }
        plugin.getLogger().info("Registered PlaceholderAPI expansion (stonyperms)");
        return () -> {
            try {
                if (expansion.isRegistered()) {
                    expansion.unregister();
                }
            } catch (RuntimeException | NoClassDefFoundError ignored) {
            }
        };
    }
}
