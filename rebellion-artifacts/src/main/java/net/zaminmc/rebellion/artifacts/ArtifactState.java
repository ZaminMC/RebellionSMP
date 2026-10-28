package net.zaminmc.rebellion.artifacts;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent lifecycle state of a dimension artifact (ADR-0013). One row is
 * one artifact; the state travels in the database and every transition is a
 * compare-and-set guarded by the current state plus the row's live instance
 * stamp (and holder, where the transition is holder-initiated) — never a
 * blind write.
 *
 * <p>Holder semantics per state (enforced by {@link Artifact}'s compact
 * constructor and by every CAS statement):</p>
 * <ul>
 *   <li>{@link #ORIGIN} — the artifact rests at its protected, discoverable
 *       origin realm; holder is NULL; the origin item carries the live
 *       instance.</li>
 *   <li>{@link #HELD} — a player possesses the live instance; holder is that
 *       player.</li>
 *   <li>{@link #LOST} — dropped/stolen-then-dropped/undeliverable; a
 *       recoverable state. The last rightful holder is kept on the row for
 *       forensics and the admin route; the live instance stays resolvable so
 *       a finder can present the physical item.</li>
 *   <li>{@link #RETURNING} — in transit back to the origin realm (voluntary
 *       return, or the return-delay sweep from LOST/SPENT); holder NULL; the
 *       instance column already holds the superseding stamp, so any leftover
 *       physical copy is inert.</li>
 *   <li>{@link #SPENT} — consumed by the Rebellion event's overpowering
 *       action; the lineage is not invalidated — the return-delay sweep
 *       routes SPENT rows through RETURNING back to ORIGIN, so the world can
 *       reconquer and there is no permanent winner.</li>
 * </ul>
 *
 * <p>There is deliberately no terminal state and no admin retirement in this
 * model (master prompt §5.5 gives artifacts no retire flow — unlike dragon
 * lineages, an artifact can never be permanently removed).</p>
 */
public enum ArtifactState {
    ORIGIN,
    HELD,
    LOST,
    RETURNING,
    SPENT;

    /** Parses the stored representation; unknown values fail loudly. */
    public static @NotNull ArtifactState parse(@NotNull String raw) {
        return ArtifactState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** @return true while the physical item carrying the live instance can
     *          be presented for a possession change (capture/find). */
    public boolean isPresentable() {
        return this == ORIGIN || this == HELD || this == LOST;
    }

    /** @return true while a player is recorded as the (last) holder. */
    public boolean hasHolder() {
        return this == HELD || this == LOST;
    }
}
