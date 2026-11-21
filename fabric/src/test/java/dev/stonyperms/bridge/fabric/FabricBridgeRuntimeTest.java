package dev.stonyperms.bridge.fabric;

import com.mojang.authlib.GameProfile;
import dev.stonyperms.bridge.core.ChangeEvent;
import dev.stonyperms.bridge.core.ParentMode;
import dev.stonyperms.bridge.core.Snapshot;
import dev.stonyperms.bridge.core.UserInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FabricBridgeRuntimeTest {

    @Test
    void honorsApplyToOpsFlagForOpPlayers() {
        FabricBridgeConfig applyOps = new FabricBridgeConfig(
                URI.create("http://127.0.0.1:8737"), "read-token-0123456789", "", "smp",
                30, 300, true, true, true, false, "", false, 1000L, 30_000L, false, 1500, 2500);
        FabricBridgeConfig skipOps = new FabricBridgeConfig(
                URI.create("http://127.0.0.1:8737"), "read-token-0123456789", "", "smp",
                30, 300, true, true, false, false, "", false, 1000L, 30_000L, false, 1500, 2500);

        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "Admin");

        PlayerList playerList = mock(PlayerList.class);
        when(playerList.isOp(any(NameAndId.class))).thenReturn(true);

        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(playerList);

        try (FabricBridgeRuntime rtApply = new FabricBridgeRuntime(applyOps, LoggerFactory.getLogger("test"));
             FabricBridgeRuntime rtSkip = new FabricBridgeRuntime(skipOps, LoggerFactory.getLogger("test"))) {
            assertFalse(rtApply.shouldBypassForOp(true));
            assertFalse(rtApply.isOpBypassed(server, profile));

            assertTrue(rtSkip.shouldBypassForOp(true));
            assertFalse(rtSkip.shouldBypassForOp(false));
            assertTrue(rtSkip.isOpBypassed(server, profile));

            // Fail-closed write API
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> rtApply.api().addParent(id.toString(), "vip", ParentMode.ADD, 0, Map.of(), false).join());
            assertTrue(ex.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void emitsFabricEventsOnSnapshotUpdateAndSseChange() {
        FabricBridgeConfig cfg = new FabricBridgeConfig(
                URI.create("http://127.0.0.1:8737"), "read-token-0123456789", "", "smp",
                30, 300, true, true, true, false, "", false, 1000L, 30_000L, false, 1500, 2500);

        List<Long> updatedRevs = new ArrayList<>();
        List<String> changeTypes = new ArrayList<>();
        StonyPermsEvents.USER_UPDATED.register((uuid, snap, prev) -> updatedRevs.add(snap.revision()));
        StonyPermsEvents.CHANGE_RECEIVED.register(ev -> changeTypes.add(ev.type()));

        UUID id = UUID.randomUUID();
        Snapshot s1 = new Snapshot(1, 5, "", 30,
                new UserInfo(id.toString(), "Steve", "vip", false, Map.of(), "", ""), List.of(), List.of());

        try (FabricBridgeRuntime rt = new FabricBridgeRuntime(cfg, LoggerFactory.getLogger("test"))) {
            rt.recordSnapshotUpdate(id, s1, Map.of("server", "smp"));
            // Duplicate same revision/content must not re-fire USER_UPDATED
            rt.recordSnapshotUpdate(id, s1, Map.of("server", "smp"));
            rt.handleSseEvent(new ChangeEvent(6, "group_updated", "", "", "vip", "", "admin", 1700000000L));

            assertEquals(List.of(5L), updatedRevs);
            assertEquals(List.of("group_updated"), changeTypes);
        }
    }

    @Test
    void combinesBrigadierRootAndTailForTabCompletionAndExecution() {
        assertArrayEquals(new String[]{"user"}, StonyPermsBridgeMod.combineRootAndTail("user", "", false));
        assertArrayEquals(new String[]{"user", ""}, StonyPermsBridgeMod.combineRootAndTail("user", "", true));
        assertArrayEquals(new String[]{"user", "Steve", "group", ""},
                StonyPermsBridgeMod.combineRootAndTail("user", "Steve group ", true));
        assertArrayEquals(new String[]{"user", "Steve", "group", "add"},
                StonyPermsBridgeMod.combineRootAndTail("user", "Steve group add", false));
    }
}
