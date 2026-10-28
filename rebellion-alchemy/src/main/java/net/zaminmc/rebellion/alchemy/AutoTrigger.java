package net.zaminmc.rebellion.alchemy;

/**
 * Auto-consumption trigger vocabulary (ADR-0019 §4). The four master-prompt
 * examples map one-to-one; the operator policy family whitelists which are
 * player-usable at all, and HEALTH_BELOW is the only trigger carrying a
 * threshold (a percent). Live evaluation (health reads, fire ticks, portal
 * entry, fall distance) is controller-phase; persistence owns the policy
 * bounds and the rule rows.
 */
public enum AutoTrigger {
    HEALTH_BELOW,
    BURNING,
    NETHER_ENTRY,
    FALLING;

    /** @return true when this trigger carries a threshold bound. */
    public boolean usesThreshold() {
        return this == HEALTH_BELOW;
    }

    /** Parses config/rule text; null on unknown. */
    public static AutoTrigger parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (AutoTrigger trigger : values()) {
            if (trigger.name().equals(trimmed)) {
                return trigger;
            }
        }
        return null;
    }
}
