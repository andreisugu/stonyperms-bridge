package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationAndContextParserTest {

    @Test
    void parsesSingleCompoundAndPermanentDurations() {
        assertEquals(0L, DurationParser.parseDurationSeconds("permanent"));
        assertEquals(0L, DurationParser.parseDurationSeconds("PERM"));
        assertEquals(0L, DurationParser.parseDurationSeconds("0"));
        assertEquals(45L, DurationParser.parseDurationSeconds("45s"));
        assertEquals(300L, DurationParser.parseDurationSeconds("5m"));
        assertEquals(5_400L, DurationParser.parseDurationSeconds("1h30m"));
        assertEquals(30L * 86_400L, DurationParser.parseDurationSeconds("30d"));
        assertEquals(2L * 604_800L, DurationParser.parseDurationSeconds("2w"));
        assertEquals(2_592_000L, DurationParser.parseDurationSeconds("1mo"));
        assertEquals(31_536_000L, DurationParser.parseDurationSeconds("1y"));
    }

    @Test
    void rejectsInvalidPlainNumbersNegativesAndOverflows() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseDurationSeconds(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseDurationSeconds("30"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseDurationSeconds("-5d"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseDurationSeconds("0d"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseDurationSeconds("11y"));
        assertFalse(DurationParser.looksLikeDuration("30"));
        assertTrue(DurationParser.looksLikeDuration("1h30m"));
    }

    @Test
    void formatsDurationsCompactly() {
        assertEquals("permanent", DurationParser.formatDuration(0));
        assertEquals("1d2h30m5s", DurationParser.formatDuration(86_400L + 7_200L + 1_800L + 5L));
        assertEquals("1y5d", DurationParser.formatDuration(31_536_000L + 5L * 86_400L));
    }

    @Test
    void parsesAndValidatesContexts() {
        Map<String, String> parsed = ContextParser.parseTrailing(
                List.of("ignored", "server=lobby", "world_1-a=minecraft:overworld"), 1);
        assertEquals(Map.of("server", "lobby", "world_1-a", "minecraft:overworld"), parsed);

        assertTrue(ContextParser.isContextToken("server=lobby"));
        assertFalse(ContextParser.isContextToken("30d"));
        assertFalse(ContextParser.isContextToken("INVALID=lobby"));

        assertThrows(IllegalArgumentException.class, () -> ContextParser.parseTrailing(List.of("notsplit"), 0));
        assertThrows(IllegalArgumentException.class, () -> ContextParser.parseTrailing(List.of("BAD=1"), 0));
        assertThrows(IllegalArgumentException.class, () -> ContextParser.parseTrailing(List.of("server=bad\u0007val"), 0));
        assertThrows(IllegalArgumentException.class, () -> ContextParser.parseTrailing(
                List.of("server=" + "a".repeat(129)), 0));
    }
}
