package net.zaminmc.rebellion.alchemy;

/**
 * The brewing curve (ADR-0019 §5, config family {@code alchemy.brewing}):
 * the max level, the linear per-level cost parameters and the controller's
 * default per-brew award. Validated bounds live here so the loader and the
 * tests share one authority.
 *
 * @param maxLevel 1..MAX_LEVEL
 * @param levelXpBase 1..MAX_PARAM — the cost of 1 → 2
 * @param levelXpGrowth 0..MAX_PARAM — added to the cost each level
 * @param xpPerBrew 1..MAX_PARAM — the default award per completed brew
 */
public record BrewingCurve(int maxLevel, long levelXpBase, long levelXpGrowth,
        long xpPerBrew) {

    public static final int MAX_LEVEL = 1000;
    public static final long MAX_PARAM = 1_000_000L;

    public BrewingCurve {
        if (maxLevel < 1 || maxLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("max-level must be 1.." + MAX_LEVEL
                    + " (got " + maxLevel + ")");
        }
        if (levelXpBase < 1 || levelXpBase > MAX_PARAM) {
            throw new IllegalArgumentException("level-xp-base must be 1.."
                    + MAX_PARAM + " (got " + levelXpBase + ")");
        }
        if (levelXpGrowth < 0 || levelXpGrowth > MAX_PARAM) {
            throw new IllegalArgumentException("level-xp-growth must be 0.."
                    + MAX_PARAM + " (got " + levelXpGrowth + ")");
        }
        if (xpPerBrew < 1 || xpPerBrew > MAX_PARAM) {
            throw new IllegalArgumentException("xp-per-brew must be 1.."
                    + MAX_PARAM + " (got " + xpPerBrew + ")");
        }
    }
}
