package dev.stonyperms.bridge.core.cmd;

import dev.stonyperms.bridge.api.StonyPermsBridgeAPI;
import dev.stonyperms.bridge.core.ApiException;
import dev.stonyperms.bridge.core.CheckResult;
import dev.stonyperms.bridge.core.EditorApplyResult;
import dev.stonyperms.bridge.core.EditorSessionResult;
import dev.stonyperms.bridge.core.TreeSessionResult;
import dev.stonyperms.bridge.core.VerboseSessionResult;
import dev.stonyperms.bridge.core.ContextParser;
import dev.stonyperms.bridge.core.DurationParser;
import dev.stonyperms.bridge.core.GroupMember;
import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.MutationResult;
import dev.stonyperms.bridge.core.Node;
import dev.stonyperms.bridge.core.ParentEntry;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.PriorityMeta;
import dev.stonyperms.bridge.core.TrackActionResult;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Shared execution and tab-completion engine for /spb across Paper and Fabric.
 */
public final class BridgeCommandHandler {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final StonyPermsBridgeAPI api;
    private final CommandPlatform platform;

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
            case "reload" -> {
                handleReload(sender, args);
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
    // Basic diagnostics
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

        if (!requireWrite(sender)) return;
        String actor = platform.actorName(sender);

        switch (action) {
            case "editor", "ed" -> {
                handleEditor(sender, label, "user", target, "");
                return;
            }
            case "parent", "parents", "group", "groups" -> handleUserParent(sender, label, target, actor, args);
            case "primarygroup", "setprimarygroup" -> {
                if (args.size() < 4) {
                    platform.sendMessage(sender, "Usage: /" + label + " user " + target + " primarygroup <group>");
                    return;
                }
                String group = args.get(3);
                withOnlineAutoEnsure(target, actor, id -> api.setPrimaryGroup(id, group, actor, null))
                        .whenComplete((res, err) -> reportUserMutation(sender, target, "Set primary group to '" + group + "'", res, err));
            }
            case "permission", "perm", "permissions" -> handleUserPermission(sender, label, target, actor, args);
            case "meta" -> handleUserMeta(sender, label, target, actor, args);
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
                    "Usage: /" + label + " user <player> <info|parent|group|permission|meta|promote|demote|clear>");
        }
    }

    private void handleUserParent(
            Object sender, String label, String target, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " user " + target + " group <add|addtemp|accumulate|set|remove|switchprimarygroup> <group> [duration] [contexts...]");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        String group = args.get(4);
        try {
            switch (sub) {
                case "switchprimarygroup", "setprimary" -> withOnlineAutoEnsure(target, actor, id -> api.setPrimaryGroup(id, group, actor, null))
                        .whenComplete((res, err) -> reportUserMutation(sender, target, "Primary group set to '" + group + "'", res, err));
                case "remove" -> {
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    withOnlineAutoEnsure(target, actor, id -> api.removeParent(id, group, contexts, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(sender, target, "Removed group '" + group + "'", res, err));
                }
                case "add", "addtemp", "accumulate", "set" -> {
                    ParentMode mode = switch (sub) {
                        case "set" -> ParentMode.SET;
                        case "accumulate" -> ParentMode.ACCUMULATE;
                        default -> ParentMode.ADD;
                    };
                    boolean requireDuration = sub.equals("addtemp") || sub.equals("accumulate");
                    DurationAndContexts dc = parseOptionalDurationAndContexts(args, 5, requireDuration);
                    withOnlineAutoEnsure(target, actor, id -> api.addParent(
                            id, group, mode, dc.durationSeconds, 0L, dc.contexts, false, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(
                                    sender, target,
                                    "Updated group '" + group + "' (" + mode.wireName() + ", "
                                            + DurationParser.formatDuration(dc.durationSeconds) + ")",
                                    res, err));
                }
                default -> platform.sendMessage(sender,
                        "Unknown group action '" + sub + "'. Expected add|addtemp|accumulate|set|remove|switchprimarygroup");
            }
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    private void handleUserPermission(
            Object sender, String label, String target, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " user " + target + " permission <set|settemp|unset> <node> [true|false] [duration] [contexts...]");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        String key = args.get(4);
        try {
            switch (sub) {
                case "set", "settemp" -> {
                    boolean requireDuration = sub.equals("settemp");
                    PermArgs parsed = parsePermArgs(args, 5, requireDuration);
                    withOnlineAutoEnsure(target, actor, id -> api.setPermission(
                            id, key, parsed.value, parsed.durationSeconds, 0L, parsed.contexts, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(
                                    sender, target,
                                    "Set permission '" + key + "' = " + parsed.value + " ("
                                            + DurationParser.formatDuration(parsed.durationSeconds) + ")",
                                    res, err));
                }
                case "unset" -> {
                    Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                    withOnlineAutoEnsure(target, actor, id -> api.unsetPermission(id, key, contexts, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(sender, target, "Unset permission '" + key + "'", res, err));
                }
                default -> platform.sendMessage(sender, "Unknown permission action '" + sub + "'. Expected set|settemp|unset");
            }
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
        }
    }

    private void handleUserMeta(
            Object sender, String label, String target, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender,
                    "Usage: /" + label + " user " + target + " meta <set|unset|setprefix|removeprefix|setsuffix|removesuffix> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "set" -> {
                    if (args.size() < 6) {
                        platform.sendMessage(sender, "Usage: /" + label + " user " + target + " meta set <key> <value>");
                        return;
                    }
                    String key = args.get(4);
                    String val = joinArgs(args, 5);
                    withOnlineAutoEnsure(target, actor, id -> api.setMeta(id, key, val, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(sender, target, "Set meta '" + key + "'", res, err));
                }
                case "unset" -> {
                    String key = args.get(4);
                    withOnlineAutoEnsure(target, actor, id -> api.unsetMeta(id, key, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(sender, target, "Unset meta '" + key + "'", res, err));
                }
                case "setprefix", "addprefix", "setsuffix", "addsuffix" -> {
                    if (args.size() < 6) {
                        platform.sendMessage(sender, "Usage: /" + label + " user " + target + " meta " + sub + " <priority> <value> [duration] [contexts...]");
                        return;
                    }
                    boolean isPrefix = sub.contains("prefix");
                    int priority = Integer.parseInt(args.get(4));
                    String value = args.get(5);
                    DurationAndContexts dc = parseOptionalDurationAndContexts(args, 6, false);
                    withOnlineAutoEnsure(target, actor, id -> isPrefix
                            ? api.setPrefix(id, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null)
                            : api.setSuffix(id, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(
                                    sender, target,
                                    "Set " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")",
                                    res, err));
                }
                case "removeprefix", "removesuffix" -> {
                    boolean isPrefix = sub.contains("prefix");
                    int priority = Integer.parseInt(args.get(4));
                    withOnlineAutoEnsure(target, actor, id -> isPrefix
                            ? api.removePrefix(id, priority, actor, null)
                            : api.removeSuffix(id, priority, actor, null))
                            .whenComplete((res, err) -> reportUserMutation(
                                    sender, target,
                                    "Removed " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")",
                                    res, err));
                }
                default -> platform.sendMessage(sender,
                        "Unknown meta action '" + sub + "'. Expected set|unset|setprefix|removeprefix|setsuffix|removesuffix");
            }
        } catch (NumberFormatException error) {
            platform.sendMessage(sender, "Priority must be an integer");
        } catch (IllegalArgumentException error) {
            platform.sendMessage(sender, "Invalid argument: " + error.getMessage());
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
                case "permission", "perm", "permissions" -> handleGroupPermission(sender, label, group, actor, args);
                case "parent", "parents" -> handleGroupParent(sender, label, group, actor, args);
                case "meta" -> handleGroupMeta(sender, label, group, actor, args);
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

    private void handleGroupPermission(
            Object sender, String label, String group, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender, "Usage: /" + label + " group " + group + " permission <set|settemp|unset> <node> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        String key = args.get(4);
        switch (sub) {
            case "set", "settemp" -> {
                PermArgs parsed = parsePermArgs(args, 5, sub.equals("settemp"));
                api.setGroupPermission(group, key, parsed.value, parsed.durationSeconds, 0L, parsed.contexts, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender,
                                "Set group '" + group + "' permission '" + key + "' = " + parsed.value, res, err));
            }
            case "unset" -> {
                Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                api.unsetGroupPermission(group, key, contexts, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender,
                                "Unset group '" + group + "' permission '" + key + "'", res, err));
            }
            default -> platform.sendMessage(sender, "Unknown permission action '" + sub + "'");
        }
    }

    private void handleGroupParent(
            Object sender, String label, String group, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender, "Usage: /" + label + " group " + group + " parent <add|addtemp|accumulate|set|remove> <parent> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        String parent = args.get(4);
        switch (sub) {
            case "remove" -> {
                Map<String, String> contexts = ContextParser.parseTrailing(args, 5);
                api.removeGroupParent(group, parent, contexts, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender,
                                "Removed parent '" + parent + "' from group '" + group + "'", res, err));
            }
            case "add", "addtemp", "accumulate", "set" -> {
                ParentMode mode = switch (sub) {
                    case "set" -> ParentMode.SET;
                    case "accumulate" -> ParentMode.ACCUMULATE;
                    default -> ParentMode.ADD;
                };
                DurationAndContexts dc = parseOptionalDurationAndContexts(args, 5, sub.equals("addtemp") || sub.equals("accumulate"));
                api.addGroupParent(group, parent, mode, dc.durationSeconds, 0L, dc.contexts, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender,
                                "Added parent '" + parent + "' to group '" + group + "'", res, err));
            }
            default -> platform.sendMessage(sender, "Unknown parent action '" + sub + "'");
        }
    }

    private void handleGroupMeta(
            Object sender, String label, String group, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender, "Usage: /" + label + " group " + group + " meta <set|unset|setprefix|removeprefix|setsuffix|removesuffix> ...");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (args.size() < 6) {
                    platform.sendMessage(sender, "Usage: /" + label + " group " + group + " meta set <key> <value>");
                    return;
                }
                String key = args.get(4);
                String val = joinArgs(args, 5);
                api.setGroupMeta(group, key, val, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender, "Set group '" + group + "' meta '" + key + "'", res, err));
            }
            case "unset" -> {
                String key = args.get(4);
                api.unsetGroupMeta(group, key, actor, null)
                        .whenComplete((res, err) -> reportMutation(sender, "Unset group '" + group + "' meta '" + key + "'", res, err));
            }
            case "setprefix", "addprefix", "setsuffix", "addsuffix" -> {
                if (args.size() < 6) {
                    platform.sendMessage(sender, "Usage: /" + label + " group " + group + " meta " + sub + " <priority> <value> [duration] [contexts...]");
                    return;
                }
                boolean isPrefix = sub.contains("prefix");
                int priority = Integer.parseInt(args.get(4));
                String value = args.get(5);
                DurationAndContexts dc = parseOptionalDurationAndContexts(args, 6, false);
                CompletableFuture<MutationResult> fut = isPrefix
                        ? api.setGroupPrefix(group, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null)
                        : api.setGroupSuffix(group, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null);
                fut.whenComplete((res, err) -> reportMutation(sender,
                        "Set group '" + group + "' " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")", res, err));
            }
            case "removeprefix", "removesuffix" -> {
                boolean isPrefix = sub.contains("prefix");
                int priority = Integer.parseInt(args.get(4));
                CompletableFuture<MutationResult> fut = isPrefix
                        ? api.removeGroupPrefix(group, priority, actor, null)
                        : api.removeGroupSuffix(group, priority, actor, null);
                fut.whenComplete((res, err) -> reportMutation(sender,
                        "Removed group '" + group + "' " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")", res, err));
            }
            default -> platform.sendMessage(sender, "Unknown group meta action '" + sub + "'");
        }
    }

    private void handleGroupAffix(
            Object sender, String label, String group, boolean isPrefix, String actor, List<String> args) {
        if (args.size() < 5) {
            platform.sendMessage(sender, "Usage: /" + label + " group " + group + " "
                    + (isPrefix ? "prefix" : "suffix") + " <set <priority> <value> [duration] [contexts...]|remove <priority>>");
            return;
        }
        String sub = args.get(3).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set", "add" -> {
                if (args.size() < 6) {
                    platform.sendMessage(sender, "Usage: /" + label + " group " + group + " "
                            + (isPrefix ? "prefix" : "suffix") + " set <priority> <value> [duration] [contexts...]");
                    return;
                }
                int priority = Integer.parseInt(args.get(4));
                String value = args.get(5);
                DurationAndContexts dc = parseOptionalDurationAndContexts(args, 6, false);
                CompletableFuture<MutationResult> fut = isPrefix
                        ? api.setGroupPrefix(group, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null)
                        : api.setGroupSuffix(group, priority, value, dc.durationSeconds, 0L, dc.contexts, actor, null);
                fut.whenComplete((res, err) -> reportMutation(sender,
                        "Set group '" + group + "' " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")", res, err));
            }
            case "remove" -> {
                int priority = Integer.parseInt(args.get(4));
                CompletableFuture<MutationResult> fut = isPrefix
                        ? api.removeGroupPrefix(group, priority, actor, null)
                        : api.removeGroupSuffix(group, priority, actor, null);
                fut.whenComplete((res, err) -> reportMutation(sender,
                        "Removed group '" + group + "' " + (isPrefix ? "prefix" : "suffix") + " (priority " + priority + ")", res, err));
            }
            default -> platform.sendMessage(sender, "Unknown affix action '" + sub + "'");
        }
    }

    // -------------------------------------------------------------------------
    // /spb track <list | <name> ...>
    // -------------------------------------------------------------------------

    private void handleTrack(Object sender, String label, List<String> args) {
        if (args.size() < 2) {
            platform.sendMessage(sender, "Usage: /" + label + " track <list|<name> <info|create|delete|set|append|remove>>");
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
                top.addAll(List.of("status", "refresh", "check", "reload", "user", "group", "track"));
            } else {
                top.addAll(List.of("user", "group", "track"));
            }
            if (canWrite) {
                top.addAll(List.of("log", "editor", "applyedits", "tree", "verbose"));
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
        if (root.equals("user")) {
            if (idx == 1) {
                return filter(platform.onlinePlayerNames(), current);
            }
            if (idx == 2) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.add("info");
                if (canWrite) subs.addAll(List.of("group", "parent", "permission", "meta", "promote", "demote", "editor", "clear", "primarygroup"));
                return filter(subs, current);
            }
            if (canWrite && idx == 3) {
                return switch (args.get(2).toLowerCase(Locale.ROOT)) {
                    case "group", "groups", "parent", "parents" ->
                            filter(List.of("add", "addtemp", "accumulate", "set", "remove", "switchprimarygroup"), current);
                    case "permission", "perm", "permissions" ->
                            filter(List.of("set", "settemp", "unset"), current);
                    case "meta" ->
                            filter(List.of("set", "unset", "setprefix", "removeprefix", "setsuffix", "removesuffix"), current);
                    case "clear" ->
                            filter(List.of("all", "permissions", "parents", "meta"), current);
                    default -> List.of();
                };
            }
            if (canWrite && idx == 5) {
                String sub2 = args.get(2).toLowerCase(Locale.ROOT);
                String sub3 = args.get(3).toLowerCase(Locale.ROOT);
                if ((sub2.equals("group") || sub2.equals("groups") || sub2.equals("parent") || sub2.equals("parents"))
                        && (sub3.equals("add") || sub3.equals("addtemp") || sub3.equals("accumulate") || sub3.equals("set"))) {
                    return filter(List.of("30d", "7d", "24h", "1h", "permanent"), current);
                }
                if ((sub2.equals("permission") || sub2.equals("perm") || sub2.equals("permissions"))
                        && (sub3.equals("set") || sub3.equals("settemp"))) {
                    return filter(List.of("true", "false"), current);
                }
            }
        }
        if (root.equals("group")) {
            if (idx == 1 && canAdmin) {
                return filter(List.of("list"), current);
            }
            if (idx == 2 && !args.get(1).equalsIgnoreCase("list")) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.addAll(List.of("info", "members"));
                if (canWrite) subs.addAll(List.of("create", "delete", "setweight", "setdisplayname", "permission", "parent", "meta", "prefix", "suffix", "editor"));
                return filter(subs, current);
            }
            if (canWrite && idx == 3) {
                return switch (args.get(2).toLowerCase(Locale.ROOT)) {
                    case "permission", "perm", "permissions" -> filter(List.of("set", "settemp", "unset"), current);
                    case "parent", "parents" -> filter(List.of("add", "addtemp", "accumulate", "set", "remove"), current);
                    case "meta" -> filter(List.of("set", "unset", "setprefix", "removeprefix", "setsuffix", "removesuffix"), current);
                    case "prefix", "suffix" -> filter(List.of("set", "remove"), current);
                    default -> List.of();
                };
            }
        }
        if (root.equals("track")) {
            if (idx == 1 && canAdmin) {
                return filter(List.of("list"), current);
            }
            if (idx == 2 && !args.get(1).equalsIgnoreCase("list")) {
                List<String> subs = new ArrayList<>();
                if (canAdmin) subs.add("info");
                if (canWrite) subs.addAll(List.of("create", "delete", "set", "append", "remove"));
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
        platform.sendMessage(sender, "Usage: /" + label + " <status|refresh|check|reload|user|group|track|log|editor|applyedits|tree|verbose>");
    }

    private static DurationAndContexts parseOptionalDurationAndContexts(
            List<String> args, int startIdx, boolean requirePositiveDuration) {
        long durationSeconds = 0L;
        int idx = startIdx;
        if (idx < args.size() && !ContextParser.isContextToken(args.get(idx))) {
            durationSeconds = DurationParser.parseDurationSeconds(args.get(idx));
            idx++;
        }
        if (requirePositiveDuration && durationSeconds <= 0L) {
            throw new IllegalArgumentException("A positive duration is required (e.g. 30d, 1h)");
        }
        Map<String, String> contexts = ContextParser.parseTrailing(args, idx);
        return new DurationAndContexts(durationSeconds, contexts);
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
        DurationAndContexts dc = parseOptionalDurationAndContexts(args, idx, requirePositiveDuration);
        return new PermArgs(value, dc.durationSeconds, dc.contexts);
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

    private record DurationAndContexts(long durationSeconds, Map<String, String> contexts) {
    }

    private record PermArgs(boolean value, long durationSeconds, Map<String, String> contexts) {
    }
}
