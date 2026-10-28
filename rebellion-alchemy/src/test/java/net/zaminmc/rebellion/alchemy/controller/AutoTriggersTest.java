package net.zaminmc.rebellion.alchemy.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.World.Environment;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure trigger predicates (ADR-0020 §4): every bound a rule
 * evaluation depends on, decidable without a live entity. FALLING ships
 * policy-disabled (ADR-0019) but its predicate is complete because the
 * operator whitelist can turn it on without a code change.
 */
final class AutoTriggersTest {

    // ------------------------------------------------------------------
    // HEALTH_BELOW
    // ------------------------------------------------------------------

    @Test
    void healthBelowFiresAtOrUnderTheThreshold() {
        // 20/20 hearts, threshold 40%: 100 > 40, then 8/20 = 40% <= 40.
        assertFalse(AutoTriggers.healthBelow(20, 20, 40));
        assertTrue(AutoTriggers.healthBelow(8, 20, 40));
        assertTrue(AutoTriggers.healthBelow(2, 20, 40));
    }

    @Test
    void healthBelowRejectsImpossibleBounds() {
        // maxHealth 0 (never legal from the attribute, defensive) and
        // out-of-range thresholds never fire - fail closed.
        assertFalse(AutoTriggers.healthBelow(0, 0, 40));
        assertFalse(AutoTriggers.healthBelow(20, 20, 0));
        assertFalse(AutoTriggers.healthBelow(20, 20, 100));
    }

    // ------------------------------------------------------------------
    // BURNING
    // ------------------------------------------------------------------

    @Test
    void burningIsAnyActiveFire() {
        assertFalse(AutoTriggers.burning(0));
        assertFalse(AutoTriggers.burning(-1), "extinguishing never counts");
        assertTrue(AutoTriggers.burning(1));
        assertTrue(AutoTriggers.burning(200));
    }

    // ------------------------------------------------------------------
    // NETHER_ENTRY
    // ------------------------------------------------------------------

    @Test
    void netherIsTheNetherEnvironmentOnly() {
        assertTrue(AutoTriggers.inNether(Environment.NETHER));
        assertFalse(AutoTriggers.inNether(Environment.NORMAL));
        assertFalse(AutoTriggers.inNether(Environment.THE_END));
        assertFalse(AutoTriggers.inNether(null));
    }

    // ------------------------------------------------------------------
    // FALLING
    // ------------------------------------------------------------------

    @Test
    void fallingNeedsAirborneAndDistance() {
        assertTrue(AutoTriggers.falling(12.5f, false, 8));
        assertFalse(AutoTriggers.falling(12.5f, true, 8),
                "grounded players never fire (ladders, water, ground)");
        assertFalse(AutoTriggers.falling(7.9f, false, 8));
        assertTrue(AutoTriggers.falling(8.0f, false, 8), "at-threshold fires");
    }
}
