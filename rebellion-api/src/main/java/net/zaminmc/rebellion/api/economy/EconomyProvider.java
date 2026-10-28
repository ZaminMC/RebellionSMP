package net.zaminmc.rebellion.api.economy;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-module money contract (master prompt §5.9: "the shared economy
 * provider/adapter", §5.8: prison fees ride it). The internal
 * {@code rebellion-economy} module registers the ledger-backed
 * implementation; a future optional Vault bridge would register an adapter
 * implementation with the same shape — callers never hard-code either.
 *
 * <h2>Contract rules (binding on every implementation)</h2>
 * <ul>
 *   <li><strong>Minor units, exact integers.</strong> Every amount is a
 *       {@code long} count of the currency's smallest unit. No floats, no
 *       doubles, no unchecked multiplication — display scaling is the
 *       implementation's concern, never the caller's.</li>
 *   <li><strong>Atomic + idempotent.</strong> A charge is one all-or-none
 *       mutation of the account row plus its append-only ledger entry,
 *       keyed by {@code (account, idempotencyKey)}. A replayed key is a
 *       typed successful no-op carrying the original entry id — a caller
 *       that crashed between charge and its own commit can safely retry
 *       the same key and reconcile.</li>
 *   <li><strong>Fail closed.</strong> Persistence failure refuses the
 *       operation; nothing is minted or consumed. Insufficient balance
 *       refuses a debit; balances never go negative.</li>
 *   <li><strong>Never a hot path.</strong> Implementations may hit a
 *       database. Per §5.9, callers must never invoke this from the main
 *       server thread — route through the persistence executor and marshal
 *       results back.</li>
 *   <li><strong>Audited.</strong> Every successful mutation is recorded
 *       with its caller-supplied reason; the ledger entry id is the
 *       transaction id callers store in their own audit rows.</li>
 * </ul>
 */
public interface EconomyProvider {

    /**
     * Stable provider identity for audit rows (master prompt §5.9: the fee
     * record stores the provider) — e.g. {@code rebellion-economy} or a
     * future {@code vault-bridge}.
     */
    @NotNull String providerId();

    /**
     * Current balance in minor units. An account with no row reads zero;
     * the read never mutates state.
     */
    long balance(@NotNull UUID account);

    /**
     * Charges {@code minorAmount} from the account. Refuses
     * {@code INSUFFICIENT_FUNDS} (nothing changes) rather than allowing a
     * negative balance.
     *
     * @param account the account UUID (never null)
     * @param minorAmount positive amount in minor units
     * @param idempotencyKey caller-composed unique key per logical charge
     *        (grammar-checked by the implementation)
     * @param reason audit reason (grammar-checked, e.g.
     *        {@code prison.release-fee})
     */
    @NotNull EconomyResult debit(@NotNull UUID account, long minorAmount,
            @NotNull String idempotencyKey, @NotNull String reason);

    /**
     * Grants {@code minorAmount} to the account (staff grants, refunds,
     * event rewards). Refuses {@code BOUNDS_EXCEEDED} when the grant would
     * push the account above the configured maximum.
     *
     * @param account the account UUID (never null)
     * @param minorAmount positive amount in minor units
     * @param idempotencyKey caller-composed unique key per logical grant
     * @param reason audit reason (e.g. {@code admin.grant})
     */
    @NotNull EconomyResult credit(@NotNull UUID account, long minorAmount,
            @NotNull String idempotencyKey, @NotNull String reason);
}
