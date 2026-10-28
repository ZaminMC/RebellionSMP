package net.zaminmc.rebellion.rpg;

/**
 * Tracking-precision tiers, ordered coarse to fine (ADR-0016 §3). The tier
 * is the persistence representation of master prompt §5.3's "must not
 * reveal more precision than configured": a session's granted tier is
 * stamped at cast time, the controller may render a signal of <em>at
 * most</em> that tier, and an active mask degrades the effective tier by
 * exactly one step.
 *
 * <p>Tier semantics (what the controller is allowed to reveal at each
 * level; the actual rendering is controller-phase work):</p>
 * <ul>
 *   <li>{@link #DIMENSION} — the target's current world, nothing else.</li>
 *   <li>{@link #REGION} — world plus a coarse direction (8-way compass)
 *       and a distance bucket; never an exact vector.</li>
 *   <li>{@link #COORDINATE} — the exact block position.</li>
 * </ul>
 */
public enum Precision {
    DIMENSION,
    REGION,
    COORDINATE;

    private static final Precision[] ORDER = values();

    /** @return this tier, or empty when there is no coarser tier to fall to. */
    public java.util.Optional<Precision> degraded() {
        return ordinal() == 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(ORDER[ordinal() - 1]);
    }

    /** Parses config input case-insensitively; null on unknown text. */
    public static Precision parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (Precision tier : ORDER) {
            if (tier.name().equals(trimmed)) {
                return tier;
            }
        }
        return null;
    }
}
