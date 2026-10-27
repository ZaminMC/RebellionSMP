package net.zaminmc.rebellion.api.result;

/**
 * Stable machine-readable failure codes shared by Rebellion services.
 *
 * These codes exist so callers can react to failures without parsing message
 * text. Every code maps to a documented recovery behavior in the service that
 * produces it.
 */
public enum FailureCode {
    /** A required service or provider is not available right now. Retryable after re-enable. */
    SERVICE_UNAVAILABLE,

    /** Caller-supplied arguments are invalid (bad format, out of range, unknown key). */
    INVALID_ARGUMENT,

    /** The operation is invalid for the current state of the target (e.g. hatching a used egg). */
    INVALID_STATE,

    /** The database refused the operation; unique asset creation must fail closed. */
    PERSISTENCE_FAILURE,

    /** The async persistence queue is saturated; the operation was rejected without side effects. */
    PERSISTENCE_OVERLOADED,

    /** The acting player lacks the required permission. */
    PERMISSION_FAILURE,

    /** The referenced entity/account/record does not exist. */
    NOT_FOUND,

    /** The operation conflicts with a concurrent one (e.g. double claim); safe to re-check state. */
    CONFLICT
}
