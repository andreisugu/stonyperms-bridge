package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.GroupRef;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.TeamColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricDisplayManagerTest {

    @Test
    void parsesLegacyPrefixTeamColorAndPreservesPlainChatContent() {
        Component prefix = FabricLegacyText.parse("&a[VIP] ");
        assertEquals("[VIP] ", prefix.getString());
        assertEquals(Optional.of(TeamColor.GREEN), FabricLegacyText.trailingTeamColor("&a[VIP] "));
        assertEquals(Optional.empty(), FabricLegacyText.trailingTeamColor("&a[VIP] &r"));

        UserInfo vip = new UserInfo("u1", "Andrei", "vip", false, Map.of(), "&a[VIP] ", "");
        Component chat = FabricDisplayManager.formatChatMessage(
                "{prefix}{name}{suffix}&r&7: &f{message}", vip, "Andrei", "&4raw-text");
        assertEquals("[VIP] Andrei: &4raw-text", chat.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),
                FabricLegacyText.trailingStyle("&a[VIP] Andrei&r&7: &f").getColor());
    }

    @Test
    void teamNamesSortHigherWeightFirstWithinScoreboardLimit() {
        Snapshot owner = new Snapshot(1, 1L, "", 60,
                new UserInfo("u1", "A", "owner", false, Map.of(), "&4[Owner] ", ""),
                List.of(new GroupRef("owner", "Owner", 1000)), List.of());
        Snapshot vip = new Snapshot(1, 1L, "", 60,
                new UserInfo("u2", "B", "vip", false, Map.of(), "&a[VIP] ", ""),
                List.of(new GroupRef("vip", "VIP", 100)), List.of());

        String ownerTeam = FabricDisplayManager.teamNameFor(owner);
        String vipTeam = FabricDisplayManager.teamNameFor(vip);
        assertTrue(ownerTeam.length() <= 16 && vipTeam.length() <= 16);
        assertTrue(ownerTeam.compareTo(vipTeam) < 0,
                "ownerTeam " + ownerTeam + " should sort before vipTeam " + vipTeam);
    }
}
