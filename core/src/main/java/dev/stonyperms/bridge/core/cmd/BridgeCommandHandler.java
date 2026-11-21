package dev.stonyperms.bridge.core.cmd;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.BulkConstraintRecord;
import dev.stonyperms.bridge.core.BulkUpdateSummaryRecord;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.ContextParser;
import dev.stonyperms.bridge.core.DurationParser;
import dev.stonyperms.bridge.core.EditorApplyResult;
import dev.stonyperms.bridge.core.EditorSessionResult;
import dev.stonyperms.bridge.core.GroupMember;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.SearchHitRecord;
import dev.stonyperms.bridge.core.SearchResultRecord;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.TreeSessionResult;
import dev.stonyperms.bridge.core.UserRecord;
import dev.stonyperms.bridge.core.VerboseSessionResult;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Shared execution and tab-completion engine for /spb across Paper and Fabric.
 */
public final class BridgeCommandHandler {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long BULK_CONFIRM_TTL_MS = 60_000L;
    private static final int INFO_PER_PAGE = 15;
    private static final Set<String> MODIFIERS = Set.of("accumulate", "replace", "deny");
    private static final Set<String> BULK_OPERATORS = Set.of("==", "!=", "~~", "!~");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StonyPermsBridgeAPI api;
    private final CommandPlatform platform;
    private final ConcurrentHashMap<String, PendingBulk> pendingBulkUpdates = new ConcurrentHashMap<>();

    public BridgeCommandHandler(StonyPermsBridgeAPI api, CommandPlatform platform) {
        this.api = Objects.requireNonNull(api, "api");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public boolean execute(Object sender, String label, String[] rawArgs) {
        List<String> args = normalizeQuotedArgs(rawArgs);
        if (args.isEmpty()) {
            if (!platform.hasAdminPermission(sender) && !platform.hasWritePermission(sender)) {
                platform.sendMessage(sender, "You do not have permission to use /" + label + ".");
                return true;
            }
            sendUsage(sender, label);
            return true;
        }

        String root = args.getFirst().toLowerCase(Locale.ROOT);
        switch (root) {
            case "status" -> {
                if (!requireAdmin(sender)) return true;
                for (String line : platform.statusSummary().split("\n")) {
                    platform.sendMessage(sender, line);
                }
                return true;
            }
            case "refresh" -> {
                if (!requireAdmin(sender)) return true;
                handleRefresh(sender, args);
                return true;
            }
            case "check" -> {
                if (!requireAdmin(sender)) return true;
                handleCheck(sender, label, args);
                return true;
            }
            case "search" -> {
                if (!requireAdmin(sender)) return true;
                handleSearch(sender, label, args);
                return true;
            }
            case "reload" -> {
                handleReload(sender, args);
                return true;
            }
            case "sync", "networksync" -> {
                if (!requireWrite(sender)) return true;
                handleSync(sender);
                return true;
            }
            case "bulkupdate" -> {
                if (!requireWrite(sender)) return true;
                handleBulkUpdate(sender, label, args);
                return true;
            }
            case "user" -> {
                handleUser(sender, label, args);
                return true;
            }
            case "group" -> {
                handleGroup(sender, label, args);
                return true;
            }
            case "track" -> {
                handleTrack(sender, label, args);
                return true;
            }
            case "log", "logs" -> {
                if (!requireWrite(sender)) return true;
                handleLog(sender, args);
                return true;
            }
            case "editor", "ed" -> {
                if (!requireWrite(sender)) return true;
                handleEditor(sender, label, "all", "", args.size() >= 2 ? joinArgs(args, 1) : "");
                return true;
            }
            case "applyedits", "applyedit" -> {
                if (!requireWrite(sender)) return true;
                handleApplyEdits(sender, label, args);
                return true;
            }
            case "tree" -> {
                if (!requireWrite(sender)) return true;
                handleTree(sender, label, args);
                return true;
            }
            case "verbose" -> {
                if (!requireWrite(sender)) return true;
                handleVerbose(sender, label, args);
                return true;
            }
            default -> {
                if (!platform.hasAdminPermission(sender) && !platform.hasWritePermission(sender)) {
                    platform.sendMessage(sender, "You do not have permission to use /" + label + ".");
                } else {
                    sendUsage(sender, label);
                }
                return true;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Basic diagnostics & network operations
    // -------------------------------------------------------------------------

    private void handleRefresh(Object sender, List<String> args) {
        if (args.size() >= 2) {
            Optional<CommandPlatform.OnlinePlayerRef> player = platform.findOnlinePlayer(args.get(1));
            if (player.isEmpty()) {
                platform.sendMessage(sender, "Player not found: " + args.get(1));
                return;
            }
            platform.requestRefreshPlayer(player.get().uuid());
            platform.sendMessage(sender, "Refresh requested for " + player.get().name());
        } else {
            platform.requestRefreshAll();
            platform.sendMessage(sender, "Refresh requested for all online players");
        }
    }

    private void handleCheck(Object sender, String label, List<String> args) {
        if (args.size() < 3) {
            platform.sendMessage(sender, "Usage: /" + label + " check <player> <permission>");
            return;
        }
        Optional<CommandPlatform.OnlinePlayerRef> targetOpt = platform.findOnlinePlayer(args.get(1));
        if (targetOpt.isEmpty()) {
            platform.sendMessage(sender, "Player not found: " + args.get(1));
            return;
        }
        CommandPlatform.OnlinePlayerRef target = targetOpt.get();
        String permission = args.get(2);
        Map<String, String> contexts = target.contexts().isEmpty() ? platform.defaultContexts() : target.contexts();
        api.checkAuthoritative(target.uuid(), permission, contexts).whenComplete((CheckResult result, Throwable error) -> {
            if (error != null) {
                platform.sendMessage(sender, "Check failed: " + formatError(error));
            } else {
                platform.sendMessage(sender, permission + " = " + result.value() + " (" + result.source() + ")");
            }
        });
    }

    private void handleReload(Object sender, List<String> args) {
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("remote")) {
            if (!requireWrite(sender)) return;
            String actor = platform.actorName(sender);
            api.triggerRemoteReload(actor, null).whenComplete((result, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Remote reload failed: " + formatError(error));
                } else {
                    platform.sendMessage(sender, "Remote StonyPerms reload triggered (revision " + result.revision() + ")");
                }
            });
            return;
        }
        if (!requireAdmin(sender)) return;
        if (platform.reloadBridge()) {
            platform.requestRefreshAll();
            platform.sendMessage(sender, "StonyPerms bridge reloaded");
        } else {
            platform.sendMessage(sender, "Reload failed; see console");
        }
    }

    private void handleSync(Object sender) {
        String actor = platform.actorName(sender);
        api.triggerRemoteReload(actor, null).whenComplete((result, error) -> {
            if (error != null) {
                platform.sendMessage(sender, "Sync failed: " + formatError(error));
            } else {
                platform.requestRefreshAll();
                platform.sendMessage(sender, "Synced StonyPerms proxy & local cache (revision " + result.revision() + ")");
            }
        });
    }

    private void handleSearch(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            platform.sendMessage(sender, "Usage: /" + label + " search <permission|group.<group>> [page]");
            return;
        }
        int requestedPage = 1;
        String query;
        if (args.size() >= 3) {
            int parsed = parsePositiveInt(args.getLast(), -1);
            if (parsed > 0) {
                requestedPage = parsed;
                query = joinArgs(args.subList(0, args.size() - 1), 1);
            } else {
                query = joinArgs(args, 1);
            }
        } else {
            query = args.get(1);
        }
        int pageNum = requestedPage;
        api.search(query).whenComplete((SearchResultRecord result, Throwable error) -> {
            if (error != null) {
                platform.sendMessage(sender, "Search failed: " + formatError(error));
                return;
            }
            List<SearchHitRecord> hits = new ArrayList<>(result.groupHits().size() + result.userHits().size());
            hits.addAll(result.groupHits());
            hits.addAll(result.userHits());
            int total = hits.size();
            int totalPages = Math.max(1, (total + INFO_PER_PAGE - 1) / INFO_PER_PAGE);
            int page = Math.min(pageNum, totalPages);
            platform.sendMessage(sender, "Search '" + result.query() + "' (Page " + page + "/" + totalPages + " - " + total + " hits):");
            if (total == 0) {
                platform.sendMessage(sender, "  (no matching permissions or parents found)");
                return;
            }
            int start = (page - 1) * INFO_PER_PAGE;
            int end = Math.min(total, start + INFO_PER_PAGE);
            long nowEpoch = Instant.now().getEpochSecond();
            for (int i = start; i < end; i++) {
                SearchHitRecord h = hits.get(i);
                String tag = "user".equalsIgnoreCase(h.targetType()) ? "[U] " + h.target() : "[G] " + h.target();
                String sign = h.value() ? "+" : "-";
                String exp = formatExpirySuffix(h.expiry(), nowEpoch);
                String ctx = h.contexts().isEmpty() ? "" : " " + h.contexts();
                platform.sendMessage(sender, "  " + tag + " -> " + sign + h.nodeKey() + exp + ctx);
            }
        });
    }

    private void handleBulkUpdate(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            sendBulkUsage(sender, label);
            return;
        }
        String actor = platform.actorName(sender);
        if (args.get(1).equalsIgnoreCase("confirm")) {
            if (args.size() < 3) {
                platform.sendMessage(sender, "Usage: /" + label + " bulkupdate confirm <code>");
                return;
            }
            PendingBulk entry = takePendingBulk(args.get(2));
            if (entry == null) {
                platform.sendMessage(sender, "Invalid or expired confirmation code (codes expire after 60s).");
                return;
            }
            api.bulkUpdate(entry.scope(), entry.action(), entry.field(), entry.value(), entry.constraints(), false, actor, null)
                    .whenComplete((summary, error) -> reportBulkSummary(sender, summary, error));
            return;
        }

        if (args.size() < 3) {
            sendBulkUsage(sender, label);
            return;
        }
        String scope = args.get(1).toLowerCase(Locale.ROOT);
        if (!scope.equals("all") && !scope.equals("users") && !scope.equals("groups")) {
            platform.sendMessage(sender, "Invalid scope '" + args.get(1) + "'. Expected all|users|groups");
            return;
        }
        String action = args.get(2).toLowerCase(Locale.ROOT);
        if (!action.equals("update") && !action.equals("delete")) {
            platform.sendMessage(sender, "Invalid action '" + args.get(2) + "'. Expected update|delete");
            return;
        }

        boolean confirmNow = false;
        List<String> filtered = new ArrayList<>();
        for (int i = 3; i < args.size(); i++) {
            String tok = args.get(i);
            if (tok.equalsIgnoreCase("--confirm")) {
                confirmNow = true;
            } else {
                filtered.add(tok);
            }
        }

        String field = "";
        String value = "";
        int constraintStart = 0;
        if (action.equals("update")) {
            if (filtered.size() < 2) {
                platform.sendMessage(sender, "Usage: /" + label + " bulkupdate <all|users|groups> update <field> <value> [where <constraints...>] [--confirm]");
                return;
            }
            field = filtered.get(0);
            value = filtered.get(1);
            constraintStart = 2;
        }

        List<BulkConstraintRecord> constraints;
        try {
            constraints = parseBulkConstraints(filtered.subList(constraintStart, filtered.size()));
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid bulkupdate constraint: " + error.getMessage());
            return;
        }

        if (confirmNow) {
            api.bulkUpdate(scope, action, field, value, constraints, false, actor, null)
                    .whenComplete((summary, error) -> reportBulkSummary(sender, summary, error));
            return;
        }

        String finalField = field;
        String finalValue = value;
        api.bulkUpdate(scope, action, field, value, constraints, true, actor, null)
                .whenComplete((preview, error) -> {
                    if (error != null) {
                        platform.sendMessage(sender, "Bulk update preview failed: " + formatError(error));
                        return;
                    }
                    if (preview.nodesAffected() == 0) {
                        platform.sendMessage(sender, "Bulk update preview: 0 matching nodes found; nothing to change.");
                        return;
                    }
                    String code = storePendingBulk(scope, action, finalField, finalValue, constraints);
                    platform.sendMessage(sender, String.format(
                            "Bulk update preview (expires in 60s): %d groups, %d users, %d nodes matched.",
                            preview.groupsChanged(), preview.usersChanged(), preview.nodesAffected()));
                    platform.sendMessage(sender, "Run /" + label + " bulkupdate confirm " + code + " to apply.");
                });
    }

    private void reportBulkSummary(Object sender, BulkUpdateSummaryRecord summary, Throwable error) {
        if (error != null) {
            platform.sendMessage(sender, "Bulk update failed: " + formatError(error));
            return;
        }
        platform.sendMessage(sender, String.format(
                "Bulk update complete (rev #%d): %d groups, %d users, %d nodes updated",
                summary.revision(), summary.groupsChanged(), summary.usersChanged(), summary.nodesAffected()));
    }

    private void sendBulkUsage(Object sender, String label) {
        platform.sendMessage(sender,
                "Usage: /" + label + " bulkupdate <all|users|groups> <update <field> <value>|delete> [where <field> <==|!=|~~|!~> <pattern>...] [--confirm] | confirm <code>");
    }

    private String storePendingBulk(
            String scope, String action, String field, String value, List<BulkConstraintRecord> constraints) {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        String code = HexFormat.of().formatHex(bytes);
        long nowMs = System.currentTimeMillis();
        pruneExpiredBulk(nowMs);
        pendingBulkUpdates.put(code, new PendingBulk(scope, action, field, value, constraints, nowMs + BULK_CONFIRM_TTL_MS));
        return code;
    }

    private PendingBulk takePendingBulk(String code) {
        String key = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        long nowMs = System.currentTimeMillis();
        pruneExpiredBulk(nowMs);
        PendingBulk entry = pendingBulkUpdates.remove(key);
        if (entry == null || nowMs >= entry.expiresAtMillis()) {
            return null;
        }
        return entry;
    }

    private void pruneExpiredBulk(long nowMs) {
        pendingBulkUpdates.entrySet().removeIf(e -> nowMs >= e.getValue().expiresAtMillis());
    }

    private static List<BulkConstraintRecord> parseBulkConstraints(List<String> tokens) {
        List<BulkConstraintRecord> out = new ArrayList<>();
        int i = 0;
        if (i < tokens.size() && tokens.get(i).equalsIgnoreCase("where")) {
            i++;
        }
        while (i < tokens.size()) {
            String tok = tokens.get(i).trim();
            if (tok.equalsIgnoreCase("and")) {
                i++;
                continue;
            }
            String[] parts = tok.split("\\s+");
            if (parts.length == 3) {
                validateConstraintOp(parts[1]);
                out.add(new BulkConstraintRecord(parts[0], parts[1], parts[2]));
                i++;
            } else if (parts.length == 1 && i + 2 < tokens.size()) {
                validateConstraintOp(tokens.get(i + 1));
                out.add(new BulkConstraintRecord(tokens.get(i), tokens.get(i + 1), tokens.get(i + 2)));
                i += 3;
            } else {
                throw new IllegalArgumentException(
                        "Expected '<field> <==|!=|~~|!~> <pattern>' near '" + tokens.get(i) + "'");
            }
        }
        return out;
    }

    private static void validateConstraintOp(String op) {
        if (!BULK_OPERATORS.contains(op)) {
            throw new IllegalArgumentException("Unsupported operator '" + op + "' (expected ==|!=|~~|!~)");
        }
    }

    // -------------------------------------------------------------------------
    // /spb user <target> ...
    // -------------------------------------------------------------------------

    private void handleUser(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            if (platform.hasAdminPermission(sender) || platform.hasWritePermission(sender)) {
                platform.sendMessage(sender, "Usage: /" + label + " user <player> <info|parent|group|permission|meta|promote|demote|clear|primarygroup>");
            } else {
                platform.sendMessage(sender, "You do not have permission.");
            }
            return;
        }
        String target = args.get(1);
        String action = args.size() >= 3 ? args.get(2).toLowerCase(Locale.ROOT) : "info";

        if (action.equals("info")) {
            if (!requireAdmin(sender)) return;
            api.fetchUserRecord(target).whenComplete((user, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "User lookup failed: " + formatError(error));
                } else {
                    sendUserInfo(sender, user);
                }
            });
            return;
        }

        switch (action) {
            case "parent", "parents", "group", "groups" -> {
                handleParentAction(sender, label, true, target, args);
                return;
            }
            case "permission", "perm", "permissions" -> {
                handlePermissionAction(sender, label, true, target, args);
                return;
            }
            default -> {
            }
        }

        if (!requireWrite(sender)) return;
        String actor = platform.actorName(sender);

        switch (action) {
            case "editor", "ed" -> {
                handleEditor(sender, label, "user", target, "");
                return;
            }
            case "primarygroup", "setprimarygroup" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " user " + target + " primarygroup <group>");
                    return;
                }
                String group = args.get(3);
                withOnlineAutoEnsure(target, actor, id -> api.setPrimaryGroup(id, group, actor, null))
                        .whenComplete((res, err) -> reportUserMutation(sender, target, "Set primary group to '" + group + "'", res, err));
            }
            case "meta" -> handleMetaAction(sender, label, true, target, actor, args);
            case "promote" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " user " + target + " promote <track>");
                    return;
                }
                String track = args.get(3);
                withOnlineAutoEnsure(target, actor, id -> api.promote(id, track, actor, null))
                        .whenComplete((TrackActionResult res, Throwable err) -> {
                            if (err != null) {
                                platform.sendMessage(sender, "Promote failed: " + formatError(err));
                            } else {
                                platform.sendMessage(sender, "Promoted " + target + " on track '" + track + "': "
                                        + res.fromGroup() + " -> " + res.toGroup() + " (rev " + res.revision() + ")");
                            }
                        });
            }
            case "demote" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " user " + target + " demote <track>");
                    return;
                }
                String track = args.get(3);
                withOnlineAutoEnsure(target, actor, id -> api.demote(id, track, actor, null))
                        .whenComplete((TrackActionResult res, Throwable err) -> {
                            if (err != null) {
                                platform.sendMessage(sender, "Demote failed: " + formatError(err));
                            } else {
                                platform.sendMessage(sender, "Demoted " + target + " on track '" + track + "': "
                                        + res.fromGroup() + " -> " + res.toGroup() + " (rev " + res.revision() + ")");
                            }
                        });
            }
            case "clear" -> {
                String scope = args.size() >= 4 ? args.get(3).toLowerCase(Locale.ROOT) : "all";
                withOnlineAutoEnsure(target, actor, id -> api.clearUser(id, scope, actor, null))
                        .whenComplete((res, err) -> reportUserMutation(sender, target, "Cleared user (" + scope + ")", res, err));
            }
            default -> platform.sendMessage(sender,
                    "Usage: /" + label + " user <player> <info|parent|group|permission|meta|promote|demote|clear|primarygroup>");
        }
    }

    // -------------------------------------------------------------------------
    // /spb group <list | <name> ...>
    // -------------------------------------------------------------------------

    private void handleGroup(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            platform.sendMessage(sender, "Usage: /" + label + " group <list|<name> <info|members|create|delete|setweight|setdisplayname|permission|parent|meta|prefix|suffix>>");
            return;
        }
        String first = args.get(1);
        if (first.equalsIgnoreCase("list")) {
            if (!requireAdmin(sender)) return;
            api.fetchAllGroups().whenComplete((groups, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Failed to list groups: " + formatError(error));
                    return;
                }
                platform.sendMessage(sender, "StonyPerms groups (" + groups.size() + "):");
                groups.stream()
                        .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
                        .forEach(g -> platform.sendMessage(sender,
                                "  " + g.name() + " (weight=" + g.weight() + ", display=" + g.effectiveDisplayName() + ")"));
            });
            return;
        }

        String group = first;
        String action = args.size() >= 3 ? args.get(2).toLowerCase(Locale.ROOT) : "info";
        if (action.equals("info")) {
            if (!requireAdmin(sender)) return;
            api.fetchGroup(group).whenComplete((g, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Group lookup failed: " + formatError(error));
                } else {
                    sendGroupInfo(sender, g);
                }
            });
            return;
        }
        if (action.equals("members")) {
            if (!requireAdmin(sender)) return;
            api.fetchGroupMembers(group).whenComplete((members, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Group members lookup failed: " + formatError(error));
                    return;
                }
                platform.sendMessage(sender, "Members of '" + group + "' (" + members.size() + "):");
                for (GroupMember m : members) {
                    String name = m.username().isEmpty() ? m.uuid().toString() : m.username();
                    String dur = m.permanent() ? "permanent" : "expires@" + m.expiry();
                    platform.sendMessage(sender, "  " + name + " [" + dur + "]"
                            + (m.contexts().isEmpty() ? "" : " " + m.contexts()));
                }
            });
            return;
        }

        switch (action) {
            case "permission", "perm", "permissions" -> {
                handlePermissionAction(sender, label, false, group, args);
                return;
            }
            case "parent", "parents" -> {
                handleParentAction(sender, label, false, group, args);
                return;
            }
            default -> {
            }
        }

        if (!requireWrite(sender)) return;
        String actor = platform.actorName(sender);

        try {
            switch (action) {
                case "create" -> {
                    int weight = args.size() >= 4 ? Integer.parseInt(args.get(3)) : 0;
                    String displayName = args.size() >= 5 ? joinArgs(args, 4) : "";
                    api.createGroup(group, weight, displayName, actor, null)
                            .whenComplete((res, err) -> reportMutation(sender, "Created group '" + group + "'", res, err));
                }
                case "delete" -> api.deleteGroup(group, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender, "Deleted group '" + group + "'", res, err));
                case "setweight" -> {
                    if (args.size() < 4) {
                        platform.sendMessage(sender, "Usage: /" + label + " group " + group + " setweight <weight>");
                        return;
                    }
                    int weight = Integer.parseInt(args.get(3));
                    api.patchGroup(group, weight, null, actor, null)
                            .whenComplete((res, err) -> reportMutation(sender, "Updated weight for '" + group + "' to " + weight, res, err));
                }
                case "setdisplayname" -> {
                    if (args.size() < 4) {
                        platform.sendMessage(sender, "Usage: /" + label + " group " + group + " setdisplayname <name>");
                        return;
                    }
                    String displayName = joinArgs(args, 3);
                    api.patchGroup(group, null, displayName, actor, null)
                            .whenComplete((res, err) -> reportMutation(sender, "Updated displayName for '" + group + "'", res, err));
                }
                case "meta" -> handleMetaAction(sender, label, false, group, actor, args);
                case "editor", "ed" -> handleEditor(sender, label, "group", group, "");
                case "prefix", "suffix" -> handleGroupAffix(sender, label, group, action.equals("prefix"), actor, args);
                default -> platform.sendMessage(sender,
                        "Unknown group action '" + action + "'. Expected info|members|create|delete|setweight|setdisplayname|permission|parent|meta|prefix|suffix");
            }
        } catch (NumberFormatException error) {
            platform.sendMessage(sender, "Expected an integer argument");
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Shared permission / parent / meta / affix handlers for users & groups
    // -------------------------------------------------------------------------

    private void handlePermissionAction(
            Object sender, String label, boolean isUser, String target, List<String> args) {
        String kind = isUser ? "user " : "group ";
        if (args.size() < 4) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " " + kind + target + " permission <info [page]|set|settemp|unset|unsettemp> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            if (!requireAdmin(sender)) return;
            int page = args.size() >= 5 ? parsePositiveInt(args.get(4), 1) : 1;
            if (isUser) {
                api.fetchUserRecord(target).whenComplete((user, err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "User lookup failed: " + formatError(err));
                    } else {
                        sendPermissionInfo(sender, "User '" + user.username() + "'", user.nodes(), page);
                    }
                });
            } else {
                api.fetchGroup(target).whenComplete((group, err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "Group lookup failed: " + formatError(err));
                    } else {
                        sendPermissionInfo(sender, "Group '" + group.name() + "'", group.nodes(), page);
                    }
                });
            }
            return;
        }

        if (!requireWrite(sender)) return;
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " " + kind + target + " permission <set|settemp|unset|unsettemp> <node> [true|false] [duration] [accumulate|replace|deny] [contexts...]");
            return;
        }
        String actor = platform.actorName(sender);
        String key = args.get(4);
        try {
            switch (sub) {
                case "set", "settemp" -> {
                    boolean requireDuration = sub.equals("settemp");
                    PermArgs parsed = parsePermArgs(args, 5, requireDuration);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.setPermission(
                                id, key, parsed.value, parsed.durationSeconds, 0L, parsed.contexts, parsed.modifier, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target,
                                        "Set permission '" + key + "' = " + parsed.value + " ("
                                                + DurationParser.formatDuration(parsed.durationSeconds) + ")",
                                        res, err));
                    } else {
                        api.setGroupPermission(
                                target, key, parsed.value, parsed.durationSeconds, 0L, parsed.contexts, parsed.modifier, actor, null)
                                .whenComplete((res, err) -> reportMutation(sender,
                                        "Set group '" + target + "' permission '" + key + "' = " + parsed.value, res, err));
                    }
                }
                case "unset", "unsettemp" -> {
                    boolean tempOnly = sub.equals("unsettemp");
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.unsetPermission(id, key, contexts, tempOnly, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target,
                                        (tempOnly ? "Unset temporary" : "Unset") + " permission '" + key + "'", res, err));
                    } else {
                        api.unsetGroupPermission(target, key, contexts, tempOnly, actor, null)
                                .whenComplete((res, err) -> reportMutation(sender,
                                        (tempOnly ? "Unset temporary group '" : "Unset group '") + target + "' permission '" + key + "'",
                                        res, err));
                    }
                }
                default -> platform.sendMessage(sender,
                        "Unknown permission action '" + sub + "'. Expected info|set|settemp|unset|unsettemp");
            }
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    private void handleParentAction(
            Object sender, String label, boolean isUser, String target, List<String> args) {
        String kind = isUser ? "user " : "group ";
        if (args.size() < 4) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " " + kind + target + " parent <info|add|addtemp|accumulate|replace|deny|set|remove|removetemp|settrack|cleartrack"
                            + (isUser ? "|switchprimarygroup" : "") + "> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            if (!requireAdmin(sender)) return;
            if (isUser) {
                api.fetchUserRecord(target).whenComplete((user, err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "User lookup failed: " + formatError(err));
                    } else {
                        sendParentInfo(sender, "User '" + user.username() + "' (primaryGroup=" + user.primaryGroup() + ")", user.parents());
                    }
                });
            } else {
                api.fetchGroup(target).whenComplete((group, err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "Group lookup failed: " + formatError(err));
                    } else {
                        sendParentInfo(sender, "Group '" + group.name() + "'", group.parents());
                    }
                });
            }
            return;
        }

        if (!requireWrite(sender)) return;
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " " + kind + target + " parent " + sub + " <group|track> ...");
            return;
        }
        String actor = platform.actorName(sender);
        String firstArg = args.get(4);
        try {
            switch (sub) {
                case "switchprimarygroup", "setprimary" -> {
                    if (!isUser) {
                        platform.sendMessage(sender, "Unknown group parent action '" + sub + "'");
                        return;
                    }
                    withOnlineAutoEnsure(target, actor, id -> api.setPrimaryGroup(id, firstArg, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(sender, target, "Primary group set to '" + firstArg + "'", res, err));
                }
                case "settrack" -> {
                    if (args.size() < 6) {
                        platform.sendMessage(sender, "Usage: /" + label + " " + kind + target + " parent settrack <track> <group> [contexts...]");
                        return;
                    }
                    String track = firstArg;
                    String group = args.get(5);
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 6);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.setUserTrack(id, track, group, contexts, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target, "Set track '" + track + "' group to '" + group + "'", res, err));
                    } else {
                        api.setGroupTrack(target, track, group, contexts, actor, null)
                                .whenComplete((res, err) -> reportMutation(
                                        sender, "Set group '" + target + "' track '" + track + "' to '" + group + "'", res, err));
                    }
                }
                case "cleartrack" -> {
                    String track = firstArg;
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.clearUserTrack(id, track, contexts, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target, "Cleared track '" + track + "'", res, err));
                    } else {
                        api.clearGroupTrack(target, track, contexts, actor, null)
                                .whenComplete((res, err) -> reportMutation(
                                        sender, "Cleared track '" + track + "' from group '" + target + "'", res, err));
                    }
                }
                case "remove", "removetemp" -> {
                    boolean tempOnly = sub.equals("removetemp");
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.removeParent(id, firstArg, contexts, tempOnly, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target,
                                        (tempOnly ? "Removed temporary" : "Removed") + " group '" + firstArg + "'", res, err));
                    } else {
                        api.removeGroupParent(target, firstArg, contexts, tempOnly, actor, null)
                                .whenComplete((res, err) -> reportMutation(
                                        sender,
                                        (tempOnly ? "Removed temporary parent '" : "Removed parent '") + firstArg + "' from group '" + target + "'",
                                        res, err));
                    }
                }
                case "add", "addtemp", "accumulate", "replace", "deny", "set" -> {
                    boolean requireDuration = sub.equals("addtemp") || sub.equals("accumulate")
                            || sub.equals("replace") || sub.equals("deny");
                    DurationModifierContexts dc = parseDurationModifierContexts(args, 5, requireDuration);
                    ParentMode mode = resolveParentMode(sub, dc.modifier);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.addParent(
                                id, firstArg, mode, dc.durationSeconds, 0L, dc.contexts, false, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target,
                                        "Updated group '" + firstArg + "' (" + mode.wireName() + ", "
                                                + DurationParser.formatDuration(dc.durationSeconds) + ")",
                                        res, err));
                    } else {
                        api.addGroupParent(target, firstArg, mode, dc.durationSeconds, 0L, dc.contexts, actor, null)
                                .whenComplete((res, err) -> reportMutation(sender,
                                        "Added parent '" + firstArg + "' to group '" + target + "'", res, err));
                    }
                }
                default -> platform.sendMessage(sender,
                        "Unknown parent action '" + sub + "'. Expected info|add|addtemp|accumulate|replace|deny|set|remove|removetemp|settrack|cleartrack");
            }
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    private void handleMetaAction(
            Object sender, String label, boolean isUser, String target, String actor, List<String> args) {
        String kind = isUser ? "user " : "group ";
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " " + kind + target + " meta <set|settemp|unset|unsettemp|setprefix|addprefix|settempprefix|addtempprefix|removeprefix|removetempprefix|setsuffix|addsuffix|settempsuffix|addtempsuffix|removesuffix|removetempsuffix> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "set" -> {
                    if (args.size() < 6) {
                        platform.sendMessage(sender, "Usage: /" + label + " " + kind + target + " meta set <key> <value> [contexts...]");
                        return;
                    }
                    MetaSetArgs parsed = parseMetaSetArgs(args, 4);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.setMeta(
                                id, parsed.key, parsed.value, 0L, 0L, parsed.contexts, null, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(sender, target, "Set meta '" + parsed.key + "'", res, err));
                    } else {
                        api.setGroupMeta(target, parsed.key, parsed.value, 0L, 0L, parsed.contexts, null, actor, null)
                                .whenComplete((res, err) -> reportMutation(sender, "Set group '" + target + "' meta '" + parsed.key + "'", res, err));
                    }
                }
                case "settemp" -> {
                    if (args.size() < 7) {
                        platform.sendMessage(sender, "Usage: /" + label + " " + kind + target + " meta settemp <key> <value> <duration> [accumulate|replace|deny] [contexts...]");
                        return;
                    }
                    String key = args.get(4);
                    String val = args.get(5);
                    DurationModifierContexts dc = parseDurationModifierContexts(args, 6, true);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.setMeta(
                                id, key, val, dc.durationSeconds, 0L, dc.contexts, dc.modifier, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(sender, target, "Set temporary meta '" + key + "'", res, err));
                    } else {
                        api.setGroupMeta(target, key, val, dc.durationSeconds, 0L, dc.contexts, dc.modifier, actor, null)
                                .whenComplete((res, err) -> reportMutation(sender, "Set temporary group '" + target + "' meta '" + key + "'", res, err));
                    }
                }
                case "unset", "unsettemp" -> {
                    boolean tempOnly = sub.equals("unsettemp");
                    String key = args.get(4);
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    if (isUser) {
                        withOnlineAutoEnsure(target, actor, id -> api.unsetMeta(id, key, contexts, tempOnly, actor, null))
                                .whenComplete((res, err) -> reportUserMutation(
                                        sender, target, (tempOnly ? "Unset temporary" : "Unset") + " meta '" + key + "'", res, err));
                    } else {
                        api.unsetGroupMeta(target, key, contexts, tempOnly, actor, null)
                                .whenComplete((res, err) -> reportMutation(
                                        sender, (tempOnly ? "Unset temporary group '" : "Unset group '") + target + "' meta '" + key + "'", res, err));
                    }
                }
                case "setprefix", "addprefix", "settempprefix", "addtempprefix",
                        "setsuffix", "addsuffix", "settempsuffix", "addtempsuffix" -> {
                    if (args.size() < 6) {
                        platform.sendMessage(sender, "Usage: /" + label + " " + kind + target + " meta " + sub
                                + " <priority> <value> [duration] [accumulate|replace|deny] [contexts...]");
                        return;
                    }
                    boolean isPrefix = sub.contains("prefix");
                    String mode = sub.startsWith("add") ? "add" : "set";
                    boolean requireDuration = sub.contains("temp");
                    applyAffixSet(sender, isUser, target, actor, isPrefix, mode, requireDuration, args, 4);
                }
                case "removeprefix", "removetempprefix", "removesuffix", "removetempsuffix" -> {
                    boolean isPrefix = sub.contains("prefix");
                    boolean tempOnly = sub.contains("temp");
                    applyAffixRemove(sender, isUser, target, actor, isPrefix, tempOnly, args, 4);
                }
                default -> platform.sendMessage(sender,
                        "Unknown meta action '" + sub + "'. Expected set|settemp|unset|unsettemp|setprefix|addprefix|settempprefix|addtempprefix|removeprefix|removetempprefix|setsuffix|addsuffix|settempsuffix|addtempsuffix|removesuffix|removetempsuffix");
            }
        } catch (NumberFormatException error) {
            platform.sendMessage(sender, "Priority must be an integer");
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    private void handleGroupAffix(
            Object sender, String label, String group, boolean isPrefix, String actor, List<String> args) {
        String noun = isPrefix ? "prefix" : "suffix";
        if (args.size() < 5) {
            platform.sendMessage(sender, "Usage: /" + label + " group " + group + " "
                    + noun + " <set|add|settemp|addtemp|remove|removetemp> <priority> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set", "add", "settemp", "addtemp" -> {
                if (args.size() < 6) {
                    platform.sendMessage(sender, "Usage: /" + label + " group " + group + " "
                            + noun + " " + sub + " <priority> <value> [duration] [accumulate|replace|deny] [contexts...]");
                    return;
                }
                String mode = sub.startsWith("add") ? "add" : "set";
                boolean requireDuration = sub.contains("temp");
                applyAffixSet(sender, false, group, actor, isPrefix, mode, requireDuration, args, 4);
            }
            case "remove", "removetemp" -> applyAffixRemove(sender, false, group, actor, isPrefix, sub.equals("removetemp"), args, 4);
            default -> platform.sendMessage(sender, "Unknown affix action '" + sub + "'");
        }
    }

    private void applyAffixSet(
            Object sender,
            boolean isUser,
            String target,
            String actor,
            boolean isPrefix,
            String mode,
            boolean requireDuration,
            List<String> args,
            int priorityIdx) {
        int priority = Integer.parseInt(args.get(priorityIdx));
        String value = args.get(priorityIdx + 1);
        DurationModifierContexts dc = parseDurationModifierContexts(args, priorityIdx + 2, requireDuration);
        String noun = isPrefix ? "prefix" : "suffix";
        if (isUser) {
            withOnlineAutoEnsure(target, actor, id -> isPrefix
                    ? api.setPrefix(id, priority, value, dc.durationSeconds, 0L, dc.contexts, mode, dc.modifier, actor, null)
                    : api.setSuffix(id, priority, value, dc.durationSeconds, 0L, dc.contexts, mode, dc.modifier, actor, null))
                    .whenComplete((res, err) -> reportUserMutation(
                            sender, target, "Set " + noun + " (priority " + priority + ")", res, err));
        } else {
            CompletableFuture<MutationResult> fut = isPrefix
                    ? api.setGroupPrefix(target, priority, value, dc.durationSeconds, 0L, dc.contexts, mode, dc.modifier, actor, null)
                    : api.setGroupSuffix(target, priority, value, dc.durationSeconds, 0L, dc.contexts, mode, dc.modifier, actor, null);
            fut.whenComplete((res, err) -> reportMutation(
                    sender, "Set group '" + target + "' " + noun + " (priority " + priority + ")", res, err));
        }
    }

    private void applyAffixRemove(
            Object sender,
            boolean isUser,
            String target,
            String actor,
            boolean isPrefix,
            boolean tempOnly,
            List<String> args,
            int priorityIdx) {
        int priority = Integer.parseInt(args.get(priorityIdx));
        String value = null;
        int ctxIdx = priorityIdx + 1;
        if (ctxIdx < args.size() && !ContextParser.isContextToken(args.get(ctxIdx))) {
            value = args.get(ctxIdx);
            ctxIdx++;
        }
        Map<String, String> contexts = ContextParser.parseTrailing(args, ctxIdx);
        String finalValue = value;
        String noun = isPrefix ? "prefix" : "suffix";
        if (isUser) {
            withOnlineAutoEnsure(target, actor, id -> isPrefix
                    ? api.removePrefix(id, priority, finalValue, contexts, tempOnly, actor, null)
                    : api.removeSuffix(id, priority, finalValue, contexts, tempOnly, actor, null))
                    .whenComplete((res, err) -> reportUserMutation(
                            sender, target,
                            (tempOnly ? "Removed temporary " : "Removed ") + noun + " (priority " + priority + ")",
                            res, err));
        } else {
            CompletableFuture<MutationResult> fut = isPrefix
                    ? api.removeGroupPrefix(target, priority, finalValue, contexts, tempOnly, actor, null)
                    : api.removeGroupSuffix(target, priority, finalValue, contexts, tempOnly, actor, null);
            fut.whenComplete((res, err) -> reportMutation(
                    sender,
                    (tempOnly ? "Removed temporary group '" : "Removed group '") + target + "' " + noun + " (priority " + priority + ")",
                    res, err));
        }
    }

    // -------------------------------------------------------------------------
    // /spb track <list | <name> ...>
    // -------------------------------------------------------------------------

    private void handleTrack(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            platform.sendMessage(sender, "Usage: /" + label + " track <list|<name> <info|create|delete|set|append|remove|clone>>");
            return;
        }
        String first = args.get(1);
        if (first.equalsIgnoreCase("list")) {
            if (!requireAdmin(sender)) return;
            api.fetchTracks().whenComplete((tracks, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Failed to list tracks: " + formatError(error));
                    return;
                }
                platform.sendMessage(sender, "StonyPerms tracks (" + tracks.size() + "):");
                for (TrackRecord t : tracks) {
                    platform.sendMessage(sender, "  " + t.name() + ": " + String.join(" -> ", t.groups()));
                }
            });
            return;
        }

        String track = first;
        String action = args.size() >= 3 ? args.get(2).toLowerCase(Locale.ROOT) : "info";
        if (action.equals("info")) {
            if (!requireAdmin(sender)) return;
            api.fetchTrack(track).whenComplete((t, error) -> {
                if (error != null) {
                    platform.sendMessage(sender, "Track lookup failed: " + formatError(error));
                } else {
                    platform.sendMessage(sender, "Track '" + t.name() + "': "
                            + (t.groups().isEmpty() ? "(empty)" : String.join(" -> ", t.groups())));
                }
            });
            return;
        }

        if (!requireWrite(sender)) return;
        String actor = platform.actorName(sender);

        switch (action) {
            case "create" -> {
                List<String> groups = args.subList(3, args.size());
                api.createTrack(track, groups, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender, "Created track '" + track + "'", res, err));
            }
            case "delete" -> api.deleteTrack(track, actor, null)
                    .whenComplete((res, err) -> reportMutation(sender, "Deleted track '" + track + "'", res, err));
            case "set" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " track " + track + " set <group1> [group2 ...]");
                    return;
                }
                List<String> groups = args.subList(3, args.size());
                api.putTrack(track, groups, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender, "Updated track '" + track + "'", res, err));
            }
            case "append" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " track " + track + " append <group>");
                    return;
                }
                String group = args.get(3).toLowerCase(Locale.ROOT);
                api.fetchTrack(track).thenCompose(existing -> {
                    List<String> next = new ArrayList<>(existing.groups());
                    if (next.stream().anyMatch(g -> g.equalsIgnoreCase(group))) {
                        throw new IllegalArgumentException("Group '" + group + "' is already on track '" + track + "'");
                    }
                    next.add(group);
                    return api.putTrack(track, next, actor, null);
                }).whenComplete((res, err) -> reportMutation(sender, "Appended '" + group + "' to track '" + track + "'", res, err));
            }
            case "remove" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " track " + track + " remove <group>");
                    return;
                }
                String group = args.get(3).toLowerCase(Locale.ROOT);
                api.fetchTrack(track).thenCompose(existing -> {
                    List<String> next = new ArrayList<>();
                    boolean removed = false;
                    for (String g : existing.groups()) {
                        if (g.equalsIgnoreCase(group)) {
                            removed = true;
                        } else {
                            next.add(g);
                        }
                    }
                    if (!removed) {
                        throw new IllegalArgumentException("Group '" + group + "' is not on track '" + track + "'");
                    }
                    return api.putTrack(track, next, actor, null);
                }).whenComplete((res, err) -> reportMutation(sender, "Removed '" + group + "' from track '" + track + "'", res, err));
            }
            case "clone" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " track " + track + " clone <newName>");
                    return;
                }
                String newName = args.get(3).toLowerCase(Locale.ROOT);
                api.fetchTrack(track)
                        .thenCompose(existing -> api.createTrack(newName, existing.groups(), actor, null))
                        .whenComplete((res, err) -> reportMutation(
                                sender, "Cloned track '" + track + "' to '" + newName + "'", res, err));
            }
            default -> platform.sendMessage(sender, "Unknown track action '" + action + "'");
        }
    }

    // -------------------------------------------------------------------------
    // /spb log [limit] [targetType=...] [target=...] [actor=...]
    // -------------------------------------------------------------------------

    private void handleLog(Object sender, List<String> args) {
        int limit = 20;
        String targetType = null;
        String target = null;
        String actorFilter = null;

        for (int i = 1; i < args.size(); i++) {
            String token = args.get(i);
            int eq = token.indexOf('=');
            if (eq > 0) {
                String k = token.substring(0, eq).toLowerCase(Locale.ROOT);
                String v = token.substring(eq + 1);
                switch (k) {
                    case "type", "targettype" -> targetType = v;
                    case "target" -> target = v;
                    case "actor" -> actorFilter = v;
                    case "limit" -> limit = parsePositiveInt(v, 20);
                    default -> {
                        platform.sendMessage(sender, "Unknown log filter '" + k + "'");
                        return;
                    }
                }
            } else {
                try {
                    limit = Math.max(1, Integer.parseInt(token));
                } catch (NumberFormatException error) {
                    if (target == null) {
                        target = token;
                    } else {
                        platform.sendMessage(sender, "Invalid log argument: " + token);
                        return;
                    }
                }
            }
        }

        api.fetchLogs(limit, targetType, target, actorFilter).whenComplete((List<LogEntry> entries, Throwable error) -> {
            if (error != null) {
                platform.sendMessage(sender, "Failed to fetch logs: " + formatError(error));
                return;
            }
            platform.sendMessage(sender, "StonyPerms audit logs (" + entries.size() + "):");
            for (LogEntry entry : entries) {
                String ts = TS_FMT.format(Instant.ofEpochSecond(entry.timestamp()));
                platform.sendMessage(sender, "  [" + ts + "] " + entry.actor()
                        + " -> " + entry.targetType() + ":" + entry.target() + " | " + entry.action());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    public List<String> tabComplete(Object sender, String[] rawArgs) {
        boolean canAdmin = platform.hasAdminPermission(sender);
        boolean canWrite = platform.hasWritePermission(sender) && api.isWriteEnabled();
        if (!canAdmin && !platform.hasWritePermission(sender)) {
            return List.of();
        }

        List<String> args = normalizeTabArgs(rawArgs);
        if (args.size() <= 1) {
            String prefix = args.isEmpty() ? "" : args.getFirst();
            List<String> top = new ArrayList<>();
            if (canAdmin) {
                top.addAll(List.of("status", "refresh", "check", "search", "reload", "user", "group", "track"));
            } else {
                top.addAll(List.of("user", "group", "track"));
            }
            if (canWrite) {
                top.addAll(List.of("sync", "networksync", "bulkupdate", "log", "editor", "applyedits", "tree", "verbose"));
            }
            return filter(top, prefix);
        }

        String root = args.getFirst().toLowerCase(Locale.ROOT);
        String current = args.getLast();
        int idx = args.size() - 1;

        if ((root.equals("refresh") || root.equals("check")) && idx == 1 && canAdmin) {
            return filter(platform.onlinePlayerNames(), current);
        }
        if (root.equals("reload") && idx == 1 && canWrite) {
            return filter(List.of("remote"), current);
        }
        if (root.equals("bulkupdate") && canWrite) {
            if (idx == 1) {
                return filter(List.of("all", "users", "groups", "confirm"), current);
            }
            if (idx == 2 && !args.get(1).equalsIgnoreCase("confirm")) {
                return filter(List.of("update", "delete"), current);
            }
            if (idx == 3 && args.get(2).equalsIgnoreCase("update")) {
                return filter(List.of("permission", "group", "meta", "prefix", "suffix"), current);
            }
        }
        if (root.equals("user")) {
            if (idx == 1) {
                return filter(platform.onlinePlayerNames(), current);
            }
            if (idx == 2) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.addAll(List.of("info", "permission", "parent", "group"));
                if (canWrite) subs.addAll(List.of("meta", "promote", "demote", "editor", "clear", "primarygroup"));
                return filter(subs, current);
            }
            if (idx == 3) {
                return tabCompleteEntitySub(true, args.get(2).toLowerCase(Locale.ROOT), canAdmin, canWrite, current);
            }
            if (canWrite && idx >= 5) {
                return tabCompleteEntityArgs(args, idx, current);
            }
        }
        if (root.equals("group")) {
            if (idx == 1 && canAdmin) {
                return filter(List.of("list"), current);
            }
            if (idx == 2 && !args.get(1).equalsIgnoreCase("list")) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.addAll(List.of("info", "members", "permission", "parent"));
                if (canWrite) subs.addAll(List.of("create", "delete", "setweight", "setdisplayname", "meta", "prefix", "suffix", "editor"));
                return filter(subs, current);
            }
            if (idx == 3) {
                return tabCompleteEntitySub(false, args.get(2).toLowerCase(Locale.ROOT), canAdmin, canWrite, current);
            }
            if (canWrite && idx >= 5) {
                return tabCompleteEntityArgs(args, idx, current);
            }
        }
        if (root.equals("track")) {
            if (idx == 1 && canAdmin) {
                return filter(List.of("list"), current);
            }
            if (idx == 2 && !args.get(1).equalsIgnoreCase("list")) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.add("info");
                if (canWrite) subs.addAll(List.of("create", "delete", "set", "append", "remove", "clone"));
                return filter(subs, current);
            }
        }
        if (root.equals("log") || root.equals("logs")) {
            if (canWrite && idx >= 1) {
                return filter(List.of("20", "50", "targetType=user", "targetType=group", "target=", "actor="), current);
            }
        }
        if (root.equals("tree") && canWrite && idx == 1) {
            return filter(List.of(".", "bukkit", "minecraft", "stonyperms", "gate"), current);
        }
        if (root.equals("verbose") && canWrite && idx == 1) {
            return filter(List.of("paste", "upload"), current);
        }
        return List.of();
    }

    private static List<String> tabCompleteEntitySub(
            boolean isUser, String sub2, boolean canAdmin, boolean canWrite, String current) {
        List<String> out = new ArrayList<>();
        switch (sub2) {
            case "group", "groups", "parent", "parents" -> {
                if (canAdmin) out.add("info");
                if (canWrite) {
                    out.addAll(List.of("add", "addtemp", "accumulate", "replace", "deny",
                            "set", "remove", "removetemp", "settrack", "cleartrack"));
                    if (isUser) out.add("switchprimarygroup");
                }
            }
            case "permission", "perm", "permissions" -> {
                if (canAdmin) out.add("info");
                if (canWrite) out.addAll(List.of("set", "settemp", "unset", "unsettemp"));
            }
            case "meta" -> {
                if (canWrite) {
                    out.addAll(List.of("set", "settemp", "unset", "unsettemp",
                            "setprefix", "addprefix", "settempprefix", "addtempprefix", "removeprefix", "removetempprefix",
                            "setsuffix", "addsuffix", "settempsuffix", "addtempsuffix", "removesuffix", "removetempsuffix"));
                }
            }
            case "prefix", "suffix" -> {
                if (!isUser && canWrite) {
                    out.addAll(List.of("set", "add", "settemp", "addtemp", "remove", "removetemp"));
                }
            }
            case "clear" -> {
                if (isUser && canWrite) {
                    out.addAll(List.of("all", "permissions", "parents", "meta"));
                }
            }
            default -> {
            }
        }
        return filter(out, current);
    }

    private static List<String> tabCompleteEntityArgs(List<String> args, int idx, String current) {
        String sub2 = args.get(2).toLowerCase(Locale.ROOT);
        String sub3 = args.get(3).toLowerCase(Locale.ROOT);
        if (idx == 5) {
            if ((sub2.equals("group") || sub2.equals("groups") || sub2.equals("parent") || sub2.equals("parents"))
                    && (sub3.equals("add") || sub3.equals("addtemp") || sub3.equals("accumulate")
                    || sub3.equals("replace") || sub3.equals("deny") || sub3.equals("set"))) {
                return filter(List.of("30d", "7d", "24h", "1h", "permanent"), current);
            }
            if ((sub2.equals("permission") || sub2.equals("perm") || sub2.equals("permissions"))
                    && (sub3.equals("set") || sub3.equals("settemp"))) {
                return filter(List.of("true", "false"), current);
            }
        } else if (idx == 6) {
            if ((sub2.equals("group") || sub2.equals("groups") || sub2.equals("parent") || sub2.equals("parents"))
                    && (sub3.equals("add") || sub3.equals("addtemp"))) {
                return filter(List.of("accumulate", "replace", "deny"), current);
            }
            if ((sub2.equals("permission") || sub2.equals("perm") || sub2.equals("permissions")) && sub3.equals("settemp")) {
                String tok5 = args.get(5).toLowerCase(Locale.ROOT);
                return (tok5.equals("true") || tok5.equals("false"))
                        ? filter(List.of("30d", "7d", "24h", "1h"), current)
                        : filter(List.of("accumulate", "replace", "deny"), current);
            }
            if ((sub2.equals("meta") && (sub3.equals("settemp") || sub3.contains("tempprefix") || sub3.contains("tempsuffix")))
                    || ((sub2.equals("prefix") || sub2.equals("suffix")) && sub3.contains("temp"))) {
                return filter(List.of("30d", "7d", "24h", "1h"), current);
            }
        } else if (idx == 7) {
            if ((sub2.equals("permission") || sub2.equals("perm") || sub2.equals("permissions")) && sub3.equals("settemp")) {
                return filter(List.of("accumulate", "replace", "deny"), current);
            }
            if ((sub2.equals("meta") && (sub3.equals("settemp") || sub3.contains("prefix") || sub3.contains("suffix")))
                    || ((sub2.equals("prefix") || sub2.equals("suffix")) && !sub3.startsWith("remove"))) {
                return filter(List.of("accumulate", "replace", "deny"), current);
            }
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> CompletableFuture<T> withOnlineAutoEnsure(
            String target, String actor, Function<String, CompletableFuture<T>> action) {
        return action.apply(target).handle((result, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            Throwable cause = unwrap(throwable);
            if (cause instanceof ApiException apiEx
                    && apiEx.status() == 404
                    && "user_not_found".equals(apiEx.errorCode())) {
                Optional<CommandPlatform.OnlinePlayerRef> online = platform.findOnlinePlayer(target);
                if (online.isPresent()) {
                    CommandPlatform.OnlinePlayerRef ref = online.get();
                    return api.ensureUser(ref.uuid(), ref.name(), null, false, actor, null)
                            .thenCompose(ignored -> action.apply(ref.uuid().toString()));
                }
            }
            return CompletableFuture.<T>failedFuture(cause);
        }).thenCompose(Function.identity());
    }

    private void reportUserMutation(
            Object sender, String target, String summary, MutationResult result, Throwable error) {
        if (error != null) {
            platform.sendMessage(sender, "Operation failed for " + target + ": " + formatError(error));
        } else {
            platform.sendMessage(sender, summary + " for " + target + " (rev " + result.revision() + ")");
        }
    }

    private void reportMutation(Object sender, String summary, MutationResult result, Throwable error) {
        if (error != null) {
            platform.sendMessage(sender, "Operation failed: " + formatError(error));
        } else {
            platform.sendMessage(sender, summary + " (rev " + result.revision() + ")");
        }
    }

    private void sendPermissionInfo(Object sender, String subject, List<Node> nodes, int requestedPage) {
        int total = nodes.size();
        int totalPages = Math.max(1, (total + INFO_PER_PAGE - 1) / INFO_PER_PAGE);
        int page = Math.min(requestedPage, totalPages);
        platform.sendMessage(sender, subject + " permissions (Page " + page + "/" + totalPages + " - " + total + " total):");
        if (total == 0) {
            platform.sendMessage(sender, "  (none)");
            return;
        }
        int start = (page - 1) * INFO_PER_PAGE;
        int end = Math.min(total, start + INFO_PER_PAGE);
        long nowEpoch = Instant.now().getEpochSecond();
        for (int i = start; i < end; i++) {
            Node n = nodes.get(i);
            String exp = formatExpirySuffix(n.expiry(), nowEpoch);
            String ctx = n.contexts().isEmpty() ? "" : " " + n.contexts();
            platform.sendMessage(sender, "  " + n.key() + " = " + n.value() + exp + ctx);
        }
    }

    private void sendParentInfo(Object sender, String subject, List<ParentEntry> parents) {
        platform.sendMessage(sender, subject + " parents (" + parents.size() + "):");
        if (parents.isEmpty()) {
            platform.sendMessage(sender, "  (none)");
            return;
        }
        long nowEpoch = Instant.now().getEpochSecond();
        for (ParentEntry p : parents) {
            String exp = p.permanent() ? " (permanent)" : formatExpirySuffix(p.expiry(), nowEpoch);
            String ctx = p.contexts().isEmpty() ? "" : " " + p.contexts();
            platform.sendMessage(sender, "  " + p.group() + exp + ctx);
        }
    }

    private void sendUserInfo(Object sender, UserRecord user) {
        platform.sendMessage(sender, "User: " + user.username() + " (" + user.uuid() + ")");
        platform.sendMessage(sender, "  primaryGroup: " + user.primaryGroup());
        platform.sendMessage(sender, "  parents (" + user.parents().size() + "): " + formatParents(user.parents()));
        platform.sendMessage(sender, "  nodes (" + user.nodes().size() + "): " + formatNodes(user.nodes()));
        if (!user.meta().isEmpty()) {
            platform.sendMessage(sender, "  meta: " + user.meta());
        }
        if (!user.prefixes().isEmpty()) {
            platform.sendMessage(sender, "  prefixes: " + formatAffixes(user.prefixes()));
        }
        if (!user.suffixes().isEmpty()) {
            platform.sendMessage(sender, "  suffixes: " + formatAffixes(user.suffixes()));
        }
    }

    private void sendGroupInfo(Object sender, GroupRecord group) {
        platform.sendMessage(sender, "Group: " + group.name()
                + " (weight=" + group.weight() + ", display=" + group.effectiveDisplayName() + ")");
        platform.sendMessage(sender, "  parents (" + group.parents().size() + "): " + formatParents(group.parents()));
        platform.sendMessage(sender, "  nodes (" + group.nodes().size() + "): " + formatNodes(group.nodes()));
        if (!group.meta().isEmpty()) {
            platform.sendMessage(sender, "  meta: " + group.meta());
        }
        if (!group.prefixes().isEmpty()) {
            platform.sendMessage(sender, "  prefixes: " + formatAffixes(group.prefixes()));
        }
        if (!group.suffixes().isEmpty()) {
            platform.sendMessage(sender, "  suffixes: " + formatAffixes(group.suffixes()));
        }
    }

    private static String formatParents(List<ParentEntry> parents) {
        if (parents.isEmpty()) {
            return "(none)";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (ParentEntry p : parents) {
            StringBuilder sb = new StringBuilder(p.group());
            if (!p.permanent()) sb.append("@").append(p.expiry());
            if (!p.contexts().isEmpty()) sb.append(p.contexts());
            joiner.add(sb.toString());
        }
        return joiner.toString();
    }

    private static String formatNodes(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return "(none)";
        }
        StringJoiner joiner = new StringJoiner(", ");
        int limit = Math.min(nodes.size(), 15);
        for (int i = 0; i < limit; i++) {
            Node n = nodes.get(i);
            StringBuilder sb = new StringBuilder(n.key()).append("=").append(n.value());
            if (n.expiry() > 0) sb.append("@").append(n.expiry());
            if (!n.contexts().isEmpty()) sb.append(n.contexts());
            joiner.add(sb.toString());
        }
        if (nodes.size() > limit) {
            joiner.add("... +" + (nodes.size() - limit) + " more");
        }
        return joiner.toString();
    }

    private static String formatAffixes(List<PriorityMeta> affixes) {
        StringJoiner joiner = new StringJoiner(", ");
        for (PriorityMeta pm : affixes) {
            joiner.add("[" + pm.priority() + "]='" + pm.value() + "'");
        }
        return joiner.toString();
    }

    private static String formatExpirySuffix(long expiry, long nowEpoch) {
        if (expiry <= 0) {
            return "";
        }
        long rem = expiry - nowEpoch;
        return rem > 0 ? " (expires in " + DurationParser.formatDuration(rem) + ")" : " (expired)";
    }

    private boolean requireAdmin(Object sender) {
        if (platform.hasAdminPermission(sender)) {
            return true;
        }
        platform.sendMessage(sender, "You do not have permission (requires stonyperms.bridge.admin).");
        return false;
    }

    private boolean requireWrite(Object sender) {
        if (!platform.hasWritePermission(sender)) {
            platform.sendMessage(sender, "You do not have permission (requires stonyperms.bridge.write).");
            return false;
        }
        if (!api.isWriteEnabled()) {
            platform.sendMessage(sender, "StonyPerms writes are disabled on this server (write.enabled=false).");
            return false;
        }
        return true;
    }

    private void handleEditor(Object sender, String label, String targetType, String target, String filter) {
        String actor = platform.actorName(sender);
        List<String> known = platform.collectKnownPermissions();
        Map<String, List<String>> extraContexts = platform.collectExtraContexts();
        api.createEditorSession(actor, label, targetType, target, filter, known, extraContexts)
                .whenComplete((EditorSessionResult res, Throwable err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "Failed to open web editor: " + formatError(err));
                        return;
                    }
                    platform.sendUrlMessage(sender, "[StonyPerms] Web Editor link:", res.url(),
                            "Click to open StonyPermsWeb in your browser");
                    if (!res.liveSave()) {
                        platform.sendMessage(sender, "After saving in the browser, run /" + label + " applyedits <code>");
                    }
                });
    }

    private void handleApplyEdits(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            platform.sendMessage(sender, "Usage: /" + label + " applyedits <code>");
            return;
        }
        String code = args.get(1);
        String actor = platform.actorName(sender);
        api.applyEditorEdits(code, actor).whenComplete((EditorApplyResult res, Throwable err) -> {
            if (err != null) {
                platform.sendMessage(sender, "Failed to apply edits: " + formatError(err));
                return;
            }
            platform.sendMessage(sender, String.format(
                    "Applied web edits (rev #%d): %d groups (-%d), %d tracks (-%d), %d users (-%d)",
                    res.revision(), res.groupsUpdated(), res.groupsDeleted(),
                    res.tracksUpdated(), res.tracksDeleted(), res.usersUpdated(), res.usersDeleted()));
        });
    }

    private void handleTree(Object sender, String label, List<String> args) {
        String rootPrefix = args.size() >= 2 ? args.get(1) : ".";
        String actor = platform.actorName(sender);
        api.createTreeSession(rootPrefix, actor, label, platform.collectKnownPermissions())
                .whenComplete((TreeSessionResult res, Throwable err) -> {
                    if (err != null) {
                        platform.sendMessage(sender, "Failed to generate permission tree: " + formatError(err));
                        return;
                    }
                    platform.sendUrlMessage(sender,
                            "[StonyPerms] Permission tree (" + res.count() + " nodes):",
                            res.url(),
                            "Click to open Permission Tree Viewer");
                });
    }

    private void handleVerbose(Object sender, String label, List<String> args) {
        String sub = args.size() >= 2 ? args.get(1).toLowerCase(Locale.ROOT) : "paste";
        if (!sub.equals("paste") && !sub.equals("upload")) {
            platform.sendMessage(sender, "Usage: /" + label + " verbose paste (start Gate verbose capture via /sp verbose record on the proxy)");
            return;
        }
        String actor = platform.actorName(sender);
        api.createVerboseSession(actor, label).whenComplete((VerboseSessionResult res, Throwable err) -> {
            if (err != null) {
                platform.sendMessage(sender, "Failed to upload verbose log: " + formatError(err));
                return;
            }
            platform.sendUrlMessage(sender,
                    "[StonyPerms] Verbose log (" + res.count() + " checks):",
                    res.url(),
                    "Click to inspect verbose checks in StonyPermsWeb");
        });
    }

    private void sendUsage(Object sender, String label) {
        platform.sendMessage(sender,
                "Usage: /" + label + " <status|refresh|check|search|reload|sync|networksync|bulkupdate|user|group|track|log|editor|applyedits|tree|verbose>");
    }

    private static boolean isModifierToken(String tok) {
        return tok != null && MODIFIERS.contains(tok.toLowerCase(Locale.ROOT));
    }

    private static ParentMode resolveParentMode(String sub, String modifier) {
        ParentMode base = switch (sub) {
            case "set" -> ParentMode.SET;
            case "accumulate" -> ParentMode.ACCUMULATE;
            case "replace" -> ParentMode.REPLACE;
            case "deny" -> ParentMode.DENY;
            default -> ParentMode.ADD;
        };
        if (modifier == null) {
            return base;
        }
        ParentMode fromMod = switch (modifier) {
            case "accumulate" -> ParentMode.ACCUMULATE;
            case "replace" -> ParentMode.REPLACE;
            case "deny" -> ParentMode.DENY;
            default -> base;
        };
        if (base == ParentMode.ADD || base == fromMod) {
            return fromMod;
        }
        throw new IllegalArgumentException("Conflicting modifier '" + modifier + "' for parent '" + sub + "'");
    }

    private static DurationModifierContexts parseDurationModifierContexts(
            List<String> args, int startIdx, boolean requirePositiveDuration) {
        long durationSeconds = 0L;
        String modifier = null;
        int idx = startIdx;
        if (idx < args.size() && !ContextParser.isContextToken(args.get(idx)) && !isModifierToken(args.get(idx))) {
            durationSeconds = DurationParser.parseDurationSeconds(args.get(idx));
            idx++;
        }
        if (requirePositiveDuration && durationSeconds <= 0L) {
            throw new IllegalArgumentException("A positive duration is required (e.g. 30d, 1h)");
        }
        if (idx < args.size() && isModifierToken(args.get(idx))) {
            modifier = args.get(idx).toLowerCase(Locale.ROOT);
            idx++;
        }
        int end = args.size();
        if (modifier == null && end > idx && isModifierToken(args.get(end - 1))) {
            modifier = args.get(end - 1).toLowerCase(Locale.ROOT);
            end--;
        }
        Map<String, String> contexts = ContextParser.parseTrailing(args.subList(0, end), idx);
        return new DurationModifierContexts(durationSeconds, modifier, contexts);
    }

    private static PermArgs parsePermArgs(List<String> args, int startIdx, boolean requirePositiveDuration) {
        boolean value = true;
        int idx = startIdx;
        if (idx < args.size()) {
            String tok = args.get(idx).toLowerCase(Locale.ROOT);
            if (tok.equals("true") || tok.equals("false")) {
                value = Boolean.parseBoolean(tok);
                idx++;
            }
        }
        DurationModifierContexts dc = parseDurationModifierContexts(args, idx, requirePositiveDuration);
        return new PermArgs(value, dc.durationSeconds, dc.modifier, dc.contexts);
    }

    private static MetaSetArgs parseMetaSetArgs(List<String> args, int keyIdx) {
        String key = args.get(keyIdx);
        int valueStart = keyIdx + 1;
        int ctxStart = args.size();
        while (ctxStart > valueStart + 1 && ContextParser.isContextToken(args.get(ctxStart - 1))) {
            ctxStart--;
        }
        String value = joinArgs(args.subList(0, ctxStart), valueStart);
        Map<String, String> contexts = ContextParser.parseTrailing(args, ctxStart);
        return new MetaSetArgs(key, value, contexts);
    }

    private static String joinArgs(List<String> args, int startIdx) {
        StringJoiner joiner = new StringJoiner(" ");
        for (int i = startIdx; i < args.size(); i++) {
            joiner.add(args.get(i));
        }
        return joiner.toString();
    }

    private static int parsePositiveInt(String raw, int fallback) {
        try {
            int val = Integer.parseInt(raw);
            return val > 0 ? val : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Joins space-split quoted arguments while preserving already-tokenized array elements. */
    static List<String> normalizeQuotedArgs(String[] rawArgs) {
        if (rawArgs == null || rawArgs.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rawArgs.length);
        StringBuilder openQuote = null;
        char quoteChar = 0;

        for (String token : rawArgs) {
            if (token == null) {
                continue;
            }
            if (openQuote == null) {
                if (token.isEmpty()) {
                    continue;
                }
                char first = token.charAt(0);
                if (first == '\'' || first == '"') {
                    if (token.length() >= 2 && token.charAt(token.length() - 1) == first) {
                        out.add(token.substring(1, token.length() - 1));
                    } else {
                        quoteChar = first;
                        openQuote = new StringBuilder(token.substring(1));
                    }
                } else {
                    out.add(token);
                }
            } else {
                openQuote.append(' ');
                if (!token.isEmpty() && token.charAt(token.length() - 1) == quoteChar) {
                    openQuote.append(token, 0, token.length() - 1);
                    out.add(openQuote.toString());
                    openQuote = null;
                } else {
                    openQuote.append(token);
                }
            }
        }
        if (openQuote != null) {
            out.add(openQuote.toString());
        }
        return out;
    }

    private static List<String> normalizeTabArgs(String[] rawArgs) {
        if (rawArgs == null || rawArgs.length == 0) {
            return List.of();
        }
        List<String> list = new ArrayList<>(rawArgs.length);
        for (String s : rawArgs) {
            list.add(s == null ? "" : s);
        }
        return list;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(c -> c.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cur = error;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException)
                && cur.getCause() != null
                && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String formatError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof ApiException || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return cause.getMessage();
        }
        return cause.getClass().getSimpleName()
                + (cause.getMessage() != null && !cause.getMessage().isBlank() ? ": " + cause.getMessage() : "");
    }

    private record DurationModifierContexts(long durationSeconds, String modifier, Map<String, String> contexts) {
    }

    private record PermArgs(boolean value, long durationSeconds, String modifier, Map<String, String> contexts) {
    }

    private record MetaSetArgs(String key, String value, Map<String, String> contexts) {
    }

    private record PendingBulk(
            String scope,
            String action,
            String field,
            String value,
            List<BulkConstraintRecord> constraints,
            long expiresAtMillis) {
    }
}
