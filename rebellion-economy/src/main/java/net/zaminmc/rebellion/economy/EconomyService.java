package net.zaminmc.rebellion.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.MigrationSpec;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.economy.EconomyOutcome;
import net.zaminmc.rebellion.api.economy.EconomyProvider;
import net.zaminmc.rebellion.api.economy.EconomyResult;
import net.zaminmc.rebellion.economy.db.EconomySchema;
import net.zaminmc.rebellion.economy.db.EconomyStore;
import net.zaminmc.rebellion.economy.db.EconomyStore.LedgerRow;
import org.jetbrains.annotations.NotNull;

/**
 * Ledger-backed {@link EconomyProvider} implementation (ADR-0021, master
 * prompt §5.9): "atomic ledgers, UUID account identifiers, idempotency
 * keys, exact decimal/integer units, transaction reasons, and audit
 * history".
 *
 * <h2>Protocol of one mutation (credit or debit)</h2>
 * <ol>
 *   <li>Argument validation before the transaction: amount positive and
 *       within the single-mutation bound; key/reason grammar-checked.
 *       Failures are typed refusals that touched nothing.</li>
 *   <li>One database transaction. The ledger insert is the <em>gate</em>
 *       (the ADR-0019 discipline: the idempotency anchor writes first):
 *       a {@code (account, key)} pair that already exists makes the INSERT
 *       lose the constraint and the call resolves to the typed successful
 *       no-op carrying the surviving entry's id and recorded balance —
 *       a crashed caller retries the same key and reconciles without a
 *       second charge.</li>
 *   <li>Fresh entry: the new balance is computed from the account row,
 *       invariant-checked (never negative, never above the configured
 *       maximum — §5.9's overflow answer), and written as
 *       {@code balance_after} on the ledger row; the account row is then
 *       inserted-or-accumulated in the same transaction. All-or-none: a
 *       balance never moves without its durable ledger entry.</li>
 * </ol>
 *
 * <p>Concurrency: SQLite serializes writers; on MySQL the account-row
 * write serializes same-account writers and the {@code UNIQUE
 * (account_uuid, idempotency_key)} index is the structural backstop. The
 * one race worth naming — two identical (account, key) mutations
 * committing concurrently — is caught by the constraint, rolls the loser
 * back, and resolves on a single bounded retry as the replay it is.</p>
 */
public final class EconomyService implements EconomyProvider {

    /** Single-mutation safety ceiling (config-independent). */
    public static final long MAX_AMOUNT = 1L << 40;

    /** Read-only view of this service's module key for boot wiring. */
    public static final String MODULE_KEY = "economy";

    private final RebellionDatabase db;
    private final EconomyConfig config;
    private final LongSupplier clock;

    public EconomyService(@NotNull RebellionDatabase db,
            @NotNull EconomyConfig config, @NotNull LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public @NotNull String providerId() {
        return "rebellion-economy";
    }

    @Override
    public long balance(@NotNull UUID account) {
        return db.transaction(tx -> EconomyStore.balance(tx, account).orElse(0L))
                .join();
    }

    @Override
    public @NotNull EconomyResult debit(@NotNull UUID account, long minorAmount,
            @NotNull String idempotencyKey, @NotNull String reason) {
        // Amount validation happens on the caller's value BEFORE any sign
        // arithmetic: a negative debit must never flip into a credit.
        if (minorAmount < 1 || minorAmount > MAX_AMOUNT) {
            return refused(EconomyOutcome.AMOUNT_INVALID, account);
        }
        return mutate(account, -minorAmount, idempotencyKey, reason);
    }

    @Override
    public @NotNull EconomyResult credit(@NotNull UUID account, long minorAmount,
            @NotNull String idempotencyKey, @NotNull String reason) {
        if (minorAmount < 1 || minorAmount > MAX_AMOUNT) {
            return refused(EconomyOutcome.AMOUNT_INVALID, account);
        }
        return mutate(account, minorAmount, idempotencyKey, reason);
    }

    /** @return the module schema registry for boot-time migration. */
    public static List<MigrationSpec> migrations() {
        return EconomySchema.migrations();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Typed refusal thrown inside a transaction: rolls it back. */
    private static final class Refusal extends RuntimeException {
        final EconomyResult result;

        Refusal(EconomyResult result) {
            super(result.outcome().name(), null, false, false);
            this.result = result;
        }
    }

    /**
     * Constraint race on the idempotency index: unwind, then retry once —
     * the probe on the retry resolves as the replay it is.
     */
    private static final class KeyRace extends RuntimeException {
    }

    private EconomyResult mutate(UUID account, long signedDelta, String idempotencyKey,
            String reason) {
        long amount = Math.abs(signedDelta);
        if (amount < 1 || amount > MAX_AMOUNT) {
            return refused(EconomyOutcome.AMOUNT_INVALID, account);
        }
        if (idempotencyKey == null
                || !EconomyConfig.KEY.matcher(idempotencyKey).matches()) {
            return refused(EconomyOutcome.ARGUMENT_INVALID, account);
        }
        if (reason == null || !EconomyConfig.REASON.matcher(reason).matches()) {
            return refused(EconomyOutcome.ARGUMENT_INVALID, account);
        }
        try {
            return attempt(account, signedDelta, idempotencyKey, reason);
        } catch (CompletionException c) {
            Throwable cause = c.getCause() != null ? c.getCause() : c;
            if (cause instanceof KeyRace) {
                // Constraint race on the idempotency index: retry once —
                // the probe on the retry resolves as the replay it is.
                try {
                    return attempt(account, signedDelta, idempotencyKey, reason);
                } catch (CompletionException retry) {
                    return toFailure(retry.getCause() != null ? retry.getCause() : retry,
                            account);
                }
            }
            return toFailure(cause, account);
        }
    }

    /** One full transaction attempt; typed failures unwind as {@link Refusal}. */
    private EconomyResult attempt(UUID account, long signedDelta, String idempotencyKey,
            String reason) {
        return db.<EconomyResult>transaction(tx -> {
            long now = clock.getAsLong();
            long current = EconomyStore.balance(tx, account).orElse(0L);
            long next = current + signedDelta;

            // Invariants checked before any write; refusal rolls back clean.
            if (next < 0) {
                throw new Refusal(EconomyResult.refused(
                        EconomyOutcome.INSUFFICIENT_FUNDS, current));
            }
            if (next > config.maxBalanceMinor()) {
                throw new Refusal(EconomyResult.refused(
                        EconomyOutcome.BOUNDS_EXCEEDED, current));
            }

            // The gate: the ledger row is the idempotency anchor. A loser
            // here is a same-key replay — resolve from the survivor.
            UUID entryId = UUID.randomUUID();
            if (EconomyStore.insertLedger(tx, entryId, account, idempotencyKey,
                    EconomyStore.truncate(reason, 64), signedDelta, next, now) == 0) {
                LedgerRow survivor = EconomyStore.entryByKey(tx, account,
                        idempotencyKey).orElse(null);
                if (survivor != null) {
                    return EconomyResult.noop(survivor.entryId(),
                            survivor.balanceAfter());
                }
                // Constraint fired but the row is not visible in this
                // snapshot (isolation): unwind and retry once.
                throw new KeyRace();
            }

            // Account row: first mutation inserts, later ones accumulate.
            if (EconomyStore.insertAccount(tx, account, next, now) == 0) {
                EconomyStore.accumulate(tx, account, signedDelta, now);
            }
            return EconomyResult.changed(
                    signedDelta < 0 ? EconomyOutcome.DEBITED : EconomyOutcome.CREDITED,
                    entryId, next);
        }).join();
    }

    private static EconomyResult toFailure(Throwable error, UUID account) {
        Throwable cause = error instanceof CompletionException c && c.getCause() != null
                ? c.getCause() : error;
        if (cause instanceof Refusal refusal) {
            return refusal.result;
        }
        return EconomyResult.refused(EconomyOutcome.SERVICE_ERROR, 0L);
    }

    private EconomyResult refused(EconomyOutcome outcome, UUID account) {
        long balance;
        try {
            balance = balance(account);
        } catch (RuntimeException e) {
            balance = 0L;
        }
        return EconomyResult.refused(outcome, balance);
    }
}
