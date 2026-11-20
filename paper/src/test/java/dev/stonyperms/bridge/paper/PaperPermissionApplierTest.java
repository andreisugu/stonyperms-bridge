package dev.stonyperms.bridge.paper;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperPermissionApplierTest {

    @Test
    void setsMaterializedValuesOnOneAttachment() {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        PermissionAttachment attachment = mock(PermissionAttachment.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.addAttachment(plugin)).thenReturn(attachment);

        PaperPermissionApplier applier = new PaperPermissionApplier(plugin);
        applier.apply(player, Map.of("Example.Perm", true, "blocked.node", false));

        verify(attachment).setPermission("example.perm", true);
        verify(attachment).setPermission("blocked.node", false);
        verify(player).recalculatePermissions();
    }

    @Test
    void replacingASnapshotRemovesTheOldAttachment() {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        PermissionAttachment first = mock(PermissionAttachment.class);
        PermissionAttachment second = mock(PermissionAttachment.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.addAttachment(plugin)).thenReturn(first, second);

        PaperPermissionApplier applier = new PaperPermissionApplier(plugin);
        applier.apply(player, Map.of("a", true));
        applier.apply(player, Map.of("b", true));

        verify(first).remove();
        verify(second).setPermission("b", true);
    }

    @Test
    void emptySnapshotRemovesGrantsWithoutAddingAttachment() {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        PaperPermissionApplier applier = new PaperPermissionApplier(plugin);
        applier.apply(player, Map.of());

        verify(player, never()).addAttachment(plugin);
        verify(player).recalculatePermissions();
    }
}
