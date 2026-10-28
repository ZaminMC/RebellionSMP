package net.zaminmc.rebellion.prison;

import java.math.BigDecimal;
import net.zaminmc.rebellion.prison.PrisonConfig.ReleasePolicy;
import org.jetbrains.annotations.NotNull;

/**
 * The pure §5.8 release-fee formula (ADR-0021):
 * {@code death_score × inflation_factor × multiplier}, evaluated in
 * {@link BigDecimal} — never unchecked long multiplication — rounded with
 * the configured mode, then clamped into the configured minor-unit
 * bounds <em>before</em> any long conversion (arbitrary-precision space,
 * so a hostile death score or a fat inflation factor cannot overflow a
 * long: the clamp fires first and the result is always inside
 * {@code [0, MAX_FEE_LIMIT]}).
 */
public final class FeeMath {

    private FeeMath() {
    }

    /**
     * @param deathScore the sentence-time accumulated death score
     * @param policy the operator's release-fee policy
     * @return the structured §5.9 audit quote (score, factor, multiplier,
     *         rounding, raw product, clamped minor-unit result)
     */
    public static @NotNull FeeQuote quote(long deathScore, @NotNull ReleasePolicy policy) {
        BigDecimal raw = BigDecimal.valueOf(deathScore)
                .multiply(policy.inflationFactor())
                .multiply(BigDecimal.valueOf(policy.multiplier()));
        BigDecimal rounded = raw.setScale(0, policy.rounding());

        long amount;
        FeeQuote.FeeClamp clamp;
        if (rounded.compareTo(BigDecimal.valueOf(policy.maxFeeMinor())) > 0) {
            amount = policy.maxFeeMinor();
            clamp = FeeQuote.FeeClamp.CLAMPED_TO_MAX;
        } else if (rounded.compareTo(BigDecimal.valueOf(policy.minFeeMinor())) < 0) {
            amount = policy.minFeeMinor();
            clamp = FeeQuote.FeeClamp.CLAMPED_TO_MIN;
        } else {
            // Inside the bounds by the comparisons above, so exact by
            // construction — but stay defensive about the long edge.
            amount = rounded.longValueExact();
            clamp = FeeQuote.FeeClamp.EXACT;
        }
        return new FeeQuote(deathScore, policy.inflationFactor().toPlainString(),
                policy.multiplier(), policy.rounding().name(), raw, amount, clamp);
    }
}
