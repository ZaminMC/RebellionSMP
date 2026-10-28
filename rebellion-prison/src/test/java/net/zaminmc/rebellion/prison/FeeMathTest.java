package net.zaminmc.rebellion.prison;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.zaminmc.rebellion.prison.FeeQuote.FeeClamp;
import net.zaminmc.rebellion.prison.PrisonConfig.ReleasePolicy;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure §5.8 fee formula (ADR-0021): exact BigDecimal evaluation,
 * explicit rounding, and the clamp-before-conversion overflow answer — a
 * hostile death score or fat factor can never overflow a long because the
 * bounds fire in arbitrary-precision space first.
 */
final class FeeMathTest {

    private static ReleasePolicy policy(String factor, int multiplier,
            RoundingMode rounding, long min, long max) {
        return new ReleasePolicy(new BigDecimal(factor), multiplier, rounding,
                min, max);
    }

    @Test
    void theProductFormulaHolds() {
        FeeQuote quote = FeeMath.quote(10, policy("1.00", 5,
                RoundingMode.HALF_UP, 0, 1_000_000));
        assertEquals(50L, quote.amountMinor());
        assertEquals(FeeClamp.EXACT, quote.clamp());
        assertEquals(0, quote.rawAmount().compareTo(BigDecimal.TEN
                .multiply(new BigDecimal("1.00")).multiply(BigDecimal.valueOf(5))));
        assertEquals("1.00", quote.inflationFactor());
        assertEquals("HALF_UP", quote.rounding());
        assertEquals(5, quote.multiplier());
        assertEquals(10L, quote.deathScore());
    }

    @Test
    void roundingIsExplicitAndConfigurable() {
        // 1 x 1.10 x 5 = 5.5 -> HALF_UP rounds up, DOWN rounds toward zero.
        assertEquals(6L, FeeMath.quote(1, policy("1.10", 5,
                RoundingMode.HALF_UP, 0, 100)).amountMinor());
        assertEquals(5L, FeeMath.quote(1, policy("1.10", 5,
                RoundingMode.DOWN, 0, 100)).amountMinor());
    }

    @Test
    void fractionalFactorsEvaluateExactly() {
        // 3 x 1.25 x 5 = 18.75 -> HALF_UP -> 19.
        FeeQuote quote = FeeMath.quote(3, policy("1.25", 5,
                RoundingMode.HALF_UP, 0, 100));
        assertEquals(19L, quote.amountMinor());
        assertEquals(0, quote.rawAmount().compareTo(new BigDecimal("18.75")));
    }

    @Test
    void boundsClampInBigDecimalSpaceBeforeAnyLong() {
        // Score beyond long-scale magnitude: the raw product is ~9.2e28,
        // far past long range — the clamp fires first, nothing overflows.
        FeeQuote huge = FeeMath.quote(Long.MAX_VALUE / 2, policy("1000000", 100,
                RoundingMode.HALF_UP, 0, 1_000_000_000L));
        assertEquals(1_000_000_000L, huge.amountMinor());
        assertEquals(FeeClamp.CLAMPED_TO_MAX, huge.clamp());

        FeeQuote floored = FeeMath.quote(0, policy("1.00", 5,
                RoundingMode.HALF_UP, 100, 1_000_000));
        assertEquals(100L, floored.amountMinor());
        assertEquals(FeeClamp.CLAMPED_TO_MIN, floored.clamp());
    }

    @Test
    void inflatedFactorsRaiseTheFeeExactly() {
        // 7 x 1.35 x 5 = 47.25 -> HALF_UP -> 47.
        assertEquals(47L, FeeMath.quote(7, policy("1.35", 5,
                RoundingMode.HALF_UP, 0, 1_000_000)).amountMinor());
        // The shipped defaults: score x 1.00 x 5.
        assertEquals(5L, FeeMath.quote(1, PrisonConfig.defaults().release())
                .amountMinor());
        assertEquals(50L, FeeMath.quote(10, PrisonConfig.defaults().release())
                .amountMinor());
    }
}
