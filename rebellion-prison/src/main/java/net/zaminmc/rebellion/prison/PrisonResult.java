package net.zaminmc.rebellion.prison;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of a prison mutation: typed outcome, human-readable detail for
 * the future command layer, a typed payload when one exists, and whether
 * this call <em>actually changed persisted state</em>. Generic over the
 * payload — the RPG/alchemy result contract, third edition.
 *
 * <p>Idempotent no-ops ({@code DEATH_NOOP}, {@code JAILED_NOOP},
 * {@code PAID_NOOP}, {@code REHAB_NOOP}, {@code RELEASE_NOOP}) are
 * {@link #success() successful} — the invariant held, nothing was owed —
 * but carry {@code mutated == false} so callers log them as no-ops.
 * Typed refusals are never successful and never mutated anything.</p>
 *
 * @param <T> the payload type carried by this operation family
 */
public record PrisonResult<T>(@NotNull PrisonOutcome outcome, @Nullable String detail,
        @Nullable T payload, boolean mutated) {

    /** Real state change carrying the affected row/view. */
    public static <T> PrisonResult<T> changed(@NotNull PrisonOutcome outcome,
            @Nullable T payload) {
        return new PrisonResult<>(outcome, null, payload, true);
    }

    /** Real state change with a detail for the command layer. */
    public static <T> PrisonResult<T> changed(@NotNull PrisonOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new PrisonResult<>(outcome, detail, payload, true);
    }

    /** Idempotent no-op: successful, but nothing changed. */
    public static <T> PrisonResult<T> unchanged(@NotNull PrisonOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new PrisonResult<>(outcome, detail, payload, false);
    }

    /** Refused operation; never mutated anything. */
    public static <T> PrisonResult<T> failure(@NotNull PrisonOutcome outcome,
            @Nullable String detail) {
        return new PrisonResult<>(outcome, detail, null, false);
    }

    /** Refused operation carrying the unchanged row for display; never a
     *  success and never a mutation. */
    public static <T> PrisonResult<T> refused(@NotNull PrisonOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new PrisonResult<>(outcome, detail, payload, false);
    }

    /** True for every success AND idempotent no-op. */
    public boolean success() {
        return outcome != PrisonOutcome.ROUTE_DISABLED
                && outcome != PrisonOutcome.SCORE_BELOW_THRESHOLD
                && outcome != PrisonOutcome.NO_SENTENCE
                && outcome != PrisonOutcome.NOT_IN_STATE
                && outcome != PrisonOutcome.ADMIN_ACTOR_REQUIRED
                && outcome != PrisonOutcome.INSUFFICIENT_FUNDS
                && outcome != PrisonOutcome.PROVIDER_REFUSED
                && outcome != PrisonOutcome.NOT_JAILED
                && outcome != PrisonOutcome.REHAB_UNKNOWN_TASK
                && outcome != PrisonOutcome.REHAB_DISABLED
                && outcome != PrisonOutcome.REHAB_EXHAUSTED
                && outcome != PrisonOutcome.SCARS_FULLY_RESTORED
                && outcome != PrisonOutcome.ARGUMENT_INVALID
                && outcome != PrisonOutcome.DB_ERROR;
    }

    /** @return the payload, when one is carried. */
    public @NotNull Optional<T> payloadOpt() {
        return Optional.ofNullable(payload);
    }
}
