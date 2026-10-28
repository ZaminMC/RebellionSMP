package net.zaminmc.rebellion.artifacts;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of an artifact mutation: typed outcome, human-readable detail for
 * the command layer, the affected artifact and/or rebellion event when one
 * exists, and whether this call <em>actually changed persisted state</em>.
 * Mirrors the dragons {@code LineageResult} contract so command rendering
 * stays mechanical.
 *
 * <p>Idempotent no-ops ({@code LOST_ALREADY}, {@code EVENT_ALREADY_ACTIVE},
 * {@code RETURN_NOOP}, {@code RETURN_QUEUED} on an in-transit row,
 * {@code EVENT_COMPLETE_NOOP}) are {@link #success() successful} — the
 * invariant held; nothing was owed — but carry {@code mutated == false} so
 * callers log them as no-ops and the service skips the mirror swap.</p>
 *
 * <p>Multi-row operations (seed, spending) deliberately carry no artifact on
 * the result; their rows are read back through {@code ArtifactService.all()}
 * — the service updates its mirror internally.</p>
 */
public record ArtifactResult(@NotNull ArtifactOutcome outcome, @Nullable String detail,
        @Nullable Artifact artifact, @Nullable RebellionEvent event, boolean mutated) {

    /** Real state change (capture/find/transfer/loss/return/spend/route/trigger). */
    public static ArtifactResult changed(@NotNull ArtifactOutcome outcome,
            @Nullable Artifact artifact) {
        return new ArtifactResult(outcome, null, artifact, null, true);
    }

    /** Real state change with a detail for the command layer. */
    public static ArtifactResult changed(@NotNull ArtifactOutcome outcome,
            @Nullable String detail, @Nullable Artifact artifact) {
        return new ArtifactResult(outcome, detail, artifact, null, true);
    }

    /** Real multi-row state change (seed/spend): detail only, rows read back
     *  through the service readers. */
    public static ArtifactResult changed(@NotNull ArtifactOutcome outcome,
            @NotNull String detail) {
        return new ArtifactResult(outcome, detail, null, null, true);
    }

    /** Real state change carrying the affected rebellion event. */
    public static ArtifactResult changed(@NotNull ArtifactOutcome outcome,
            @Nullable String detail, @Nullable RebellionEvent event) {
        return new ArtifactResult(outcome, detail, null, event, true);
    }

    /** Idempotent no-op: successful, but nothing changed. */
    public static ArtifactResult unchanged(@NotNull ArtifactOutcome outcome,
            @Nullable String detail, @Nullable Artifact artifact) {
        return new ArtifactResult(outcome, detail, artifact, null, false);
    }

    /** Idempotent no-op carrying an event (e.g. EVENT_ALREADY_ACTIVE). */
    public static ArtifactResult unchanged(@NotNull ArtifactOutcome outcome,
            @Nullable String detail, @Nullable RebellionEvent event,
            @SuppressWarnings("unused") boolean eventCarrier) {
        return new ArtifactResult(outcome, detail, null, event, false);
    }

    /** Refused operation; never mutated anything. */
    public static ArtifactResult failure(@NotNull ArtifactOutcome outcome,
            @Nullable String detail) {
        return new ArtifactResult(outcome, detail, null, null, false);
    }

    /** True for every success AND idempotent no-op. */
    public boolean success() {
        return outcome == ArtifactOutcome.CAPTURED
                || outcome == ArtifactOutcome.FOUND
                || outcome == ArtifactOutcome.TRANSFERRED
                || outcome == ArtifactOutcome.LOST_RECORDED
                || outcome == ArtifactOutcome.LOST_ALREADY
                || outcome == ArtifactOutcome.RETURN_SUBMITTED
                || outcome == ArtifactOutcome.RETURN_QUEUED
                || outcome == ArtifactOutcome.RETURNED
                || outcome == ArtifactOutcome.RETURN_NOOP
                || outcome == ArtifactOutcome.EVENT_TRIGGERED
                || outcome == ArtifactOutcome.EVENT_ALREADY_ACTIVE
                || outcome == ArtifactOutcome.EVENT_COMPLETED
                || outcome == ArtifactOutcome.EVENT_COMPLETE_NOOP
                || outcome == ArtifactOutcome.SPENT
                || outcome == ArtifactOutcome.ROUTED;
    }

    /** @return the affected artifact, when one is carried. */
    public @NotNull Optional<Artifact> artifactOpt() {
        return Optional.ofNullable(artifact);
    }

    /** @return the affected rebellion event, when one is carried. */
    public @NotNull Optional<RebellionEvent> eventOpt() {
        return Optional.ofNullable(event);
    }
}
