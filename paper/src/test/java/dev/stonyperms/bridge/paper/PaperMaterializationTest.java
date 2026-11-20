package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.permissions.Permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperMaterializationTest {


    @Test
    void explicitChildDenialSurvivesParentExpansion() {
        Map<String, Boolean> values = Map.of(
                "example.parent", true,
                "example.use", false);
        Map<String, Permission> registered = Map.of(
                "example.parent", new Permission("example.parent", Map.of("example.use", true)),
                "example.use", new Permission("example.use"));

        Map<String, Boolean> safe = PaperMaterialization.withoutConflictingParents(values, registered);

        assertFalse(safe.containsKey("example.parent"));
        assertFalse(safe.get("example.use"));
    }

    @Test
    void parentIsDroppedWhenAChildHasNoExplicitValue() {
        Map<String, Boolean> values = Map.of("example.parent", true);
        Map<String, Permission> registered = Map.of(
                "example.parent", new Permission("example.parent", Map.of("example.use", true)));

        Map<String, Boolean> safe = PaperMaterialization.withoutConflictingParents(values, registered);

        assertFalse(safe.containsKey("example.parent"));
    }

    @Test
    void failClosedDeniesKnownPermissionsButNotBridgeAdmin() {
        Map<String, Boolean> values = PaperMaterialization.failClosedValues(
                Set.of("example.use", "stonyperms.bridge.admin"));
        assertFalse(values.get("example.use"));
        assertFalse(values.containsKey("stonyperms.bridge.admin"));
    }

    @Test
    void addsLiteralNodesButNotWildcards() {
        Snapshot snapshot = new Snapshot(1, 1, "", 30,
                new UserInfo("00000000-0000-0000-0000-000000000001", "Steve", "default", false,
                        Map.of(), "", ""),
                List.of(),
                List.of(
                        new Node("*", true, Map.of(), 0, "group:admin", 100, false),
                        new Node("example.literal", true, Map.of(), 0, "group:admin", 100, false)));

        Set<String> permissions = PaperMaterialization.permissions(snapshot, Set.of("bukkit.command.help"));

        assertTrue(permissions.contains("bukkit.command.help"));
        assertTrue(permissions.contains("example.literal"));
        assertFalse(permissions.contains("*"));
    }
}
