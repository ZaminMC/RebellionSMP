package net.zaminmc.rebellion.alchemy;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of an alchemy mutation: typed outcome, human-readable detail for
 * the future command layer, a typed payload when one exists, and whether
 * this call <em>actually changed persisted state</em>. Generic over the
 * payload (progress, claim, grant, rule) — the RPG result contract.
 *
 * <p>Idempotent no-ops ({@code XP_NOOP}, {@code RELEASE_NOOP},
 * {@code COMMIT_NOOP}, {@code RULE_EXISTS}, {@code TOGGLE_NOOP},
 * {@code RULE_REMOVE_NOOP}) are {@link #success() successful} — the
 * invariant held; nothing was owed — but carry {@code mutated == false} so
 * callers log them as no-ops. Typed refusals are never successful and never
 * mutated anything.</p>
 *
 * @param <T> the payload type carried by this operation family
 */
public record AlchemyResult<T>(@NotNull AlchemyOutcome outcome,
        @Nullable String detail, @Nullable T payload, boolean mutated) {

    /** Real state change carrying the affected row. */
    public static <T> AlchemyResult<T> changed(@NotNull AlchemyOutcome outcome,
            @Nullable T payload) {
        return new AlchemyResult<>(outcome, null, payload, true);
    }

    /** Real state change with a detail for the command layer. */
    public static <T> AlchemyResult<T> changed(@NotNull AlchemyOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new AlchemyResult<>(outcome, detail, payload, true);
    }

    /** Idempotent no-op: successful, but nothing changed. */
    public static <T> AlchemyResult<T> unchanged(@NotNull AlchemyOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new AlchemyResult<>(outcome, detail, payload, false);
    }

    /** Refused operation; never mutated anything. */
    public static <T> AlchemyResult<T> failure(@NotNull AlchemyOutcome outcome,
            @Nullable String detail) {
        return new AlchemyResult<>(outcome, detail, null, false);
    }

    /** True for every success AND idempotent no-op. */
    public boolean success() {
        return outcome != AlchemyOutcome.UNLOCK_UNKNOWN
                && outcome != AlchemyOutcome.UNLOCK_DISABLED
                && outcome != AlchemyOutcome.LEVEL_TOO_LOW
                && outcome != AlchemyOutcome.PREREQUISITE_MISSING
                && outcome != AlchemyOutcome.ALREADY_OWNED
                && outcome != AlchemyOutcome.RESERVATION_HELD
                && outcome != AlchemyOutcome.CLAIM_NOT_FOUND
                && outcome != AlchemyOutcome.RULE_CAP_REACHED
                && outcome != AlchemyOutcome.TRIGGER_DISABLED
                && outcome != AlchemyOutcome.POTION_NOT_ALLOWED
                && outcome != AlchemyOutcome.THRESHOLD_OUT_OF_BOUNDS
                && outcome != AlchemyOutcome.COOLDOWN_BELOW_FLOOR
                && outcome != AlchemyOutcome.PRIORITY_OUT_OF_RANGE
                && outcome != AlchemyOutcome.STORAGE_INVALID
                && outcome != AlchemyOutcome.RULE_NOT_FOUND
                && outcome != AlchemyOutcome.RULE_NOT_ACTIVE
                && outcome != AlchemyOutcome.COOLDOWN_ACTIVE
                && outcome != AlchemyOutcome.ARGUMENT_INVALID
                && outcome != AlchemyOutcome.DB_ERROR;
    }

    /** @return the payload, when one is carried. */
    public @NotNull Optional<T> payloadOpt() {
        return Optional.ofNullable(payload);
    }
}
