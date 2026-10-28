package net.zaminmc.rebellion.dragons;

/**
 * Typed lineage operation outcomes; commands map these to player messages.
 * The two idempotency outcomes are distinct on purpose so the event layer
 * can log "nothing done, already handled" without treating it as an error.
 */
public enum LineageOutcome {
    /** Gateway issuance minted a new lineage egg. */
    ISSUED,
    /** Idempotent issuance no-op: this gateway already minted its lineage. */
    ISSUED_ALREADY,
    /** Configured lineage cap (or the hard policy ceiling) reached; nothing minted. */
    CAP_REACHED,
    /** Egg hatched; lineage bound to the new living entity. */
    HATCHED,
    /** Hatch refused: stale/clone egg instance, or lineage already hatched. */
    HATCH_CONFLICT,
    /** Death processed: lineage returned to an egg, re-issued to the killer. */
    EGG_RETURNED,
    /** Idempotent death no-op: event already processed (or stale entity). */
    EGG_RETURN_NOOP,
    /** Egg routed into the protected recovery mailbox. */
    RECOVERY_ENTERED,
    /** Recovery egg claimed by its recorded claimant. */
    CLAIMED,
    /** Admin routed the recovery egg to a new holder. */
    ROUTED,
    /** Admin retirement succeeded (audited, terminal). */
    RETIRED,
    /** Pearl heal recorded: health advanced, cooldown stamp refreshed. */
    PEARL_HEALED,
    /** Pearl heal refused: the cooldown is still active (idempotent retry). */
    PEARL_COOLDOWN,
    /** Controller snapshot written (health + position + persist stamp). */
    SNAPSHOT_SAVED,
    /** Idempotent snapshot no-op: the binding was superseded/dead before the write. */
    SNAPSHOT_STALE,
    NOT_FOUND,
    /** Transition refused by the current state (e.g. claim on a held egg). */
    INVALID_STATE,
    /** Caller-supplied arguments failed validation before any database access. */
    ARGUMENT_INVALID,
    DB_ERROR
}
