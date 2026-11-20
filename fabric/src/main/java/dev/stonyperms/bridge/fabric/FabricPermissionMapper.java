package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.Decision;
import net.fabricmc.fabric.api.util.TriState;

public final class FabricPermissionMapper {
    private FabricPermissionMapper() {
    }

    public static TriState map(Decision decision) {
        if (decision == null) {
            return TriState.DEFAULT;
        }
        return switch (decision.value()) {
            case TRUE -> TriState.TRUE;
            case FALSE -> TriState.FALSE;
            case UNDEFINED -> TriState.DEFAULT;
        };
    }
}
