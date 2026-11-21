package dev.stonyperms.bridge.core.local;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.BulkConstraintRecord;
import dev.stonyperms.bridge.core.BulkUpdateSummaryRecord;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.MetaEntry;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.SearchResultRecord;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.Tri;
import dev.stonyperms.bridge.core.UserRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the shared canonical conformance suite in {@code core/src/test/resources/spec/conformance/}
 * identically to {@code gate-stonyperms/manager/conformance_test.go}.
 */
class ConformanceSuiteTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void conformanceEvalVectors() throws Exception {
        EvalSpecFile spec = GSON.fromJson(readSpec("conformance/eval.json"), EvalSpecFile.class);
        assertNotNull(spec);
        assertNotNull(spec.groups);
        assertNotNull(spec.cases);

        Map<String, GroupRecord> groups = new LinkedHashMap<>();
        for (JsonLocalStorage.StoredGroup sg : spec.groups) {
            GroupRecord g = JsonLocalStorage.fromStoredGroup(sg);
            groups.put(g.name().toLowerCase(Locale.ROOT), g);
        }

        for (EvalSpecCase tc : spec.cases) {
            LocalPermissionEngine engine = new LocalPermissionEngine();
            if (tc.primaryGroupCalculation != null && !tc.primaryGroupCalculation.isEmpty()) {
                engine.setPrimaryGroupCalculation(tc.primaryGroupCalculation);
            }
            if (tc.stack != null) {
                engine.setStackConfig(new StandaloneConfig.StackConfig(
                        tc.stack.prefixEnabled,
                        tc.stack.prefixStartSpacer,
                        tc.stack.prefixMidSpacer,
                        tc.stack.prefixEndSpacer,
                        tc.stack.suffixEnabled,
                        tc.stack.suffixStartSpacer,
                        tc.stack.suffixMidSpacer,
                        tc.stack.suffixEndSpacer,
                        tc.stack.deduplicate));
            }
            UserRecord user = tc.user == null ? null : JsonLocalStorage.fromStoredUser(tc.user);
            Map<String, String> contexts = tc.contexts == null ? Map.of() : tc.contexts;

            if (tc.checks != null) {
                for (EvalCheck chk : tc.checks) {
                    LocalPermissionEngine.EngineCheckResult got =
                            engine.evaluateAt(user, groups, chk.permission, contexts, tc.now);
                    assertEquals(parseTri(chk.expected), got.result(),
                            tc.name + " perm=" + chk.permission + " result");
                    assertEquals(chk.source, got.source(),
                            tc.name + " perm=" + chk.permission + " source");
                }
            }
            if (tc.groupChecks != null) {
                for (EvalGroupCheck gchk : tc.groupChecks) {
                    GroupRecord grp = groups.get(gchk.group.toLowerCase(Locale.ROOT));
                    LocalPermissionEngine.EngineCheckResult got =
                            engine.groupPermissionValueAt(grp, groups, gchk.permission, contexts, tc.now);
                    assertEquals(parseTri(gchk.expected), got.result(),
                            tc.name + " group=" + gchk.group + " perm=" + gchk.permission + " result");
                    assertEquals(gchk.source, got.source(),
                            tc.name + " group=" + gchk.group + " perm=" + gchk.permission + " source");
                }
            }
            if (tc.expectedPrimaryGroup != null && !tc.expectedPrimaryGroup.isEmpty()) {
                assertEquals(tc.expectedPrimaryGroup,
                        engine.resolvePrimaryGroupAt(user, groups, contexts, "default", tc.now),
                        tc.name + " primaryGroup");
            }
            if (tc.expectedPrefix != null) {
                assertEquals(tc.expectedPrefix,
                        engine.getPrefixAt(user, groups, contexts, tc.now),
                        tc.name + " prefix");
            }
            if (tc.expectedSuffix != null) {
                assertEquals(tc.expectedSuffix,
                        engine.getSuffixAt(user, groups, contexts, tc.now),
                        tc.name + " suffix");
            }
            if (tc.expectedMeta != null) {
                assertEquals(tc.expectedMeta,
                        engine.resolveAllMetaAt(user, groups, contexts, tc.now),
                        tc.name + " meta");
            }
            if (tc.metaQueries != null) {
                for (EvalMetaQuery mq : tc.metaQueries) {
                    var got = engine.getMetaWithContextAt(user, groups, mq.key, contexts, tc.now);
                    assertEquals(mq.found, got.isPresent(), tc.name + " metaQuery=" + mq.key + " found");
                    assertEquals(mq.value == null ? "" : mq.value, got.orElse(""),
                            tc.name + " metaQuery=" + mq.key + " value");
                }
            }
        }
    }

    @Test
    void conformanceMutationVectors(@TempDir Path tempDir) throws Exception {
        MutationsSpecFile doc = GSON.fromJson(readSpec("conformance/mutations.json"), MutationsSpecFile.class);
        assertNotNull(doc);
        long now = doc.baseTime;

        for (int i = 0; i < doc.tempModifiers.size(); i++) {
            TempModifierCase tm = doc.tempModifiers.get(i);
            switch (tm.kind) {
                case "node" -> {
                    List<Node> base = LocalPermissionManager.addNodeWithModifier(
                            List.of(), new Node("test.perm", true, tm.initialExpiry, Map.of()), "replace", now);
                    if (tm.expectError) {
                        int idx = i;
                        assertThrows(ApiException.class, () -> LocalPermissionManager.addNodeWithModifier(
                                base, new Node("test.perm", true, tm.newExpiry, Map.of()), tm.modifier, now),
                                "tempModifier[" + idx + "] node");
                    } else {
                        List<Node> updated = LocalPermissionManager.addNodeWithModifier(
                                base, new Node("test.perm", true, tm.newExpiry, Map.of()), tm.modifier, now);
                        assertEquals(tm.expectedExpiry, updated.getFirst().expiry(),
                                "tempModifier[" + i + "] node expiry");
                    }
                }
                case "parent" -> {
                    List<ParentEntry> base = LocalPermissionManager.addParentWithModifier(
                            List.of(), new ParentEntry("vip", tm.initialExpiry, Map.of()), "replace", now);
                    if (tm.expectError) {
                        int idx = i;
                        assertThrows(ApiException.class, () -> LocalPermissionManager.addParentWithModifier(
                                base, new ParentEntry("vip", tm.newExpiry, Map.of()), tm.modifier, now),
                                "tempModifier[" + idx + "] parent");
                    } else {
                        List<ParentEntry> updated = LocalPermissionManager.addParentWithModifier(
                                base, new ParentEntry("vip", tm.newExpiry, Map.of()), tm.modifier, now);
                        assertEquals(tm.expectedExpiry, updated.getFirst().expiry(),
                                "tempModifier[" + i + "] parent expiry");
                    }
                }
                case "affix" -> {
                    List<PriorityMeta> base = LocalPermissionManager.addAffixWithModifierList(
                            List.of(), new PriorityMeta(10, "A", tm.initialExpiry, Map.of()), "replace", now);
                    if (tm.expectError) {
                        int idx = i;
                        assertThrows(ApiException.class, () -> LocalPermissionManager.addAffixWithModifierList(
                                base, new PriorityMeta(10, "A", tm.newExpiry, Map.of()), tm.modifier, now),
                                "tempModifier[" + idx + "] affix");
                    } else {
                        List<PriorityMeta> updated = LocalPermissionManager.addAffixWithModifierList(
                                base, new PriorityMeta(10, "A", tm.newExpiry, Map.of()), tm.modifier, now);
                        assertEquals(tm.expectedExpiry, updated.getFirst().expiry(),
                                "tempModifier[" + i + "] affix expiry");
                    }
                }
                case "meta" -> {
                    var initRes = LocalPermissionManager.setMetaEntryList(
                            Map.of(), List.of(), new MetaEntry("tag", "v1", 0, tm.initialExpiry, Map.of()), "replace", now);
                    if (tm.expectError) {
                        int idx = i;
                        assertThrows(ApiException.class, () -> LocalPermissionManager.setMetaEntryList(
                                initRes.meta(), initRes.entries(),
                                new MetaEntry("tag", "v2", 0, tm.newExpiry, Map.of()), tm.modifier, now),
                                "tempModifier[" + idx + "] meta");
                    } else {
                        var updated = LocalPermissionManager.setMetaEntryList(
                                initRes.meta(), initRes.entries(),
                                new MetaEntry("tag", "v2", 0, tm.newExpiry, Map.of()), tm.modifier, now);
                        assertEquals(tm.expectedExpiry, updated.stored().expiry(),
                                "tempModifier[" + i + "] meta expiry");
                    }
                }
                default -> throw new AssertionError("Unknown tempModifier kind: " + tm.kind);
            }
        }

        // Track & rename cascade flow
        Path trackDir = tempDir.resolve("track-flow");
        try (LocalPermissionManager mgr = new LocalPermissionManager(StandaloneConfig.defaults(trackDir), null)) {
            for (String gName : doc.trackAndRenameFlow.groups) {
                try {
                    mgr.fetchGroup(gName);
                } catch (ApiException notFound) {
                    mgr.createGroup(gName, 10, gName, "conformance");
                }
            }
            mgr.createTrack(doc.trackAndRenameFlow.trackName, doc.trackAndRenameFlow.trackGroups, "conformance");
            UUID uid = UUID.fromString(doc.trackAndRenameFlow.user.uuid);
            mgr.resolveOrCreateUser(
                    uid, doc.trackAndRenameFlow.user.username, doc.trackAndRenameFlow.user.primaryGroup, true, "conformance");

            String activeTrack = doc.trackAndRenameFlow.trackName;
            for (int i = 0; i < doc.trackAndRenameFlow.steps.size(); i++) {
                TrackStep step = doc.trackAndRenameFlow.steps.get(i);
                switch (step.op) {
                    case "promote", "demote" -> {
                        boolean promote = "promote".equals(step.op);
                        if (step.expectError) {
                            assertThrows(ApiException.class,
                                    () -> mgr.stepUserTrack(uid.toString(), step.track, promote, "conformance"),
                                    "step " + i + " " + step.op);
                        } else {
                            TrackActionResult res = mgr.stepUserTrack(uid.toString(), step.track, promote, "conformance");
                            assertEquals(step.expectFrom, res.fromGroup(), "step " + i + " fromGroup");
                            assertEquals(step.expectTo, res.toGroup(), "step " + i + " toGroup");
                            assertEquals(step.expectPrimary, mgr.fetchUser(uid.toString()).primaryGroup(),
                                    "step " + i + " primary");
                        }
                    }
                    case "renameGroup" -> {
                        mgr.renameGroup(step.oldName, step.newName, "conformance");
                        assertEquals(step.expectPrimary, mgr.fetchUser(uid.toString()).primaryGroup(),
                                "step " + i + " primary after renameGroup");
                        assertEquals(step.expectTrackGroups, mgr.fetchTrack(activeTrack).groups(),
                                "step " + i + " track groups after renameGroup");
                    }
                    case "renameTrack" -> {
                        mgr.renameTrack(step.oldName, step.newName, "conformance");
                        activeTrack = step.newName;
                    }
                    default -> throw new AssertionError("Unknown track step op: " + step.op);
                }
            }
        }

        // Bulk update and search on fresh default storage
        Path bulkDir = tempDir.resolve("bulk-flow");
        try (LocalPermissionManager mgr = new LocalPermissionManager(StandaloneConfig.defaults(bulkDir), null)) {
            BulkAndSearchSpec bs = doc.bulkAndSearch;
            SearchResultRecord sr = mgr.search(bs.searchQuery);
            assertEquals(bs.expectedGroupHitCount, sr.groupHits().size(), "search group hits");

            BulkUpdateSummaryRecord prev = mgr.bulkUpdate(
                    bs.preview.scope, bs.preview.action, bs.preview.field, bs.preview.value,
                    bs.preview.constraints, true, "conformance");
            assertEquals(bs.preview.expectGroupsChanged, prev.groupsChanged());
            assertEquals(bs.preview.expectNodesAffected, prev.nodesAffected());
            assertTrue(prev.dryRun());

            BulkUpdateSummaryRecord applied = mgr.bulkUpdate(
                    bs.apply.scope, bs.apply.action, bs.apply.field, bs.apply.value,
                    bs.apply.constraints, false, "conformance");
            assertEquals(bs.apply.expectGroupsChanged, applied.groupsChanged());
            assertEquals(bs.apply.expectNodesAffected, applied.nodesAffected());
            assertFalse(applied.dryRun());

            BulkUpdateSummaryRecord deleted = mgr.bulkUpdate(
                    bs.delete.scope, bs.delete.action, null, null,
                    bs.delete.constraints, false, "conformance");
            assertEquals(bs.delete.expectGroupsChanged, deleted.groupsChanged());
            assertEquals(bs.delete.expectNodesAffected, deleted.nodesAffected());
        }
    }

    @Test
    void conformanceInteropStorageAndPortableCode(@TempDir Path tempDir) throws Exception {
        copyInteropResource("groups.json", tempDir.resolve("groups.json"));
        copyInteropResource("tracks.json", tempDir.resolve("tracks.json"));
        copyInteropResource("logs.json", tempDir.resolve("logs.json"));
        Files.createDirectories(tempDir.resolve("users"));
        copyInteropResource(
                "users/550e8400-e29b-41d4-a716-446655440000.json",
                tempDir.resolve("users/550e8400-e29b-41d4-a716-446655440000.json"));

        ExpectedSpecFile exp = GSON.fromJson(readSpec("conformance/expected.json"), ExpectedSpecFile.class);
        UUID uid = UUID.fromString(exp.userUuid);

        try (LocalPermissionManager mgr = new LocalPermissionManager(StandaloneConfig.defaults(tempDir), null)) {
            for (ExpectedScenario sc : exp.scenarios) {
                Map<String, String> contexts = sc.contexts == null ? Map.of() : sc.contexts;
                Snapshot snap = mgr.effectiveSnapshot(uid, contexts);
                assertEquals(sc.primaryGroup, snap.user().primaryGroup(), sc.name + " primaryGroup");
                assertEquals(sc.prefix, snap.user().prefix(), sc.name + " prefix");
                assertEquals(sc.suffix, snap.user().suffix(), sc.name + " suffix");
                List<String> gotGroups = snap.groups().stream().map(g -> g.name()).toList();
                assertEquals(sc.groups, gotGroups, sc.name + " groups");
                assertEquals(sc.meta, snap.user().meta(), sc.name + " meta");

                for (ExpectedCheck chk : sc.checks) {
                    CheckResult res = mgr.check(uid, chk.permission, contexts);
                    assertEquals(chk.result, res.value(), sc.name + " check " + chk.permission + " value");
                    assertEquals(chk.source, res.source(), sc.name + " check " + chk.permission + " source");
                }
            }

            String portableCode = readSpec("conformance/portable_code.txt").trim();
            assertTrue(portableCode.startsWith(PortableWebCodec.PORTABLE_PREFIX));
            mgr.applyEditorCode(portableCode, "conformance");

            GroupRecord builder = mgr.fetchGroup(exp.portableApply.expectedGroup);
            assertEquals(exp.portableApply.expectedGroupWeight, builder.weight());
            TrackRecord track = mgr.fetchTrack(exp.portableApply.expectedTrack);
            assertEquals(exp.portableApply.expectedTrackGroups, track.groups());
            assertEquals(exp.portableApply.expectedGroupsCount, mgr.groupsCount());

            // Also confirm Java-exported portable editor hash decodes symmetrically
            var session = mgr.createEditorSession("conformance", "sp", "", "", "", List.of(), Map.of());
            int hashIdx = session.url().indexOf("#data=");
            assertTrue(hashIdx > 0, "editor URL must include #data=");
            String encoded = session.url().substring(hashIdx + "#data=".length());
            PortableWebCodec.EditorSavePayload decoded = PortableWebCodec.decodeEditorPayload(encoded);
            assertEquals(exp.portableApply.expectedGroupsCount, decoded.groups.size());
        }
    }

    private static void copyInteropResource(String rel, Path target) throws IOException {
        Files.writeString(target, readSpec("conformance/interop_storage/" + rel), StandardCharsets.UTF_8);
    }

    private static String readSpec(String rel) throws IOException {
        try (InputStream in = ConformanceSuiteTest.class.getResourceAsStream("/spec/" + rel)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(Path.of("src/test/resources/spec/" + rel), StandardCharsets.UTF_8);
    }

    private static Tri parseTri(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true" -> Tri.TRUE;
            case "false" -> Tri.FALSE;
            default -> Tri.UNDEFINED;
        };
    }

    private static final class EvalSpecFile {
        List<JsonLocalStorage.StoredGroup> groups;
        List<EvalSpecCase> cases;
    }

    private static final class EvalSpecCase {
        String name;
        JsonLocalStorage.StoredUser user;
        Map<String, String> contexts;
        long now;
        String primaryGroupCalculation;
        StackSpec stack;
        List<EvalCheck> checks;
        List<EvalGroupCheck> groupChecks;
        String expectedPrimaryGroup;
        String expectedPrefix;
        String expectedSuffix;
        Map<String, String> expectedMeta;
        List<EvalMetaQuery> metaQueries;
    }

    private static final class StackSpec {
        boolean prefixEnabled;
        String prefixStartSpacer;
        String prefixMidSpacer;
        String prefixEndSpacer;
        boolean suffixEnabled;
        String suffixStartSpacer;
        String suffixMidSpacer;
        String suffixEndSpacer;
        boolean deduplicate;
    }

    private static final class EvalCheck {
        String permission;
        String expected;
        String source;
    }

    private static final class EvalGroupCheck {
        String group;
        String permission;
        String expected;
        String source;
    }

    private static final class EvalMetaQuery {
        String key;
        boolean found;
        String value;
    }

    private static final class MutationsSpecFile {
        long baseTime;
        List<TempModifierCase> tempModifiers;
        TrackAndRenameFlow trackAndRenameFlow;
        BulkAndSearchSpec bulkAndSearch;
    }

    private static final class TempModifierCase {
        String kind;
        long initialExpiry;
        long newExpiry;
        String modifier;
        long expectedExpiry;
        boolean expectError;
    }

    private static final class TrackAndRenameFlow {
        List<String> groups;
        String trackName;
        List<String> trackGroups;
        UserSeed user;
        List<TrackStep> steps;
    }

    private static final class UserSeed {
        String uuid;
        String username;
        String primaryGroup;
    }

    private static final class TrackStep {
        String op;
        String track;
        @com.google.gson.annotations.SerializedName("old")
        String oldName;
        @com.google.gson.annotations.SerializedName("new")
        String newName;
        String expectFrom;
        String expectTo;
        String expectPrimary;
        List<String> expectTrackGroups;
        boolean expectError;
    }

    private static final class BulkAndSearchSpec {
        String searchQuery;
        int expectedGroupHitCount;
        BulkStep preview;
        BulkStep apply;
        BulkStep delete;
    }

    private static final class BulkStep {
        String scope;
        String action;
        String field;
        String value;
        List<BulkConstraintRecord> constraints;
        int expectGroupsChanged;
        int expectNodesAffected;
    }

    private static final class ExpectedSpecFile {
        String userUuid;
        List<ExpectedScenario> scenarios;
        PortableApplySpec portableApply;
    }

    private static final class ExpectedScenario {
        String name;
        Map<String, String> contexts;
        String primaryGroup;
        List<String> groups;
        String prefix;
        String suffix;
        Map<String, String> meta;
        List<ExpectedCheck> checks;
    }

    private static final class ExpectedCheck {
        String permission;
        String result;
        String source;
    }

    private static final class PortableApplySpec {
        String expectedGroup;
        int expectedGroupWeight;
        String expectedTrack;
        List<String> expectedTrackGroups;
        int expectedGroupsCount;
    }
}
