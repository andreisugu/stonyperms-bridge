package dev.stonyperms.bridge.fabric;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricContextTest {

    @Test
    void includesServerAndWorldWhenConfigured() {
        Map<String, String> contexts = FabricContext.of("survival", "minecraft:overworld");
        assertEquals("survival", contexts.get("server"));
        assertEquals("minecraft:overworld", contexts.get("world"));
    }

    @Test
    void omitsBlankValues() {
        assertTrue(FabricContext.of("", "").isEmpty());
        assertFalse(FabricContext.of("", "minecraft:the_nether").containsKey("server"));
    }
}
