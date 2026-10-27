package net.zaminmc.rebellion.dragons;

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One persisted dragon lineage (ADR-0009): the immutable identity, the
 * observed vanilla gateway that minted it, and the mutable lifecycle
 * snapshot. At most {@link LineagePolicy#HARD_CEILING} rows can ever exist
 * and rows are minted only by gateway issuance — {@code owner},
 * {@code eggInstance}, {@code entityUuid} and {@code state} change over a
 * lifetime, {@code lineageId}, {@code slot} and the gateway anchor never do.
 *
 * <p>Identity stamps are the anti-clone/anti-double-delivery mechanism: the
 * physical egg item carries {@code eggInstance} in PDC and the living pet
 * dragon is bound to {@code entityUuid}. Every (re)issue mints a fresh
 * {@code eggInstance}, so stale or cloned eggs fail validation, and death
 * processing compare-and-sets on {@code entityUuid} so duplicate events
 * resolve to no-ops (master prompt §5.6).</p>
 *
 * @param lineageId     immutable server-issued lineage UUID (PRIMARY KEY)
 * @param slot          allocation ordinal, 1..; never reused, never shifts
 * @param state         lifecycle state (never null)
 * @param owner         current egg holder or dragon owner; null only for
 *                      RETIRED or an egg that never had a holder recorded
 * @param eggInstance   live PDC instance stamp of the egg (fresh per issue);
 *                      the only value a physical egg item may present
 * @param entityUuid    bound living pet entity; present only when HATCHED
 * @param originWorld   End world name where the backing gateway was observed
 * @param gatewayX      observed vanilla gateway anchor block x
 * @param gatewayY      observed vanilla gateway anchor block y
 * @param gatewayZ      observed vanilla gateway anchor block z
 * @param health        dragon health snapshot while HATCHED (null otherwise)
 * @param lastPearlHealUtc last pearl-heal epoch millis (0 = never)
 * @param createdAtUtc  mint epoch millis
 * @param updatedAtUtc  last transition epoch millis
 */
public record Lineage(
        @NotNull UUID lineageId,
        int slot,
        @NotNull LineageState state,
        UUID owner,
        @NotNull UUID eggInstance,
        UUID entityUuid,
        @NotNull String originWorld,
        int gatewayX,
        int gatewayY,
        int gatewayZ,
        Double health,
        long lastPearlHealUtc,
        long createdAtUtc,
        long updatedAtUtc) {

    /** Gateway anchor key: one lineage per observed gateway position, enforced by a DB UNIQUE. */
    public record GatewayKey(@NotNull String world, int x, int y, int z) {
        public static @NotNull GatewayKey of(@NotNull String world, int x, int y, int z) {
            return new GatewayKey(world, x, y, z);
        }
    }

    public Lineage {
        if (owner == null && state != LineageState.RETIRED) {
            throw new IllegalArgumentException(
                    "owner is required for state " + state);
        }
        if (state == LineageState.HATCHED && entityUuid == null) {
            throw new IllegalArgumentException("HATCHED lineage requires entityUuid");
        }
        if (entityUuid != null && state != LineageState.HATCHED) {
            throw new IllegalArgumentException(
                    "entityUuid present in non-HATCHED state " + state);
        }
    }

    /** @return the owner as an Optional (empty for RETIRED). */
    public @NotNull Optional<UUID> ownerOpt() {
        return Optional.ofNullable(owner);
    }

    /** @return the bound entity, when the lineage is a living pet dragon. */
    public @NotNull Optional<UUID> entityOpt() {
        return Optional.ofNullable(entityUuid);
    }

    /** @return this lineage's gateway anchor key. */
    public @NotNull GatewayKey gateway() {
        return new GatewayKey(originWorld, gatewayX, gatewayY, gatewayZ);
    }

    /** Copy with a new state and the fields the transition owns. */
    public @NotNull Lineage with(
            @NotNull LineageState newState,
            UUID newOwner,
            @NotNull UUID newEggInstance,
            UUID newEntity,
            Double newHealth,
            long updatedAtUtc) {
        return new Lineage(lineageId, slot, newState, newOwner, newEggInstance,
                newEntity, originWorld, gatewayX, gatewayY, gatewayZ, newHealth,
                lastPearlHealUtc, createdAtUtc, updatedAtUtc);
    }

    /** Copy with an updated dragon health snapshot (HATCHED only). */
    public @NotNull Lineage withHealth(double newHealth, long updatedAtUtc) {
        if (state != LineageState.HATCHED) {
            throw new IllegalArgumentException("health only exists while HATCHED");
        }
        return new Lineage(lineageId, slot, state, owner, eggInstance, entityUuid,
                originWorld, gatewayX, gatewayY, gatewayZ, newHealth,
                lastPearlHealUtc, createdAtUtc, updatedAtUtc);
    }
}
