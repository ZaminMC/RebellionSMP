package net.zaminmc.rebellion.prison;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The durable release-fee audit row (ADR-0021, master prompt §5.9: "The
 * release fee calculation must be transparent to the player and logged as
 * structured values: score, inflation factor, multiplier, rounding,
 * result, provider, and transaction ID"). Every §5.9 field is a column:
 * the quote is frozen at creation and never rewritten; payment stamps the
 * provider identity and the economy ledger entry id.
 */
public record FeeRecord(@NotNull UUID feeId, @NotNull UUID sentenceId,
        @NotNull UUID player, @NotNull FeeState state, long deathScore,
        @NotNull String inflationFactor, int multiplier, @NotNull String rounding,
        @NotNull FeeQuote.FeeClamp clamp, long amountMinor, @Nullable String provider,
        @Nullable UUID ledgerEntry, long quotedUtc, @Nullable Long resolvedUtc) {
}
