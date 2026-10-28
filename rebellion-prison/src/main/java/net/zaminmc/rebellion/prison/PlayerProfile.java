package net.zaminmc.rebellion.prison;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Per-player prison profile (ADR-0021): the accumulated death score and
 * the health-scar ledger. Immutable row snapshot.
 *
 * <p>The <em>active penalty</em> is derived, never stored:
 * {@code min(scarPoints - restoredPoints, maxActivePenalty)} — the
 * controller converts it into a max-health attribute value. Both ledgers
 * are write-clamped at mutation time (scars can never exceed the
 * configured active-penalty ceiling; restoration can never exceed scars),
 * so the stored values are always consistent and no sweep ever
 * recomputes anything.</p>
 */
public record PlayerProfile(@NotNull UUID player, long deathScore, int scarPoints,
        int restoredPoints, long updatedUtc) {

    /** The config ceiling applies to the derived view, not the raw rows. */
    public int activePenalty(int maxActivePenalty) {
        return Math.max(0, Math.min(scarPoints - restoredPoints, maxActivePenalty));
    }

    /** Scar points not yet restored (bounded by the write clamps). */
    public int remainingScars() {
        return scarPoints - restoredPoints;
    }

    /** Freshly-created zero profile. */
    public static PlayerProfile zero(@NotNull UUID player, long nowUtc) {
        return new PlayerProfile(player, 0L, 0, 0, nowUtc);
    }

    /** @return the same identity with new ledger values. */
    public @NotNull PlayerProfile with(long newScore, int newScars, int newRestored,
            long nowUtc) {
        return new PlayerProfile(player, newScore, newScars, newRestored, nowUtc);
    }
}
