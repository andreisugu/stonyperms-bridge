package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshGateTest {

    @Test
    void coalescesDuplicateKeysAndBoundsAdmission() {
        RefreshGate gate = new RefreshGate(2);

        assertTrue(gate.tryBegin("player-a|lobby"));
        assertFalse(gate.tryBegin("player-a|lobby"));
        assertTrue(gate.tryBegin("player-b|hub"));
        assertFalse(gate.tryBegin("player-c|hub"));
        assertEquals(2, gate.inFlight());

        gate.end("player-a|lobby");
        assertTrue(gate.tryBegin("player-c|hub"));
        assertEquals(2, gate.inFlight());

        gate.end("player-b|hub");
        gate.end("player-c|hub");
        assertEquals(0, gate.inFlight());
    }
}
