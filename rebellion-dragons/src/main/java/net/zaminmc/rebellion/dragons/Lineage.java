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
 * <p>Since schema v2 the row also carries the controller's persistence
 * snapshot for HATCHED lineages: the last observed {@code loc} world
 * position (all-or-none; {@code null} until the first snapshot after hatch)
 * and the {@code lastPersistUtc} stamp. The entity itself survives chunk
 * unload/reload with its UUID (proven live, ADR-0008 E7) — the persisted
 * position exists so call/recall and the orphan sweep can resolve a pet
 * whose chunk is currently unloaded, and so a restart can reconcile a
 * binding whose entity was removed while the server was down.</p>
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
 * @param locWorld      last persisted pet world name; null unless HATCHED
 *                      and snapshotted at least once
 * @param locX          last persisted pet x (null with locWorld)
 * @param locY          last persisted pet y (null with locWorld)
 * @param locZ          last persisted pet z (null with locWorld)
 * @param lastPersistUtc last controller snapshot write (0 = never)
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
        String locWorld,
        Double locX,
        Double locY,
        Double locZ,
        long lastPersistUtc,
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
        if ((locWorld == null) != (locX == null) || (locWorld == null) != (locZ == null)) {
            throw new IllegalArgumentException(
                    "persisted location is all-or-none (locWorld=" + locWorld + ")");
        }
        if (locWorld != null && locWorld.isBlank()) {
            throw new IllegalArgumentException("locWorld must not be blank");
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

    /** @return the last persisted pet position, when snapshotted. */
    public @NotNull Optional<PersistedLocation> locationOpt() {
        return locWorld == null ? Optional.empty()
                : Optional.of(new PersistedLocation(locWorld, locX, locY, locZ));
    }

    /** @return this lineage's gateway anchor key. */
    public @NotNull GatewayKey gateway() {
        return new GatewayKey(originWorld, gatewayX, gatewayY, gatewayZ);
    }

    /** Persisted pet position (schema v2; all-or-none with locWorld). */
    public record PersistedLocation(@NotNull String world, double x, double y, double z) {
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
                lastPearlHealUtc, null, null, null, null, lastPersistUtc,
                createdAtUtc, updatedAtUtc);
    }

    /** Copy with an updated dragon health snapshot (HATCHED only). */
    public @NotNull Lineage withHealth(double newHealth, long updatedAtUtc) {
        if (state != LineageState.HATCHED) {
            throw new IllegalArgumentException("health only exists while HATCHED");
        }
        return new Lineage(lineageId, slot, state, owner, eggInstance, entityUuid,
                originWorld, gatewayX, gatewayY, gatewayZ, newHealth,
                lastPearlHealUtc, locWorld, locX, locY, locZ, lastPersistUtc,
                createdAtUtc, updatedAtUtc);
    }

    /** Copy with an updated controller snapshot (HATCHED only; position optional). */
    public @NotNull Lineage withSnapshot(Double newHealth,
            @NotNull PersistedLocation location, long persistUtc, long updatedAtUtc) {
        if (state != LineageState.HATCHED) {
            throw new IllegalArgumentException("snapshots only exist while HATCHED");
        }
        return new Lineage(lineageId, slot, state, owner, eggInstance, entityUuid,
                originWorld, gatewayX, gatewayY, gatewayZ, newHealth,
                lastPearlHealUtc, location.world(), location.x(), location.y(),
                location.z(), persistUtc, createdAtUtc, updatedAtUtc);
    }

    /** Copy with an updated pearl-heal stamp (HATCHED only). */
    public @NotNull Lineage withPearlHeal(double newHealth, long healedAtUtc,
            long updatedAtUtc) {
        if (state != LineageState.HATCHED) {
            throw new IllegalArgumentException("pearl heals only exist while HATCHED");
        }
        return new Lineage(lineageId, slot, state, owner, eggInstance, entityUuid,
                originWorld, gatewayX, gatewayY, gatewayZ, newHealth, healedAtUtc,
                locWorld, locX, locY, locZ, lastPersistUtc, createdAtUtc, updatedAtUtc);
    }
}
