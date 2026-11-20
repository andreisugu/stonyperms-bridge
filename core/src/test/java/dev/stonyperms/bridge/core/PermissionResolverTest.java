package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionResolverTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    void userExactBeatsUserWildcardAndGroups() {
        Snapshot snapshot = snapshot(
                List.of(new GroupRef("admin", "Admin", 100)),
                List.of(
                        node("gate.command.*", true, "user", 0, true),
                        node("gate.command.stop", false, "user", 0, true),
                        node("gate.command.stop", true, "group:admin", 100, false)));

        assertEquals(Tri.FALSE, resolver.resolve(snapshot, "gate.command.stop", Map.of()).value());
        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "gate.command.server", Map.of()).value());
    }

    @Test
    void userWildcardBeatsHighWeightGroupExact() {
        Snapshot snapshot = snapshot(
                List.of(new GroupRef("admin", "Admin", 100)),
                List.of(
                        node("example.*", true, "user", 0, true),
                        node("example.secret", false, "group:admin", 100, false)));

        Decision decision = resolver.resolve(snapshot, "example.secret", Map.of());
        assertEquals(Tri.TRUE, decision.value());
        assertTrue(decision.source().startsWith("user:wildcard"));
    }

    @Test
    void higherWeightGroupWildcardBeatsLowerWeightExact() {
        Snapshot snapshot = snapshot(
                List.of(new GroupRef("admin", "Admin", 100), new GroupRef("default", "Default", 0)),
                List.of(
                        node("*", true, "group:admin", 100, false),
                        node("gate.command.stop", false, "group:default", 0, false)));

        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "gate.command.stop", Map.of()).value());
    }

    @Test
    void mostSpecificWildcardWinsWithinSource() {
        Snapshot snapshot = snapshot(
                List.of(),
                List.of(
                        node("*", true, "user", 0, true),
                        node("gate.*", false, "user", 0, true),
                        node("gate.command.*", true, "user", 0, true)));

        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "gate.command.server", Map.of()).value());
        assertEquals(Tri.FALSE, resolver.resolve(snapshot, "gate.other", Map.of()).value());
        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "other.permission", Map.of()).value());
    }

    @Test
    void contextBoundNodeOnlyAppliesInMatchingContext() {
        Snapshot snapshot = snapshot(
                List.of(),
                List.of(node("lobby.fly", true, "user", 0, true,
                        Map.of("server", "lobby"))));

        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "lobby.fly", Map.of("server", "lobby")).value());
        assertEquals(Tri.UNDEFINED, resolver.resolve(snapshot, "lobby.fly", Map.of("server", "hub")).value());
    }

    @Test
    void expiredNodeIsIgnored() {
        Snapshot snapshot = snapshot(
                List.of(),
                List.of(
                        new Node("temp.perm", true, Map.of(), 1000, "user", 0, true),
                        new Node("temp.fallback", true, Map.of(), 1000, "group:default", 0, false)));

        assertEquals(Tri.UNDEFINED, resolver.resolve(snapshot, "temp.perm", Map.of(), 1001).value());
        assertEquals(Tri.TRUE, resolver.resolve(snapshot, "temp.fallback", Map.of(), 999).value());
    }

    @Test
    void undefinedWhenNothingMatches() {
        Snapshot snapshot = snapshot(
                List.of(new GroupRef("default", "Default", 0)),
                List.of(node("gate.command.server", true, "group:default", 0, false)));

        Decision decision = resolver.resolve(snapshot, "some.other.node", Map.of());
        assertEquals(Tri.UNDEFINED, decision.value());
        assertFalse(decision.defined());
    }

    @Test
    void materializeProducesConcreteBukkitMap() {
        Snapshot snapshot = snapshot(
                List.of(new GroupRef("admin", "Admin", 100)),
                List.of(
                        node("*", true, "group:admin", 100, false),
                        node("blocked.node", false, "user", 0, true)));

        Map<String, Boolean> values = resolver.materialize(
                snapshot,
                List.of("blocked.node", "some.plugin.perm", "Missing", "some.plugin.perm"),
                Map.of());

        assertEquals(Map.of("blocked.node", false, "some.plugin.perm", true, "missing", true), values);
    }

    private static Snapshot snapshot(List<GroupRef> groups, List<Node> nodes) {
        return new Snapshot(
                1,
                7,
                "2026-01-01T00:00:00Z",
                30,
                new UserInfo("00000000-0000-0000-0000-000000000001", "Steve", "default", false, Map.of(), "", ""),
                groups,
                nodes);
    }

    private static Node node(String key, boolean value, String source, int weight, boolean user) {
        return node(key, value, source, weight, user, Map.of());
    }

    private static Node node(String key, boolean value, String source, int weight, boolean user, Map<String, String> contexts) {
        return new Node(key, value, contexts, 0, source, weight, user);
    }
}
