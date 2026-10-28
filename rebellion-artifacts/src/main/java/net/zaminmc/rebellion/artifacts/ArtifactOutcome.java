package net.zaminmc.rebellion.artifacts;

/**
 * Typed artifact-operation outcomes; the future command layer maps these to
 * player messages. Idempotent no-ops are distinct on purpose so the event
 * layer can log "nothing done, already handled" without treating it as an
 * error — the same discipline as the dragons {@code LineageOutcome} contract.
 */
public enum ArtifactOutcome {
    /** Artifact captured from its origin realm; now HELD by the discoverer. */
    CAPTURED,
    /** Lost artifact found; now HELD by the finder (fresh instance stamp). */
    FOUND,
    /** Possession transferred between players (fresh instance stamp). */
    TRANSFERRED,
    /** Held artifact recorded lost; parked in the recoverable LOST state. */
    LOST_RECORDED,
    /** Idempotent loss no-op: the artifact was already parked LOST. */
    LOST_ALREADY,
    /** Holder voluntarily surrendered the artifact; entered RETURNING. */
    RETURN_SUBMITTED,
    /** Return-delay elapsed for a LOST/SPENT row; sweep entered RETURNING. */
    RETURN_QUEUED,
    /** RETURNING row arrived home; artifact is back at its protected ORIGIN. */
    RETURNED,
    /** Idempotent arrival no-op: the artifact is already at ORIGIN. */
    RETURN_NOOP,
    /** Rebellion event occurrence triggered (durable event id minted). */
    EVENT_TRIGGERED,
    /** Idempotent trigger no-op: an occurrence is already active (the
     *  existing event is carried on the result). */
    EVENT_ALREADY_ACTIVE,
    /** Trigger refused: the holder does not control all three artifacts. */
    NOT_QUALIFIED,
    /** Rebellion catastrophe completed; guard cleared; reconquest possible. */
    EVENT_COMPLETED,
    /** Idempotent completion no-op: this occurrence was already completed. */
    EVENT_COMPLETE_NOOP,
    /** The active event's overpowering action spent all three artifacts. */
    SPENT,
    /** Spending refused: not all three artifacts are HELD by the trigger
     *  holder (includes a retried spend after success — all-or-none held). */
    SPENT_CONFLICT,
    /** Admin routed a stuck (LOST/RETURNING) artifact to a player. */
    ROUTED,
    /** Presented instance stamp does not match the row's live stamp — a
     *  cloned, stale or superseded physical copy. Nothing changed. */
    INSTANCE_STALE,
    /** Return refused while a rebellion event occurrence is active. */
    EVENT_ACTIVE,
    NOT_FOUND,
    /** Transition refused by the current state (wrong state/holder). */
    INVALID_STATE,
    /** Caller-supplied arguments failed validation before any database access. */
    ARGUMENT_INVALID,
    /** The configured feature is disabled (rebellion-event.enabled: false). */
    FEATURE_DISABLED,
    DB_ERROR
}
