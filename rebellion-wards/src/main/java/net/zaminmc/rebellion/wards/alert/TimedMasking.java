package net.zaminmc.rebellion.wards.alert;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Timed masking grants (admin command now; counter-rituals in a later phase
 * reuse this store). Pure in-memory working state — masking is a live
 * countermeasure, not an entitlement, so nothing persists across restarts.
 *
 * <p>Reads are on the sweep path: lazy expiry removal keeps the map at the
 * number of actively masked players without any cleanup task.</p>
 */
public final class TimedMasking {

    private final Map<UUID, Long> maskedUntil = new ConcurrentHashMap<>();

    /** Grants (or extends) masking until {@code untilMs}. */
    public void mask(@NotNull UUID player, long untilMs) {
        maskedUntil.merge(player, untilMs, Math::max);
    }

    /** Explicit revoke (admin shortening, tests). */
    public void clear(@NotNull UUID player) {
        maskedUntil.remove(player);
    }

    /** @return true when a live grant covers {@code nowMs}; expired grants are dropped. */
    public boolean masked(@NotNull UUID player, long nowMs) {
        Long until = maskedUntil.get(player);
        if (until == null) {
            return false;
        }
        if (until <= nowMs) {
            maskedUntil.remove(player, until);
            return false;
        }
        return true;
    }

    /** Live grants (diagnostics). */
    public int activeGrants() {
        return maskedUntil.size();
    }
}
