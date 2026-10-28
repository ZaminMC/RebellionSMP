package net.zaminmc.rebellion.alchemy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the deterministic brew source keys (ADR-0020 §1): the composition
 * the ADR-0019 XP idempotency rides, the one-second bucketing and the
 * grammar fallback for operator-named worlds with hostile characters.
 */
final class BrewSourceKeysTest {

    @Test
    void compositionIsDeterministic() {
        String first = BrewSourceKeys.forBrew("world", 10, 20, 30, 1000L);
        String second = BrewSourceKeys.forBrew("world", 10, 20, 30, 1000L);
        assertEquals(first, second);
        assertEquals("brew:world:10:20:30:1000", first);
        assertTrue(BrewSourceKeys.valid(first),
                "composed keys must match the ADR-0019 source grammar");
    }

    @Test
    void distinctBucketsAreDistinctSources() {
        String a = BrewSourceKeys.forBrew("world", 1, 2, 3, 1000L);
        String b = BrewSourceKeys.forBrew("world", 1, 2, 3, 1001L);
        assertNotEquals(a, b, "a re-delivered event within the same second "
                + "resolves to the typed no-op; a LATER brew is a new source");
    }

    @Test
    void bucketingSplitsAtSecondBoundaries() {
        assertEquals(500L, BrewSourceKeys.bucket(500_123L));
        assertEquals(500L, BrewSourceKeys.bucket(500_999L));
        assertEquals(501L, BrewSourceKeys.bucket(501_000L));
    }

    @Test
    void hostileWorldNamesHashIntoTheGrammar() {
        // Capitals, dots and spaces are all outside [a-z0-9_\-]; the hash
        // fallback keeps the key valid AND distinct per world.
        String hashed = BrewSourceKeys.forBrew("New World.2", 1, 2, 3, 1L);
        assertTrue(BrewSourceKeys.valid(hashed), hashed);
        assertTrue(hashed.startsWith("brew:w"), "hashed worlds carry the w tag");
        assertNotEquals(hashed, BrewSourceKeys.forBrew("Old World.2", 1, 2, 3, 1L));
    }

    @Test
    void grammarLegalWorldNamesPassThroughUnchanged() {
        assertEquals("overworld", BrewSourceKeys.safeWorld("overworld"));
        assertEquals("deep_dark-1", BrewSourceKeys.safeWorld("deep_dark-1"));
    }
}
