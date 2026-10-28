package net.zaminmc.rebellion.rpg;

/**
 * Claim-row states (ADR-0016 §2). All transitions are compare-and-sets on
 * the {@code state} column; there is no path from COMMITTED or RELEASED
 * back to RESERVED.
 */
public enum ClaimState {
    RESERVED,
    COMMITTED,
    RELEASED;

    /** Parses stored state text; null on unknown (tamper signature). */
    public static ClaimState parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (ClaimState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        return null;
    }
}
