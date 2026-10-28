package net.zaminmc.rebellion.prison;

import java.math.BigDecimal;

/**
 * One pure release-fee computation (master prompt §5.8/§5.9, ADR-0021):
 * {@code death_score × inflation_factor × multiplier}, evaluated in
 * {@link BigDecimal} (never unchecked long multiplication), rounded with
 * an explicit mode, then clamped into the configured {@code [min, max]}
 * minor-unit bounds — the overflow answer, because the clamp happens in
 * arbitrary-precision space before any long conversion.
 *
 * @param deathScore the sentence-time death score the fee is computed from
 * @param inflationFactor the configured factor, stored as its exact string
 *        form (audit reproducibility)
 * @param multiplier the configured product multiplier (default 5)
 * @param rounding the rounding mode name applied to the raw product
 * @param rawAmount the unrounded product (audit transparency)
 * @param amountMinor the final minor-unit amount inside the bounds
 * @param clamp whether the bounds moved the amount
 */
public record FeeQuote(long deathScore, String inflationFactor, int multiplier,
        String rounding, BigDecimal rawAmount, long amountMinor, FeeClamp clamp) {

    /** How the computed amount was placed inside the configured bounds. */
    public enum FeeClamp {
        EXACT,
        CLAMPED_TO_MIN,
        CLAMPED_TO_MAX
    }
}
