package net.zaminmc.rebellion.prison;

/**
 * Why a sentence ended (ADR-0021). {@code PAID} is exclusive to the fee
 * claim protocol ({@code payRelease}); the others route through
 * {@code markReleased}, and {@code ADMIN_OVERRIDE} always records the
 * acting staff member — the §5.8 "auditable staff overrides" rule.
 */
public enum ReleaseReason {
    PAID,
    ESCAPE,
    RESCUE,
    ADMIN_OVERRIDE;

    /** Parses the stored form; unknown values fail loudly. */
    public static ReleaseReason parse(String stored) {
        return switch (stored) {
            case "PAID" -> PAID;
            case "ESCAPE" -> ESCAPE;
            case "RESCUE" -> RESCUE;
            case "ADMIN_OVERRIDE" -> ADMIN_OVERRIDE;
            default -> throw new IllegalArgumentException(
                    "unknown release reason '" + stored + "'");
        };
    }
}
