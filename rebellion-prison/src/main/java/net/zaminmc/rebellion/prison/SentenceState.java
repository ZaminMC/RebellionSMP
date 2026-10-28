package net.zaminmc.rebellion.prison;

/**
 * Sentence lifecycle states (ADR-0021). {@code PENDING} is the durable
 * {@code pending_imprisonment} of master prompt §5.8 — created by death
 * routing, confirmed into {@code JAILED} by the controller after the
 * teleport lands, and safely retried on respawn/join while it stays
 * pending. {@code RELEASED} is terminal; history rows survive (the
 * {@code live_guard} column frees the per-player uniqueness slot).
 */
public enum SentenceState {
    PENDING,
    JAILED,
    RELEASED;

    /** @return whether this state owns the player's one live sentence slot. */
    public boolean live() {
        return this == PENDING || this == JAILED;
    }

    /** Parses the stored form; unknown values fail loudly. */
    public static SentenceState parse(String stored) {
        return switch (stored) {
            case "PENDING" -> PENDING;
            case "JAILED" -> JAILED;
            case "RELEASED" -> RELEASED;
            default -> throw new IllegalArgumentException(
                    "unknown sentence state '" + stored + "'");
        };
    }
}
