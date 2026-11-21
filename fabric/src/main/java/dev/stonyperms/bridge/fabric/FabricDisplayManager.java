package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.DisplayFormat;
import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Locale;
import java.util.Objects;

/**
 * Applies scoreboard PlayerTeam prefixes, suffixes, colors, and tab sorting on
 * Fabric servers, and formats chat lines according to StonyPerms snapshots.
 */
public final class FabricDisplayManager {

    static final String TEAM_PREFIX = "sp_";

    private FabricDisplayManager() {
    }

    public static String teamNameFor(Snapshot snapshot) {
        if (snapshot == null) {
            return TEAM_PREFIX + "fffff_000000";
        }
        UserInfo user = snapshot.user();
        int weight = resolveWeight(snapshot);
        int clamped = Math.max(0, Math.min(0xFFFFF, weight));
        int inverted = 0xFFFFF - clamped;
        int hash = Objects.hash(user.prefix(), user.suffix(), clamped) & 0xFFFFFF;
        return String.format(Locale.ROOT, "%s%05x_%06x", TEAM_PREFIX, inverted, hash);
    }

    public static void apply(MinecraftServer server, ServerPlayer player, Snapshot snapshot, FabricBridgeRuntime runtime) {
        if (server == null || player == null || runtime == null) {
            return;
        }
        if (!runtime.config().displayEnabled()) {
            clear(server, player.getScoreboardName());
            return;
        }
        if (runtime.isOpBypassed(server, player)) {
            clear(server, player.getScoreboardName());
            return;
        }
        if (snapshot == null) {
            if (runtime.config().failClosed()) {
                clear(server, player.getScoreboardName());
            }
            return;
        }

        UserInfo user = snapshot.user();
        ServerScoreboard board = server.getScoreboard();
        String playerName = player.getScoreboardName();
        String targetTeamName = teamNameFor(snapshot);

        PlayerTeam existing = board.getPlayersTeam(playerName);
        if (existing != null && existing.getName().startsWith(TEAM_PREFIX)
                && !existing.getName().equals(targetTeamName)) {
            board.removePlayerFromTeam(playerName, existing);
            if (existing.getPlayers().isEmpty()) {
                board.removePlayerTeam(existing);
            }
            existing = null;
        }

        PlayerTeam team = board.getPlayerTeam(targetTeamName);
        if (team == null) {
            team = board.addPlayerTeam(targetTeamName);
        }
        team.setPlayerPrefix(FabricLegacyText.parse(user.prefix()));
        team.setPlayerSuffix(FabricLegacyText.parse(user.suffix()));
        team.setColor(FabricLegacyText.trailingTeamColor(user.prefix()));

        if (existing != team) {
            board.addPlayerToTeam(playerName, team);
        }
    }

    public static void clear(MinecraftServer server, String scoreboardName) {
        if (server == null || scoreboardName == null || scoreboardName.isEmpty()) {
            return;
        }
        ServerScoreboard board = server.getScoreboard();
        PlayerTeam existing = board.getPlayersTeam(scoreboardName);
        if (existing != null && existing.getName().startsWith(TEAM_PREFIX)) {
            board.removePlayerFromTeam(scoreboardName, existing);
            if (existing.getPlayers().isEmpty()) {
                board.removePlayerTeam(existing);
            }
        }
    }

    public static Component formatChatMessage(String format, UserInfo user, String username, String rawMessage) {
        DisplayFormat.ChatParts parts = DisplayFormat.formatChatParts(format, user, username);
        MutableComponent line = FabricLegacyText.parse(parts.beforeMessage());
        line.append(Component.literal(rawMessage == null ? "" : rawMessage)
                .setStyle(FabricLegacyText.trailingStyle(parts.beforeMessage())));
        if (!parts.afterMessage().isEmpty()) {
            line.append(FabricLegacyText.parse(parts.afterMessage()));
        }
        return line;
    }

    private static int resolveWeight(Snapshot snapshot) {
        String primary = snapshot.user().primaryGroup();
        int bestAny = 0;
        for (GroupRef ref : snapshot.groups()) {
            if (ref.weight() > bestAny) {
                bestAny = ref.weight();
            }
            if (!primary.isEmpty() && ref.name().equalsIgnoreCase(primary)) {
                return ref.weight();
            }
        }
        return bestAny;
    }
}
