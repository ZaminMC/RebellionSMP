package net.zaminmc.rebellion.api.economy;

/**
 * Typed economy-operation outcomes. Idempotent no-ops are distinct from both
 * refusals and fresh mutations so callers (prison fee payment, future shop
 * flows) can log "already charged, nothing owed" without treating a replay
 * as an error or as double revenue.
 */
public enum EconomyOutcome {
    /** Fresh charge committed; the account balance decreased. */
    DEBITED,
    /** Fresh grant committed; the account balance increased. */
    CREDITED,
    /** Idempotent replay: the idempotency key was already used for this
     *  account. Successful no-op — the original entry id and balance are
     *  carried back so callers can reconcile after a crash. */
    NOOP_DUPLICATE,
    /** The account balance is below the requested debit. Nothing changed. */
    INSUFFICIENT_FUNDS,
    /** The amount failed validation (non-positive or above the bound).
     *  Nothing changed. */
    AMOUNT_INVALID,
    /** The mutation would push the account outside its configured bounds
     *  (e.g. above the maximum balance). Nothing changed. */
    BOUNDS_EXCEEDED,
    /** The idempotency key or reason failed grammar validation. */
    ARGUMENT_INVALID,
    /** Provider-side persistence failure. Nothing changed. */
    SERVICE_ERROR
}
