package net.zaminmc.rebellion.rpg;

/**
 * Typed RPG-operation outcomes; the future command layer maps these to
 * player messages. Idempotent no-ops are distinct on purpose so event
 * handlers can log "nothing done, already handled" without treating it as
 * an error — the same discipline as the artifacts {@code ArtifactOutcome}
 * and dragons {@code LineageOutcome} contracts.
 */
public enum RpgOutcome {
    // --- discoveries -----------------------------------------------------
    /** New discovery recorded (INSERT won the PK). */
    DISCOVERED,
    /** Idempotent discovery no-op: already known to this player. */
    ALREADY_KNOWN,

    // --- ritual claims: reserve / release ---------------------------------
    /** Reservation created; the controller may now remove ingredients. */
    RESERVED,
    /** Ritual key is not in the config. */
    RITUAL_UNKNOWN,
    /** Ritual exists but is disabled. */
    RITUAL_DISABLED,
    /** Too many attempts (any claim state) inside the rate-limit window. */
    RATE_LIMITED,
    /** The latest committed attempt is still inside the ritual cooldown. */
    COOLDOWN_ACTIVE,
    /** A reservation is already held for this player + ritual (ingredient
     *  race guard — a second cast cannot double-book one reagent set). */
    RESERVATION_HELD,
    /** Reservation released (refund path); digest retained as evidence. */
    RELEASED,
    /** Idempotent release no-op: the claim was not RESERVED. */
    RELEASE_NOOP,

    // --- ritual commits ----------------------------------------------------
    /** Tracking ritual committed and a session row exists. */
    TRACKING_COMMITTED,
    /** Tracking ritual committed but the failure roll lost it: claim spent
     *  (cooldown bought), no session. The event row records FAILED. */
    RITUAL_FAILED,
    /** Mask ritual committed; a masking effect row exists. */
    MASK_GRANTED,
    /** Knowledge ritual committed; the discovery was recorded (or was
     *  already known — carried in the detail). */
    KNOWLEDGE_COMMITTED,
    /** Tracking commit refused (session cap reached): the claim was
     *  auto-released in the same transaction — refund path. */
    SESSION_CAP_REACHED,
    /** Idempotent commit no-op: the claim is no longer RESERVED; the
     *  original outcome is carried on the result. */
    COMMIT_NOOP,
    /** Claim id does not exist. */
    CLAIM_NOT_FOUND,

    // --- tracking reads ------------------------------------------------------
    /** Session revoked by an admin/recovery path. */
    SESSION_REVOKED,
    /** Idempotent revocation no-op (already revoked or expired). */
    SESSION_REVOKE_NOOP,
    /** Session id does not exist. */
    SESSION_NOT_FOUND,

    // --- encounters -----------------------------------------------------------
    /** Encounter row begun (ACTIVE) after all definition checks passed. */
    ENCOUNTER_BEGUN,
    /** Encounter key is not in the config. */
    ENCOUNTER_UNKNOWN,
    /** Encounter exists but is disabled (the shipped default). */
    ENCOUNTER_DISABLED,
    /** World is not in the encounter's allowed list. */
    WORLD_REFUSED,
    /** Concurrency cap: too many ACTIVE rows for this encounter key. */
    CONCURRENCY_CAP,
    /** Encounter cooldown active since the last resolved row. */
    ENCOUNTER_COOLDOWN,
    /** Encounter completed; reward discoveries granted to the helper. */
    ENCOUNTER_COMPLETED,
    /** Idempotent completion no-op: another helper won the CAS (no reward). */
    ENCOUNTER_ALREADY_RESOLVED,
    /** TTL sweep moved a stale ACTIVE row to EXPIRED. */
    ENCOUNTER_EXPIRED,
    /** Soul entity attached to the ACTIVE encounter row. */
    SOUL_ATTACHED,
    /** Idempotent attach no-op (already attached or not ACTIVE). */
    SOUL_ATTACH_NOOP,
    /** Encounter id does not exist. */
    ENCOUNTER_NOT_FOUND,

    // --- shared failures ---------------------------------------------------------
    /** Caller-supplied arguments failed validation before any access. */
    ARGUMENT_INVALID,
    /** Database error; nothing was committed (fail closed). */
    DB_ERROR
}
