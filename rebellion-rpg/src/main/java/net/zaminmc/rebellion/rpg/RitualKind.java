package net.zaminmc.rebellion.rpg;

/**
 * The three ritual families this module implements (ADR-0016 §2). The kind
 * selects the commit path — each kind has its own explicit commit method
 * on the service; there is no reflective dispatch.
 */
public enum RitualKind {
    /** Reveals information about a specifically selected player; commits a
     *  tracking session row (unless the failure roll lost it). */
    TRACKING,
    /** Counter-ritual: grants the caster (or a chosen player) a masking
     *  effect that degrades incoming tracking precision by one tier. */
    COUNTER_MASK,
    /** Knowledge ritual: unlocks a discovery (recipe or knowledge flag)
     *  directly, riding the same transactional claim protocol. */
    KNOWLEDGE;

    /** Parses config input case-insensitively; null on unknown text. */
    public static RitualKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (RitualKind kind : values()) {
            if (kind.name().equals(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
