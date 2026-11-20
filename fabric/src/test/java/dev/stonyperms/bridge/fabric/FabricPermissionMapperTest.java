package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.Decision;
import dev.stonyperms.bridge.core.Tri;
import net.fabricmc.fabric.api.util.TriState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricPermissionMapperTest {

    @Test
    void mapsAllThreeValues() {
        assertEquals(TriState.TRUE, FabricPermissionMapper.map(new Decision(Tri.TRUE, "user")));
        assertEquals(TriState.FALSE, FabricPermissionMapper.map(new Decision(Tri.FALSE, "user")));
        assertEquals(TriState.DEFAULT, FabricPermissionMapper.map(new Decision(Tri.UNDEFINED, "default")));
        assertEquals(TriState.DEFAULT, FabricPermissionMapper.map(null));
    }
}
