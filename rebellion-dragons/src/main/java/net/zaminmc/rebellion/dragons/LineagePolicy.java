package net.zaminmc.rebellion.dragons;

/**
 * Fixed lineage policy constants (master prompt §5.6, ADR-0009). The
 * server-wide lineage limit exists because the End has at most 20 eligible
 * vanilla gateways; the ceiling is therefore structural, not a tuning knob.
 *
 * <p>Exceeding the ceiling must never happen silently: {@link DragonsConfig}
 * rejects out-of-range values at load time (loud config error), and
 * {@code LineageService} re-clamps inside the mint transaction as defense
 * in depth.</p>
 */
public final class LineagePolicy {

    private LineagePolicy() {
    }

    /**
     * Hard ceiling on obtainable dragon lineages across the entire server.
     * Not configurable upward: the vanilla End gateway ring is finite.
     */
    public static final int HARD_CEILING = 20;

    /** Sane upper bound for a hatch-time health snapshot (validation only). */
    public static final double MAX_HATCH_HEALTH = 1024.0;

    /** @return the configured cap clamped to the structurally valid range. */
    public static int effectiveCap(int configured) {
        return Math.max(1, Math.min(configured, HARD_CEILING));
    }
}
