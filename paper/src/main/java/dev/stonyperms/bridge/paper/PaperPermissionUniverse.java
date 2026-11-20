package dev.stonyperms.bridge.paper;

import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Known Bukkit permissions, including everything declared in plugin.yml files. */
public final class PaperPermissionUniverse {

    private final PluginManager pluginManager;

    public PaperPermissionUniverse(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public Set<String> collect() {
        return registered().keySet();
    }

    /** Registered permissions by lowercase name, including their child maps. */
    public Map<String, Permission> registered() {
        Map<String, Permission> permissions = new HashMap<>();
        for (Permission permission : pluginManager.getPermissions()) {
            if (permission != null && permission.getName() != null) {
                permissions.put(permission.getName().toLowerCase(Locale.ROOT), permission);
            }
        }
        return permissions;
    }
}
