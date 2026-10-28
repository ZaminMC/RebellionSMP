package net.zaminmc.rebellion.rpg;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of an RPG mutation: typed outcome, human-readable detail for the
 * future command layer, a typed payload when one exists, and whether this
 * call <em>actually changed persisted state</em>. Generic over the payload
 * because the module's payloads genuinely differ (claim, session,
 * encounter, discovery); the artifacts/dragons single-record shapes do not
 * fit four families.
 *
 * <p>Idempotent no-ops ({@code ALREADY_KNOWN}, {@code RELEASE_NOOP},
 * {@code COMMIT_NOOP}, {@code ENCOUNTER_ALREADY_RESOLVED},
 * {@code SOUL_ATTACH_NOOP}) are {@link #success() successful} — the
 * invariant held; nothing was owed — but carry {@code mutated == false} so
 * callers log them as no-ops. Typed refusals are never successful and
 * never mutated anything (the one deliberate exception is
 * {@code SESSION_CAP_REACHED}, which <em>auto-releases</em> the claim in
 * its transaction and is therefore a mutated refusal — documented on the
 * outcome).</p>
 *
 * @param <T> the payload type carried by this operation family
 */
public record RpgResult<T>(@NotNull RpgOutcome outcome, @Nullable String detail,
        @Nullable T payload, boolean mutated) {

    /** Real state change carrying the affected row. */
    public static <T> RpgResult<T> changed(@NotNull RpgOutcome outcome, @Nullable T payload) {
        return new RpgResult<>(outcome, null, payload, true);
    }

    /** Real state change with a detail for the command layer. */
    public static <T> RpgResult<T> changed(@NotNull RpgOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new RpgResult<>(outcome, detail, payload, true);
    }

    /** Idempotent no-op: successful, but nothing changed. */
    public static <T> RpgResult<T> unchanged(@NotNull RpgOutcome outcome,
            @Nullable String detail, @Nullable T payload) {
        return new RpgResult<>(outcome, detail, payload, false);
    }

    /** Refused operation; never mutated anything. */
    public static <T> RpgResult<T> failure(@NotNull RpgOutcome outcome,
            @Nullable String detail) {
        return new RpgResult<>(outcome, detail, null, false);
    }

    /** True for every success AND idempotent no-op. */
    public boolean success() {
        return outcome != RpgOutcome.RITUAL_UNKNOWN
                && outcome != RpgOutcome.RITUAL_DISABLED
                && outcome != RpgOutcome.RATE_LIMITED
                && outcome != RpgOutcome.COOLDOWN_ACTIVE
                && outcome != RpgOutcome.RESERVATION_HELD
                && outcome != RpgOutcome.SESSION_CAP_REACHED
                && outcome != RpgOutcome.CLAIM_NOT_FOUND
                && outcome != RpgOutcome.SESSION_NOT_FOUND
                && outcome != RpgOutcome.ENCOUNTER_UNKNOWN
                && outcome != RpgOutcome.ENCOUNTER_DISABLED
                && outcome != RpgOutcome.WORLD_REFUSED
                && outcome != RpgOutcome.CONCURRENCY_CAP
                && outcome != RpgOutcome.ENCOUNTER_COOLDOWN
                && outcome != RpgOutcome.ENCOUNTER_NOT_FOUND
                && outcome != RpgOutcome.ARGUMENT_INVALID
                && outcome != RpgOutcome.DB_ERROR;
    }

    /** @return the payload, when one is carried. */
    public @NotNull Optional<T> payloadOpt() {
        return Optional.ofNullable(payload);
    }
}
