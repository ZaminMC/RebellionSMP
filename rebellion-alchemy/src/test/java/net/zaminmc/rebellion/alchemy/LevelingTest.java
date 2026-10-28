package net.zaminmc.rebellion.alchemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure brewing-level derivation (ADR-0019 §2): the linear cost
 * walk, the level clamp, the inverse used for display and the projection
 * every consumer derives levels through. Level is a <em>function</em> of
 * total XP and the curve — never a stored column.
 */
final class LevelingTest {

    private static final BrewingCurve CURVE = new BrewingCurve(30, 100, 25, 10);
    private static final BrewingCurve FLAT = new BrewingCurve(5, 10, 0, 10);

    @Test
    void costIsBasePlusGrowthPerLevel() {
        assertEquals(100, Leveling.cost(CURVE, 1)); // 1 -> 2
        assertEquals(125, Leveling.cost(CURVE, 2)); // 2 -> 3
        assertEquals(150, Leveling.cost(CURVE, 3));
        assertEquals(0, Leveling.cost(CURVE, 30), "no cost past max-level");
    }

    @Test
    void levelForWalksTheCurve() {
        assertEquals(1, Leveling.levelFor(CURVE, 0));
        assertEquals(1, Leveling.levelFor(CURVE, 99));
        assertEquals(2, Leveling.levelFor(CURVE, 100));
        assertEquals(2, Leveling.levelFor(CURVE, 224));
        assertEquals(3, Leveling.levelFor(CURVE, 225)); // 100 + 125
        assertEquals(4, Leveling.levelFor(CURVE, 375)); // 100 + 125 + 150
    }

    @Test
    void levelClampsAtMaxLevel() {
        assertEquals(2, Leveling.levelFor(FLAT, 10));
        assertEquals(5, Leveling.levelFor(FLAT, 40));
        assertEquals(5, Leveling.levelFor(FLAT, 1_000_000),
                "levels clamp at the configured maximum");
        assertEquals(0, Leveling.nextLevelCost(FLAT, 1_000_000));
    }

    @Test
    void totalXpForLevelInvertsTheWalk() {
        assertEquals(0, Leveling.totalXpForLevel(CURVE, 1));
        assertEquals(100, Leveling.totalXpForLevel(CURVE, 2));
        assertEquals(225, Leveling.totalXpForLevel(CURVE, 3));
        for (int level = 1; level <= 30; level++) {
            assertEquals(level, Leveling.levelFor(CURVE, Leveling.totalXpForLevel(
                    CURVE, level)), "inverse must round-trip for level " + level);
        }
    }

    @Test
    void xpIntoLevelAndNextCost() {
        // 130 XP: level 2 (100 for the first), 30 into the level, next
        // threshold at 225 -> 95 remain.
        assertEquals(2, Leveling.levelFor(CURVE, 130));
        assertEquals(30, Leveling.xpIntoLevel(CURVE, 130));
        assertEquals(95, Leveling.nextLevelCost(CURVE, 130));
    }

    @Test
    void zeroGrowthCurveIsUniform() {
        for (int level = 1; level < 5; level++) {
            assertEquals(10, Leveling.cost(FLAT, level));
        }
    }

    @Test
    void projectCarriesTheDerivedFields() {
        BrewProgress progress = Leveling.project(UUID.randomUUID(), CURVE, 130);
        assertEquals(130, progress.totalXp());
        assertEquals(2, progress.level());
        assertEquals(30, progress.xpIntoLevel());
        assertEquals(95, progress.nextLevelCost());
        assertFalse(progress.maxedOut());
        assertTrue(Leveling.project(UUID.randomUUID(), FLAT, 1_000_000).maxedOut());
    }

    @Test
    void newPlayerProjectsToLevelOne() {
        BrewProgress progress = Leveling.project(UUID.randomUUID(), CURVE, 0);
        assertEquals(1, progress.level());
        assertEquals(0, progress.totalXp());
        assertEquals(100, progress.nextLevelCost());
        assertFalse(progress.maxedOut());
    }
}
