package net.zaminmc.rebellion.dragons;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of a lineage mutation: typed outcome, human-readable detail for the
 * command layer, the affected lineage when one exists, and whether this
 * call <em>actually changed persisted state</em>. Mirrors the teams/wards
 * result contracts so command rendering stays mechanical.
 *
 * <p>Idempotent no-ops ({@code ISSUED_ALREADY}, {@code EGG_RETURN_NOOP},
 * re-parking an already-parked egg, re-claiming an egg you already hold)
 * are {@link #success() successful} — the invariant held; nothing was owed —
 * but carry {@code mutated == false} so callers log them as no-ops and the
 * service skips the mirror swap.</p>
 */
public record LineageResult(@NotNull LineageOutcome outcome, @Nullable String detail,
        @Nullable Lineage lineage, boolean mutated) {

    /** Real state change (mint/hatch/death/claim/route/retire success). */
    public static LineageResult changed(@NotNull LineageOutcome outcome,
            @Nullable Lineage lineage) {
        return new LineageResult(outcome, null, lineage, true);
    }

    /** Real state change with a routing detail for the command layer. */
    public static LineageResult changed(@NotNull LineageOutcome outcome,
            @Nullable String detail, @Nullable Lineage lineage) {
        return new LineageResult(outcome, detail, lineage, true);
    }

    /** Idempotent no-op: successful, but nothing changed. */
    public static LineageResult unchanged(@NotNull LineageOutcome outcome,
            @Nullable String detail, @Nullable Lineage lineage) {
        return new LineageResult(outcome, detail, lineage, false);
    }

    /** Refused operation; never mutated anything. */
    public static LineageResult failure(@NotNull LineageOutcome outcome,
            @Nullable String detail) {
        return new LineageResult(outcome, detail, null, false);
    }

    /** True for mint/hatch/death/claim/route/retire success AND idempotent no-ops. */
    public boolean success() {
        return outcome == LineageOutcome.ISSUED
                || outcome == LineageOutcome.ISSUED_ALREADY
                || outcome == LineageOutcome.HATCHED
                || outcome == LineageOutcome.EGG_RETURNED
                || outcome == LineageOutcome.EGG_RETURN_NOOP
                || outcome == LineageOutcome.RECOVERY_ENTERED
                || outcome == LineageOutcome.CLAIMED
                || outcome == LineageOutcome.ROUTED
                || outcome == LineageOutcome.RETIRED;
    }
}
