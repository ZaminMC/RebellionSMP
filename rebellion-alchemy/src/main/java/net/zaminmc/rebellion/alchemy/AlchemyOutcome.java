package net.zaminmc.rebellion.alchemy;

/**
 * Typed alchemy-operation outcomes; the future command layer maps these to
 * player messages. Idempotent no-ops are distinct on purpose so event
 * handlers can log "nothing done, already handled" without treating it as
 * an error — the same discipline as the RPG {@code RpgOutcome} contract.
 */
public enum AlchemyOutcome {
    // --- brewing XP ---------------------------------------------------------
    /** Fresh XP award (the source key won the event PK). */
    XP_GAINED,
    /** Fresh XP award that crossed one or more level thresholds. */
    LEVEL_UP,
    /** Idempotent award no-op: the source key was already paid. */
    XP_NOOP,

    // --- unlock purchase claims: reserve / release --------------------------
    /** Reservation created; the controller may now remove cost items. */
    RESERVED,
    /** Unlock key is not in the config. */
    UNLOCK_UNKNOWN,
    /** Unlock exists but is disabled. */
    UNLOCK_DISABLED,
    /** The derived brewing level is below the required level. */
    LEVEL_TOO_LOW,
    /** A prerequisite unlock is not owned yet. */
    PREREQUISITE_MISSING,
    /** The unlock is already in the player's ledger (PK probe). */
    ALREADY_OWNED,
    /** A reservation is already held for this player + unlock (cost-race
     *  guard — two purchases cannot double-book one cost set). */
    RESERVATION_HELD,
    /** Reservation released (refund path); digest retained as evidence. */
    RELEASED,
    /** Idempotent release no-op: the claim was not RESERVED. */
    RELEASE_NOOP,

    // --- unlock commit ---------------------------------------------------------
    /** Purchase committed; the unlock row exists (all-or-none). */
    UNLOCK_GRANTED,
    /** Idempotent admin-grant no-op: the unlock was already owned (the
     *  original grant is carried). */
    UNLOCK_GRANT_NOOP,
    /** Idempotent commit no-op: the claim is no longer RESERVED; the
     *  original outcome is carried on the result. */
    COMMIT_NOOP,
    /** Claim id does not exist. */
    CLAIM_NOT_FOUND,

    // --- auto-consumption rules -------------------------------------------------
    /** Rule defined after every policy gate passed. */
    RULE_DEFINED,
    /** Idempotent define no-op: the rule id already exists. */
    RULE_EXISTS,
    /** Per-player live-rule cap reached (policy). */
    RULE_CAP_REACHED,
    /** The trigger is disabled by the operator policy. */
    TRIGGER_DISABLED,
    /** The chosen potion is not in the policy whitelist. */
    POTION_NOT_ALLOWED,
    /** The threshold is outside the trigger's policy bounds. */
    THRESHOLD_OUT_OF_BOUNDS,
    /** The rule cooldown is below the policy floor (anti-bot). */
    COOLDOWN_BELOW_FLOOR,
    /** The priority is outside the policy range. */
    PRIORITY_OUT_OF_RANGE,
    /** The storage scope is invalid. */
    STORAGE_INVALID,
    /** Rule id does not exist (or is removed). */
    RULE_NOT_FOUND,
    /** Rule toggle changed (audited, ownership-guarded CAS). */
    RULE_ENABLED,
    /** Rule toggle changed (audited, ownership-guarded CAS). */
    RULE_DISABLED,
    /** Idempotent toggle no-op (already in the requested state). */
    TOGGLE_NOOP,
    /** Rule removed (stamped; rows kept for forensics). */
    RULE_REMOVED,
    /** Idempotent removal no-op (already removed). */
    RULE_REMOVE_NOOP,
    /** Defensive refusal: the rule exists but is toggled off (the
     *  resolution set never contains it). */
    RULE_NOT_ACTIVE,

    // --- auto-consumption execution claims ---------------------------------------
    /** Execution reservation created; the controller may now drink/remove
     *  the potion under it. */
    AUTO_RESERVED,
    /** Execution refused: the rule's cooldown stamp is still active
     *  (answered from the row, never from scheduler memory). */
    COOLDOWN_ACTIVE,
    /** Execution committed: the potion's cooldown stamp is durable. */
    AUTO_CONSUMED,

    // --- shared failures ---------------------------------------------------------
    /** Caller-supplied arguments failed validation before any access. */
    ARGUMENT_INVALID,
    /** Database error; nothing was committed (fail closed). */
    DB_ERROR
}
