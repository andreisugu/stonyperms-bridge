package dev.stonyperms.bridge.paper;

import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Known Bukkit permissions, including everything declared in plugin.yml files. */
public final class PaperPermissionUniverse {

    private final PluginManager pluginManager;

    public PaperPermissionUniverse(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public Set<String> collect() {
        Set<String> names = new HashSet<>();
        for (Permission permission : pluginManager.getPermissions()) {
            if (permission != null && permission.getName() != null) {
                names.add(permission.getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
