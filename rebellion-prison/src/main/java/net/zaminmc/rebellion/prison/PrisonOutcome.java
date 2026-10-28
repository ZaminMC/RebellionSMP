package net.zaminmc.rebellion.prison;

/**
 * Typed prison-operation outcomes (ADR-0021); the future command layer
 * maps these to player messages. Idempotent no-ops are distinct on
 * purpose — the §5.8 "death and respawn events both fire" and "logs back
 * in" duplicate-delivery cases resolve as no-ops, never as errors, so
 * event handlers can log "already handled" without operator noise.
 */
public enum PrisonOutcome {
    // --- death processing ---------------------------------------------------
    /** Fresh death applied: score accumulated, scars clamped, routing run. */
    DEATH_APPLIED,
    /** Idempotent death no-op: the death key was already processed. */
    DEATH_NOOP,

    // --- sentence routing (carried by DEATH_APPLIED payloads) ---------------
    /** Routing created the durable PENDING sentence (retry on respawn/join). */
    SENTENCE_PENDING,
    /** A live sentence already existed (death in prison): score and scars
     *  still applied, no second sentence — the structural live guard. */
    SENTENCE_EXISTS,
    /** Routing policy is NEVER: deaths score and scar, but never jail. */
    ROUTE_DISABLED,
    /** Threshold routing: the accumulated score has not reached the
     *  configured imprisonment threshold yet. */
    SCORE_BELOW_THRESHOLD,

    // --- sentence lifecycle -------------------------------------------------
    /** PENDING sentence confirmed JAILED (the teleport landed). */
    JAILED,
    /** Idempotent confirm: the sentence was already JAILED. */
    JAILED_NOOP,
    /** No live sentence exists for the player. */
    NO_SENTENCE,
    /** The player's live sentence is not in the required state. */
    NOT_IN_STATE,
    /** Sentence released (escape / rescue / recorded staff override). */
    RELEASED,
    /** Idempotent release no-op: the sentence was already released. */
    RELEASE_NOOP,
    /** Staff override without an acting staff UUID is refused (§5.8:
     *  overrides must be auditable, never anonymous). */
    ADMIN_ACTOR_REQUIRED,

    // --- release fee (claim protocol) --------------------------------------
    /** Fee paid and the sentence released all-or-none. */
    PAID_RELEASED,
    /** Idempotent payment no-op: this sentence was already paid out. */
    PAID_NOOP,
    /** The account balance is below the frozen fee. Nothing changed; the
     *  quote stays re-usable. */
    INSUFFICIENT_FUNDS,
    /** The provider refused the charge for another typed reason. */
    PROVIDER_REFUSED,
    /** The live sentence is not JAILED (a PENDING sentence must be
     *  confirmed or reverted first). */
    NOT_JAILED,

    // --- rehabilitation -----------------------------------------------------
    /** Task completion recorded and healing applied (clamped to scars). */
    REHAB_COMPLETED,
    /** Idempotent rehab no-op: the completion key was already recorded. */
    REHAB_NOOP,
    /** The task key is not in the config. */
    REHAB_UNKNOWN_TASK,
    /** The task exists but is disabled. */
    REHAB_DISABLED,
    /** Every occurrence of the task has been used. */
    REHAB_EXHAUSTED,
    /** Nothing left to heal: the scar ledger is fully restored. */
    SCARS_FULLY_RESTORED,

    // --- shared -------------------------------------------------------------
    /** Argument validation failed before anything changed. */
    ARGUMENT_INVALID,
    /** Persistence failure; the transaction rolled back. */
    DB_ERROR
}
