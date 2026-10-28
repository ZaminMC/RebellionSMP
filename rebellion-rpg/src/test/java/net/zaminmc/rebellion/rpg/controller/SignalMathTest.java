package net.zaminmc.rebellion.rpg.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure REGION-signal vocabulary coverage (ADR-0017): the compass octants
 * and distance buckets are exactly what a REGION signal may reveal —
 * never an exact vector (master prompt §5.3 precision ceiling).
 */
final class SignalMathTest {

    @Test
    void compass8CoversAllEightOctants() {
        // Minecraft axes: +X east, +Z south, angles grow clockwise.
        assertEquals("E", SignalMath.compass8(100, 0));
        assertEquals("SE", SignalMath.compass8(100, 100));
        assertEquals("S", SignalMath.compass8(0, 100));
        assertEquals("SW", SignalMath.compass8(-100, 100));
        assertEquals("W", SignalMath.compass8(-100, 0));
        assertEquals("NW", SignalMath.compass8(-100, -100));
        assertEquals("N", SignalMath.compass8(0, -100));
        assertEquals("NE", SignalMath.compass8(100, -100));
    }

    @Test
    void compass8WrapsNegativeAnglesIntoOctants() {
        // atan2(-1, 0) = -PI/2 lands in the negative half; floorMod must
        // map it into [0, 8) instead of throwing (the compile-fix pin).
        assertEquals("N", SignalMath.compass8(0, -100));
        // Near-boundary noise rounds, never crashes: due-north-ish vectors.
        for (int dz = -100; dz <= -90; dz++) {
            String bearing = SignalMath.compass8(0, dz);
            assertTrue(bearing.equals("N") || bearing.equals("NE")
                    || bearing.equals("NW"), "bearing was " + bearing);
        }
    }

    @Test
    void bucketIndexMapsEdgesAscending() {
        List<Integer> edges = List.of(256, 1024, 4096);
        assertEquals(0, SignalMath.bucketIndex(100, edges));
        assertEquals(1, SignalMath.bucketIndex(300, edges));
        assertEquals(2, SignalMath.bucketIndex(2000, edges));
        assertEquals(3, SignalMath.bucketIndex(999_999, edges),
                "beyond the last edge resolves to the beyond-bucket");
    }

    @Test
    void horizontalDistanceIgnoresYAndStaysExactOnAxis() {
        assertEquals(100.0, SignalMath.horizontalDistance(0, 0, 100, 0));
        assertEquals(5.0, Math.round(
                SignalMath.horizontalDistance(0, 0, 3, 4) * 100) / 100.0,
                "3-4-5 triangle");
    }
}
