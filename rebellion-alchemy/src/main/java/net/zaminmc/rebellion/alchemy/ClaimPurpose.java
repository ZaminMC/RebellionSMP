package net.zaminmc.rebellion.alchemy;

/**
 * What a claim is paying for (ADR-0019 §3): the claim table is generalized
 * over the RPG shape with a purpose discriminator so both two-phase
 * inventory-spanning operations share one CAS discipline.
 */
public enum ClaimPurpose {
    /** Buying an unlock: the controller removes cost items under the
     *  reservation; commit inserts the unlock row. */
    UNLOCK_PURCHASE,
    /** Executing an auto-consumption rule: the controller drinks/removes
     *  the potion under the reservation; commit stamps the rule's
     *  cooldown authority. */
    AUTO_CONSUME;

    /** Parses stored purpose text; null on unknown (tamper signature). */
    public static ClaimPurpose parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (ClaimPurpose purpose : values()) {
            if (purpose.name().equals(trimmed)) {
                return purpose;
            }
        }
        return null;
    }
}
