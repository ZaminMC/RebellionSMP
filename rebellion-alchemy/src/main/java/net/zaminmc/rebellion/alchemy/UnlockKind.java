package net.zaminmc.rebellion.alchemy;

/**
 * The two progression paths of master prompt §5.7. The distinction is a
 * config contract: POWER grants ride potion brewing strength; EFFICIENCY
 * grants ride costs, storage and automation convenience — and efficiency
 * "must not quietly add combat strength", which the grant shapes make
 * structural (an EFFICIENCY unlock simply has no amplifier/duration fields).
 */
public enum UnlockKind {
    /** Stronger effects, longer durations, advanced recipes for the named
     *  potion type at the required brewing level. */
    POWER,
    /** Lower resource costs, better storage/bag capacity, safer consumption
     *  automation — never direct combat strength. */
    EFFICIENCY;

    /** Parses config text; null on unknown (collected as a config issue). */
    public static UnlockKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (UnlockKind kind : values()) {
            if (kind.name().equals(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
