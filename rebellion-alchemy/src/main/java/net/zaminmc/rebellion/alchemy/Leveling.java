package net.zaminmc.rebellion.alchemy;

/**
 * The pure brewing-level derivation (ADR-0019 §2). The curve is linear in
 * the per-level cost: advancing from level {@code n} to {@code n + 1} costs
 * {@code base + (n - 1) * growth} XP. Pure over inputs — no clock, no
 * database, no registry — so it is exhaustively unit-testable and every
 * consumer (service reads, level gates, unlock required-level checks, the
 * future placeholder/menus) derives the same level from the same row.
 *
 * <p>Deliberately mirrors the RPG derived-expiry discipline: level is a
 * <em>function</em> of total XP and the config curve, never a stored column
 * that can drift or need a sweep.</p>
 */
public final class Leveling {

    private Leveling() {
    }

    /**
     * @param curve max-level/base/growth triple
     * @return the XP needed to advance FROM {@code level} to
     *         {@code level + 1}; 0 at max-level
     */
    public static long cost(BrewingCurve curve, int level) {
        if (level >= curve.maxLevel()) {
            return 0;
        }
        return curve.levelXpBase() + (level - 1L) * curve.levelXpGrowth();
    }

    /**
     * @return the derived level for a total XP amount, clamped at the
     *         configured maximum (never below 1)
     */
    public static int levelFor(BrewingCurve curve, long totalXp) {
        int level = 1;
        long remaining = totalXp;
        while (level < curve.maxLevel()) {
            long cost = cost(curve, level);
            if (remaining < cost) {
                break;
            }
            remaining -= cost;
            level++;
        }
        return level;
    }

    /** @return the XP accumulated above the derived level's base. */
    public static long xpIntoLevel(BrewingCurve curve, long totalXp) {
        return totalXp - totalXpForLevel(curve, levelFor(curve, totalXp));
    }

    /**
     * @return the total XP needed to reach a level (0 for level 1); the
     *         inverse of the walk, used for display and gates
     */
    public static long totalXpForLevel(BrewingCurve curve, int level) {
        long total = 0;
        for (int l = 1; l < level; l++) {
            total += cost(curve, l);
        }
        return total;
    }

    /**
     * @return the XP still needed to reach the next level; 0 when maxed
     */
    public static long nextLevelCost(BrewingCurve curve, long totalXp) {
        int level = levelFor(curve, totalXp);
        if (level >= curve.maxLevel()) {
            return 0;
        }
        long intoLevel = totalXp - totalXpForLevel(curve, level);
        return cost(curve, level) - intoLevel;
    }

    /** @return the projected progress row fields for a raw total. */
    public static BrewProgress project(java.util.UUID player, BrewingCurve curve,
            long totalXp) {
        int level = levelFor(curve, totalXp);
        return new BrewProgress(player, totalXp, level,
                xpIntoLevel(curve, totalXp), nextLevelCost(curve, totalXp),
                level >= curve.maxLevel());
    }
}
