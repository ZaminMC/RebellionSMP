package net.zaminmc.rebellion.artifacts;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One persisted dimension artifact (ADR-0013): the dimension-keyed identity,
 * the config-owned protected-origin anchor, and the mutable lifecycle
 * snapshot. Exactly {@link ArtifactsPolicy#ARTIFACT_COUNT} rows can ever
 * exist — they are created only by the idempotent seed path and every later
 * transition is a pure UPDATE.
 *
 * <p>Anti-clone mechanics: the physical artifact item carries
 * {@code instance} in PDC. Every possession-changing transition (capture,
 * transfer, find, voluntary return) compare-and-sets on the presented
 * instance and supersedes it with a fresh one, so a stale or cloned copy
 * always fails validation and never two valid copies exist. In
 * {@code RETURNING}/{@code SPENT} the column holds the superseding stamp —
 * whatever physical copy is left over is inert.</p>
 *
 * @param artifactKey    dimension key — the row's stable PRIMARY KEY
 * @param artifactId     immutable server-issued artifact UUID (PDC identity)
 * @param state          lifecycle state (never null)
 * @param holder         current holder (HELD) or last rightful holder (LOST);
 *                       null in ORIGIN/RETURNING/SPENT
 * @param instance       live PDC instance stamp (fresh per possession change)
 * @param originWorld    protected origin realm world (config-owned; synced
 *                       at load)
 * @param originX        origin realm anchor x
 * @param originY        origin realm anchor y
 * @param originZ        origin realm anchor z
 * @param holderSinceUtc when the current holder took possession (0 = never)
 * @param stateSinceUtc  when the current state was entered — the return-delay
 *                       base for LOST/SPENT rows
 * @param createdAtUtc   seed epoch millis
 * @param updatedAtUtc   last transition epoch millis
 */
public record Artifact(
        @NotNull ArtifactKey artifactKey,
        @NotNull UUID artifactId,
        @NotNull ArtifactState state,
        UUID holder,
        @NotNull UUID instance,
        @NotNull String originWorld,
        int originX,
        int originY,
        int originZ,
        long holderSinceUtc,
        long stateSinceUtc,
        long createdAtUtc,
        long updatedAtUtc) {

    public Artifact {
        if (state.hasHolder() && holder == null) {
            throw new IllegalArgumentException(
                    "holder is required for state " + state);
        }
        if (!state.hasHolder() && holder != null) {
            throw new IllegalArgumentException(
                    "holder must be null in state " + state);
        }
        if (originWorld == null || originWorld.isBlank()) {
            throw new IllegalArgumentException("originWorld must not be blank");
        }
    }

    /** @return the holder (or last rightful holder) as an Optional. */
    public @NotNull Optional<UUID> holderOpt() {
        return Optional.ofNullable(holder);
    }

    /** Copy with a new state and the fields the transition owns. */
    public @NotNull Artifact with(@NotNull ArtifactState newState,
            UUID newHolder, @NotNull UUID newInstance, long newHolderSinceUtc,
            long newStateSinceUtc, long updatedAtUtc) {
        return new Artifact(artifactKey, artifactId, newState, newHolder,
                newInstance, originWorld, originX, originY, originZ,
                newHolderSinceUtc, newStateSinceUtc, createdAtUtc, updatedAtUtc);
    }

    /** Copy with a re-synced config-owned origin anchor (state untouched). */
    public @NotNull Artifact withOrigin(@NotNull String world, int x, int y,
            int z, long updatedAtUtc) {
        return new Artifact(artifactKey, artifactId, state, holder, instance,
                world, x, y, z, holderSinceUtc, stateSinceUtc, createdAtUtc,
                updatedAtUtc);
    }
}
