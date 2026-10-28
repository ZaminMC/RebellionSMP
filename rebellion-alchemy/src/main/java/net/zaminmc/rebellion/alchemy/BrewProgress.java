package net.zaminmc.rebellion.alchemy;

import java.util.UUID;

/**
 * One progress row (ADR-0019 §1), projected through the pure leveling walk:
 * the level is <em>derived</em> from {@code total_xp} on every read, so it
 * can never drift from the configured curve and a rebalance needs no
 * migration. Raw-row reads go through the service with this projection.
 *
 * @param playerUuid the player
 * @param totalXp the atomic accumulate of every idempotent award
 * @param level the derived level (1..max-level)
 * @param xpIntoLevel the XP accumulated above the current level's base
 * @param nextLevelCost the XP still needed for the next level (0 at
 *        max-level — the curve is clamped)
 * @param maxedOut true when the derived level is the configured maximum
 */
public record BrewProgress(UUID playerUuid, long totalXp, int level,
        long xpIntoLevel, long nextLevelCost, boolean maxedOut) {
}
