package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.api.DefaultStonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.BridgeService;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PermissionResolver;
import dev.stonyperms.bridge.core.SnapshotCache;
import dev.stonyperms.bridge.core.Tri;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.cmd.BridgeCommandHandler;
import dev.stonyperms.bridge.core.cmd.CommandPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPermissionManagerTest {

    @Test
    void re2jHandlesLinearRedosAndCasePreservationAndInvalidPatterns() {
        Node redos = new Node("r=(a+)+b", true, 0L, Map.of());
        long startNs = System.nanoTime();
        Node.MatchResult redosMatch = redos.evaluateMatch("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaac");
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertFalse(redosMatch.matched());
        assertTrue(elapsedMs < 250L, "RE2J must evaluate in linear time, took " + elapsedMs + "ms");

        Node shorthandClass = new Node("r=kit\\.\\D+\\.claim", true, 0L, Map.of());
        assertEquals("r=kit\\.\\D+\\.claim", shorthandClass.key());
        assertTrue(shorthandClass.evaluateMatch("kit.vip.claim").matched());
        assertFalse(shorthandClass.evaluateMatch("kit.123.claim").matched());

        Node malformed = new Node("r=[unclosed", true, 0L, Map.of());
        assertFalse(malformed.evaluateMatch("unclosed").matched());
    }

    @Test
    void lifecycleRenameCascadesAuditLogCapAndExpiryCleanup(@TempDir Path tempDir) throws Exception {
        try (LocalPermissionManager mgr = new LocalPermissionManager(StandaloneConfig.defaults(tempDir), null)) {
            mgr.createGroup("builder", 20, "&6Builder", "admin");
            mgr.createGroup("architect", 40, "&bArch", "admin");
            mgr.addGroupParent("architect", "builder", ParentMode.ADD, 0L, 0L, Map.of(), "admin");
            mgr.createTrack("build", List.of("builder", "architect"), "admin");

            UUID steveId = UUID.randomUUID();
            mgr.resolveOrCreateUser(steveId, "Steve", "builder", true, "admin");
            mgr.setPermission(steveId.toString(), "expired.perm", true, 0L, 1000L, Map.of(), "replace", "admin");
            mgr.setPermission(steveId.toString(), "active.perm", true, 0L, 9_999_999_999L, Map.of(), "replace", "admin");
            mgr.setAffix(steveId.toString(), true, 10, "[Old]", 0L, 1000L, Map.of(), "add", "replace", "admin");
            mgr.setMeta(steveId.toString(), "badge", "stale", 0L, 1000L, Map.of(), "replace", "admin");

            mgr.renameGroup("builder", "mason", "admin");
            UserRecord reloadedUser = mgr.fetchUser("Steve");
            assertEquals("mason", reloadedUser.primaryGroup());
            assertEquals(List.of("mason", "architect"), mgr.fetchTrack("build").groups());
            GroupRecord arch = mgr.fetchGroup("architect");
            assertTrue(arch.parents().stream().anyMatch(p -> p.group().equals("mason")));

            // Trigger expiry cleanup directly at epoch 2000
            mgr.cleanupExpired(2000L);
            UserRecord cleaned = mgr.fetchUser(steveId.toString());
            assertTrue(cleaned.nodes().stream().noneMatch(n -> n.key().equals("expired.perm")));
            assertTrue(cleaned.nodes().stream().anyMatch(n -> n.key().equals("active.perm")));
            assertTrue(cleaned.prefixes().isEmpty());
            assertTrue(cleaned.metaEntries().isEmpty());

            // Audit log respects 1000-entry rolling cap
            for (int i = 0; i < 1010; i++) {
                mgr.storage().appendLog(new LogEntry(1700000000L + i, "tester", "user", "Steve", "op-" + i));
            }
            try (LocalPermissionManager reopened = new LocalPermissionManager(StandaloneConfig.defaults(tempDir), null)) {
                assertTrue(reopened.fetchGroup("architect").parents().stream().anyMatch(p -> p.group().equals("mason")));
                assertEquals(JsonLocalStorage.MAX_LOG_ENTRIES, reopened.queryLogs(2000, "", "", "").size());
            }
        }
    }

    @Test
    void portableWebCodecEnforces64KiBCap() throws Exception {
        // High-entropy string keeps decompressed payload well above 64 KiB
        Random rng = new Random(42L);
        StringBuilder sb = new StringBuilder(110_000);
        for (int i = 0; i < 110_000; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        PortableWebCodec.EditorSavePayload huge = new PortableWebCodec.EditorSavePayload();
        JsonLocalStorage.StoredGroup g = new JsonLocalStorage.StoredGroup();
        g.name = "huge";
        JsonLocalStorage.StoredNode n = new JsonLocalStorage.StoredNode();
        n.key = sb.toString();
        n.value = true;
        g.nodes = List.of(n);
        huge.groups = List.of(g);

        String hugeCode = PortableWebCodec.encodeObject(huge);
        ApiException tooLarge = assertThrows(ApiException.class,
                () -> PortableWebCodec.decodeEditorPayload(hugeCode));
        assertEquals("payload_too_large", tooLarge.errorCode());
        String oversizedRaw = PortableWebCodec.PORTABLE_PREFIX + "a".repeat(PortableWebCodec.MAX_PORTABLE_CODE_BYTES + 10);
        assertThrows(ApiException.class, () -> PortableWebCodec.decodeEditorPayload(oversizedRaw));
    }

    @Test
    void backendBridgeServiceVerboseCaptureAndCommandsRoundTrip(@TempDir Path tempDir) throws Exception {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        try (LocalStonyPermsBackend backend = LocalStonyPermsBackend.create(StandaloneConfig.defaults(tempDir), events::add)) {
            java.util.concurrent.Executor exec = Runnable::run;
            SnapshotCache cache = new SnapshotCache(60_000L);
            BridgeService service = new BridgeService(backend, cache, new PermissionResolver(), true, true);
            service.setVerboseObserver((uuid, perm, dec) ->
                    backend.recordResolvedCheck(uuid, perm, dec.value().name().toLowerCase(Locale.ROOT), dec.source()));

            UUID alexId = UUID.randomUUID();
            backend.ensureOnlineUser(alexId, "Alex", "test");
            DefaultStonyPermsBridgeAPI api = new DefaultStonyPermsBridgeAPI(
                    service,
                    backend,
                    exec,
                    true,
                    "console",
                    uuid -> {
                        try {
                            service.refresh(uuid, Map.of("world", "world"));
                        } catch (Exception ignored) {
                        }
                    },
                    () -> {
                        try {
                            service.refresh(alexId, Map.of("world", "world"));
                        } catch (Exception ignored) {
                        }
                    });

            assertTrue(api.isStandalone());
            List<String> messages = new CopyOnWriteArrayList<>();
            List<String> urls = new CopyOnWriteArrayList<>();
            BridgeCommandHandler handler = new BridgeCommandHandler(api, new CommandPlatform() {
                @Override
                public boolean hasAdminPermission(Object sender) {
                    return true;
                }

                @Override
                public boolean hasWritePermission(Object sender) {
                    return true;
                }

                @Override
                public String actorName(Object sender) {
                    return "Console";
                }

                @Override
                public void sendMessage(Object sender, String message) {
                    messages.add(message);
                }

                @Override
                public void sendUrlMessage(Object sender, String prefix, String url, String hoverHint) {
                    urls.add(url);
                    messages.add(prefix + " " + url);
                }

                @Override
                public List<String> onlinePlayerNames() {
                    return List.of("Alex");
                }

                @Override
                public Optional<OnlinePlayerRef> findOnlinePlayer(String nameOrUuid) {
                    if ("Alex".equalsIgnoreCase(nameOrUuid) || alexId.toString().equalsIgnoreCase(nameOrUuid)) {
                        return Optional.of(new OnlinePlayerRef(alexId, "Alex", Map.of("world", "world")));
                    }
                    return Optional.empty();
                }

                @Override
                public void requestRefreshAll() {
                    try {
                        service.refresh(alexId, Map.of("world", "world"));
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void requestRefreshPlayer(UUID uuid) {
                    requestRefreshAll();
                }

                @Override
                public boolean reloadBridge() {
                    return true;
                }

                @Override
                public String statusSummary() {
                    return "standalone-test";
                }

                @Override
                public Map<String, String> defaultContexts() {
                    return Map.of();
                }
            });

            // Auto-ensure + set permission on Alex
            assertTrue(handler.execute("console", "sp", new String[]{"user", "Alex", "permission", "set", "demo.fly", "true"}));
            service.refresh(alexId, Map.of("world", "world"));
            assertEquals(Tri.TRUE, service.resolve(alexId, Map.of("world", "world"), "demo.fly").value());

            // Verbose capture lifecycle
            assertTrue(handler.execute("console", "sp", new String[]{"verbose", "record", "Alex"}));
            assertEquals(Tri.TRUE, service.resolve(alexId, Map.of("world", "world"), "demo.fly").value());
            assertEquals(Tri.UNDEFINED, service.resolve(alexId, Map.of("world", "world"), "demo.missing").value());
            assertTrue(handler.execute("console", "sp", new String[]{"verbose", "stop"}));
            assertTrue(handler.execute("console", "sp", new String[]{"verbose", "paste"}));
            String verboseUrl = urls.stream().filter(u -> u.contains("/verbose#data=")).findFirst().orElse("");
            assertFalse(verboseUrl.isEmpty(), "expected verbose URL in " + messages);
            String vCode = verboseUrl.substring(verboseUrl.indexOf("#data=") + "#data=".length());
            PortableWebCodec.VerboseSessionData vData =
                    PortableWebCodec.decodeObject(vCode, PortableWebCodec.VerboseSessionData.class);
            assertTrue(vData.entries.size() >= 2, "expected captured checks in verbose paste");

            // Rename group + track + info
            assertTrue(handler.execute("console", "sp", new String[]{"group", "donor", "create", "30"}));
            assertTrue(handler.execute("console", "sp", new String[]{"group", "donor", "rename", "supporter"}));
            assertEquals("supporter", backend.fetchGroup("supporter").name(), "messages=" + messages);
            assertTrue(handler.execute("console", "sp", new String[]{"track", "ranks", "rename", "tiers"}));
            assertEquals("tiers", backend.fetchTrack("tiers").name(), "messages=" + messages);
            assertTrue(handler.execute("console", "sp", new String[]{"info"}));
            assertTrue(messages.stream().anyMatch(m -> m.contains("standalone-test")));
        }

        // Verify persistence after backend reload
        try (LocalPermissionManager reopened = new LocalPermissionManager(StandaloneConfig.defaults(tempDir), null)) {
            assertEquals("supporter", reopened.fetchGroup("supporter").name());
            assertEquals("tiers", reopened.fetchTrack("tiers").name());
            assertEquals("true", reopened.check(reopened.fetchUser("Alex").uuid(), "demo.fly", Map.of()).value());
        }
    }
}
