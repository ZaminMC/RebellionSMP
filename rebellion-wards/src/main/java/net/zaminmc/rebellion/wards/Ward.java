package net.zaminmc.rebellion.wards;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One persisted watch ward (ADR-0007). A ward is bound to a placed anchor
 * block ({@code x/y/z} is the anchor's block-center position) and reports
 * approaching outsiders to its owner/team.
 *
 * <p><strong>No-claim contract:</strong> a ward carries no protection data
 * by design — there are no flags, no region, no membership-based denial.
 * It is information only. The type system enforces this: nothing in this
 * record can express a protection decision.</p>
 *
 * <p>Identity and authority: {@code id} is the server-issued ward UUID,
 * {@code owner} the creating player, {@code teamId} the creator's team at
 * creation time (absent for teamless owners or when the teams module is
 * missing). {@code active} toggles detection; inactive wards stay persisted
 * but never alert.</p>
 *
 * @param id          ward UUID (server-issued, never client input)
 * @param name        owner-unique display name (validated by {@link WardName})
 * @param owner       creating player UUID
 * @param teamId      owning team UUID or empty
 * @param world       world name at anchor time
 * @param x           anchor block x (block-center)
 * @param y           anchor block y (block-center)
 * @param z           anchor block z (block-center)
 * @param radius      horizontal detection radius in blocks (validated bounds)
 * @param type        ward flavor
 * @param active      whether detection runs for this ward
 * @param createdAtUtc creation epoch millis
 */
public record Ward(
        @NotNull UUID id,
        @NotNull String name,
        @NotNull UUID owner,
        @NotNull Optional<UUID> teamId,
        @NotNull String world,
        double x,
        double y,
        double z,
        double radius,
        @NotNull WardType type,
        boolean active,
        long createdAtUtc) {

    /** Anchor key: one ward per placed anchor block, enforced by a DB UNIQUE. */
    public record AnchorKey(@NotNull String world, int x, int y, int z) {
        public static AnchorKey of(@NotNull String world, int x, int y, int z) {
            return new AnchorKey(world, x, y, z);
        }
    }

    public Ward {
        if (teamId == null) {
            teamId = Optional.empty();
        }
    }

    /** Anchor this ward is bound to (block coordinates). */
    public @NotNull AnchorKey anchor() {
        return new AnchorKey(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** Copy with a different active flag (index/store replace on toggle). */
    public @NotNull Ward withActive(boolean newActive) {
        return new Ward(id, name, owner, teamId, world, x, y, z, radius, type,
                newActive, createdAtUtc);
    }

    /**
     * Squared horizontal distance to a position. Y is deliberately ignored:
     * detection is horizontal-plane only, per the calibration contract.
     */
    public double horizontalDistanceSquared(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }
}
