package net.zaminmc.rebellion.rpg;

/**
 * Encounter-row states (ADR-0016 §4). Every transition is a compare-and-set
 * on {@code state}; there is no path back to ACTIVE.
 */
public enum EncounterState {
    ACTIVE,
    COMPLETED,
    EXPIRED,
    DESPAWNED;

    /** Parses stored state text; null on unknown (tamper signature). */
    public static EncounterState parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (EncounterState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        return null;
    }
}
