package net.zaminmc.rebellion.api.economy;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of an economy mutation. Carries the typed outcome, the ledger
 * entry id (the durable transaction id a caller like the prison fee audit
 * must store), and the account balance as it stood after the operation.
 *
 * <p>Refusals carry the <em>unchanged</em> balance; idempotent no-ops
 * carry the balance as recorded by the original entry. Either way the
 * value is safe to display without a second lookup.</p>
 *
 * @param outcome typed operation outcome (never null)
 * @param entryId the ledger entry UUID this mutation is durably recorded
 *        under (the "transaction ID"), or null on refusals
 * @param balanceAfter the account balance in minor units after this
 *        operation (unchanged on refusal/no-op)
 * @param mutated whether persisted state actually changed
 */
public record EconomyResult(@NotNull EconomyOutcome outcome,
        @Nullable UUID entryId, long balanceAfter, boolean mutated) {

    /** Fresh mutation carrying its durable ledger entry. */
    public static @NotNull EconomyResult changed(@NotNull EconomyOutcome outcome,
            @NotNull UUID entryId, long balanceAfter) {
        return new EconomyResult(outcome, entryId, balanceAfter, true);
    }

    /** Idempotent replay: nothing changed, the original entry answers. */
    public static @NotNull EconomyResult noop(@NotNull UUID entryId, long balanceAfter) {
        return new EconomyResult(EconomyOutcome.NOOP_DUPLICATE, entryId,
                balanceAfter, false);
    }

    /** Refusal; balance unchanged, no entry recorded. */
    public static @NotNull EconomyResult refused(@NotNull EconomyOutcome outcome,
            long balance) {
        return new EconomyResult(outcome, null, balance, false);
    }

    /** @return true for fresh mutations AND idempotent no-ops. */
    public boolean success() {
        return outcome == EconomyOutcome.DEBITED
                || outcome == EconomyOutcome.CREDITED
                || outcome == EconomyOutcome.NOOP_DUPLICATE;
    }

    /** @return the ledger entry id, when one exists. */
    public @NotNull Optional<UUID> entryOpt() {
        return Optional.ofNullable(entryId);
    }
}
