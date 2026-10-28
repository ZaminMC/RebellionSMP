package net.zaminmc.rebellion.prison;

/**
 * Release-fee claim states (ADR-0021). The fee row IS the claim of the
 * reserve → charge → commit protocol:
 * <ul>
 *   <li>{@code QUOTED} — the frozen fee exists; the quote is immutable
 *       (score, factor, multiplier, rounding, clamp, amount are the §5.9
 *       "logged as structured values" audit) and re-usable across
 *       retries.</li>
 *   <li>{@code PAID} — the provider debit committed and the sentence was
 *       released in the same all-or-none transaction; the provider id and
 *       ledger entry (transaction) id are stamped.</li>
 *   <li>{@code VOID} — the sentence ended by another route
 *       (escape/rescue/recorded staff override); the frozen quote died
 *       with it. Kept, never deleted.</li>
 * </ul>
 */
public enum FeeState {
    QUOTED,
    PAID,
    VOID;

    /** Parses the stored form; unknown values fail loudly. */
    public static FeeState parse(String stored) {
        return switch (stored) {
            case "QUOTED" -> QUOTED;
            case "PAID" -> PAID;
            case "VOID" -> VOID;
            default -> throw new IllegalArgumentException(
                    "unknown fee state '" + stored + "'");
        };
    }
}
