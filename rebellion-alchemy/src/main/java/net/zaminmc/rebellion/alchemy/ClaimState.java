package net.zaminmc.rebellion.alchemy;

/**
 * Claim-row states (ADR-0019 §3): all transitions are compare-and-sets on
 * the {@code state} column; there is no path from COMMITTED or RELEASED
 * back to RESERVED. Identical discipline to the ADR-0016 ritual claims.
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
