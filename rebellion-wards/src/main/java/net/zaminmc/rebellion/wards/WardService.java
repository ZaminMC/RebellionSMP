package net.zaminmc.rebellion.wards;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.db.WardStore;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ward authority: transactional persistence plus the live in-memory mirror
 * (spatial index + anchor map). Every mutation runs as one database
 * transaction with cap checks inside it, so races (double create at the
 * cap, two players binding one anchor) resolve through the transaction plus
 * the schema's UNIQUE backstops — the same strategy the teams module proved
 * in ADR-0006.
 *
 * <p>The database is the durable authority; the index is a loaded mirror
 * updated only after a normal commit. The anchor map is a derived view of
 * the same rows (one entry per ward) and is what block-break events consult
 * — O(1) per event, never a database read.</p>
 *
 * <p>Wards never protect anything (master prompt §5.2): the service surface
 * has no protection call of any kind, and {@link #removeByAnchor} exists
 * precisely because anchors are ordinary breakable blocks.</p>
 */
public final class WardService {

    private final RebellionDatabase db;
    private final WardsConfig config;
    private final WardSpatialIndex index;
    private final DetectionState detectionState;
    private final ConcurrentHashMap<Ward.AnchorKey, UUID> anchors = new ConcurrentHashMap<>();
    @Nullable
    private final TeamAffiliationService teams;
    private final LongSupplier clock;

    public WardService(RebellionDatabase db, WardsConfig config, WardSpatialIndex index,
            DetectionState detectionState, @Nullable TeamAffiliationService teams,
            LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.index = index;
        this.detectionState = detectionState;
        this.teams = teams;
        this.clock = clock;
    }

    public WardSpatialIndex index() {
        return index;
    }

    public WardsConfig config() {
        return config;
    }

    /**
     * Loads every persisted ward into the mirror at enable. Wards are few
     * (global cap), so one full read is the honest, simple strategy.
     */
    public CompletableFuture<Void> loadAll() {
        return db.queryList(WardStore.SELECT_ALL, WardStore.WARD).thenAccept(rows -> {
            for (WardStore.WardRow row : rows) {
                Ward ward = row.toDomain();
                index.insert(ward);
                anchors.put(ward.anchor(), ward.id());
            }
        });
    }

    /**
     * Creates a ward bound to the anchor block at ({@code bx,by,bz}) — the
     * command layer supplies block coordinates and the service stores the
     * block-center position. Caps are checked inside the insert transaction;
     * name and anchor races are settled by the UNIQUE backstops.
     */
    public CompletableFuture<WardResult> create(UUID owner, Optional<UUID> teamId, String name,
            String world, int bx, int by, int bz, double radius, WardType type) {
        if (!WardName.valid(name)) {
            return CompletableFuture.completedFuture(
                    WardResult.failure(WardOutcome.NAME_INVALID, name));
        }
        if (radius < config.minRadius() || radius > config.maxRadius()) {
            return CompletableFuture.completedFuture(WardResult.failure(
                    WardOutcome.RADIUS_INVALID,
                    config.minRadius() + ".." + config.maxRadius()));
        }
        if (type == WardType.DECOY && !config.decoysEnabled()) {
            return CompletableFuture.completedFuture(
                    WardResult.failure(WardOutcome.DECOYS_DISABLED, null));
        }
        double x = bx + 0.5;
        double y = by + 0.5;
        double z = bz + 0.5;
        UUID wardId = UUID.randomUUID();
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            if (WardStore.countByOwner(tx, owner) >= config.maxWardsPerPlayer()) {
                return WardResult.failure(WardOutcome.PLAYER_CAP,
                        String.valueOf(config.maxWardsPerPlayer()));
            }
            if (teamId.isPresent()
                    && WardStore.countByTeam(tx, teamId.get()) >= config.maxWardsPerTeam()) {
                return WardResult.failure(WardOutcome.TEAM_CAP,
                        String.valueOf(config.maxWardsPerTeam()));
            }
            if (WardStore.countAll(tx) >= config.maxWardsTotal()) {
                return WardResult.failure(WardOutcome.GLOBAL_CAP,
                        String.valueOf(config.maxWardsTotal()));
            }
            Ward ward = new Ward(wardId, name, owner, teamId, world, x, y, z, radius, type,
                    true, now);
            try {
                WardStore.insert(tx, ward);
            } catch (java.sql.SQLException e) {
                return mapUnique(e);
            }
            return WardResult.ok(ward);
        }).thenApply(result -> {
            if (result.success() && result.ward() != null) {
                index.insert(result.ward());
                anchors.put(result.ward().anchor(), result.ward().id());
            }
            return result;
        });
    }

    /** Removes a ward by id with management rights (owner or configured team rank). */
    public CompletableFuture<WardResult> remove(UUID actor, UUID wardId) {
        return db.transaction(tx -> {
            Optional<WardStore.WardRow> row = WardStore.byId(tx, wardId);
            if (row.isEmpty()) {
                return WardResult.failure(WardOutcome.NOT_FOUND, null);
            }
            Ward ward = row.get().toDomain();
            if (!canManage(actor, ward)) {
                return WardResult.failure(WardOutcome.NO_RIGHTS, ward.name());
            }
            WardStore.deleteById(tx, wardId);
            return WardResult.ok(ward);
        }).thenApply(result -> {
            if (result.success() && result.ward() != null) {
                forget(result.ward());
            }
            return result;
        });
    }

    /**
     * Anchor-broken path (block break, explosions). Authority is the block
     * event itself — anchors are ordinary blocks and no protection applies.
     * Idempotent: a second break of the same anchor finds nothing.
     */
    public CompletableFuture<WardResult> removeByAnchor(Ward.AnchorKey anchor) {
        UUID wardId = anchors.get(anchor);
        if (wardId == null) {
            return CompletableFuture.completedFuture(
                    WardResult.failure(WardOutcome.NOT_FOUND, null));
        }
        return db.transaction(tx -> {
            Optional<WardStore.WardRow> row = WardStore.byId(tx, wardId);
            if (row.isEmpty()) {
                return WardResult.failure(WardOutcome.NOT_FOUND, null);
            }
            WardStore.deleteById(tx, wardId);
            return WardResult.ok(row.get().toDomain());
        }).thenApply(result -> {
            if (result.success() && result.ward() != null) {
                forget(result.ward());
            } else {
                // Mirror drifted (row already gone): forget anyway so the
                // anchor map cannot wedge on a stale entry.
                anchors.remove(anchor);
            }
            return result;
        });
    }

    /** Toggles a ward's active flag; both directions reset detection state. */
    public CompletableFuture<WardResult> toggle(UUID actor, UUID wardId) {
        return db.transaction(tx -> {
            Optional<WardStore.WardRow> row = WardStore.byId(tx, wardId);
            if (row.isEmpty()) {
                return WardResult.failure(WardOutcome.NOT_FOUND, null);
            }
            Ward ward = row.get().toDomain();
            if (!canManage(actor, ward)) {
                return WardResult.failure(WardOutcome.NO_RIGHTS, ward.name());
            }
            boolean next = !ward.active();
            WardStore.setActive(tx, wardId, next);
            return WardResult.ok(ward.withActive(next));
        }).thenApply(result -> {
            if (result.success() && result.ward() != null) {
                index.replace(result.ward());
                detectionState.clearWard(result.ward().id());
            }
            return result;
        });
    }

    /** Admin removal: no ownership/rank check (command layer holds the node). */
    public CompletableFuture<WardResult> adminRemove(UUID wardId) {
        return db.transaction(tx -> {
            Optional<WardStore.WardRow> row = WardStore.byId(tx, wardId);
            if (row.isEmpty()) {
                return WardResult.failure(WardOutcome.NOT_FOUND, null);
            }
            WardStore.deleteById(tx, wardId);
            return WardResult.ok(row.get().toDomain());
        }).thenApply(result -> {
            if (result.success() && result.ward() != null) {
                forget(result.ward());
            }
            return result;
        });
    }

    /** Wards the actor owns or that belong to their team (cold path). */
    public List<Ward> ownedBy(UUID player) {
        Optional<UUID> teamId = teams == null ? Optional.empty() : teams.teamOf(player);
        return index.ownedBy(player, teamId);
    }

    /** Owner-scoped name lookup for commands (cold path). */
    public Optional<Ward> byName(UUID player, String name) {
        String norm = WardName.normalize(name);
        return ownedBy(player).stream()
                .filter(w -> WardName.normalize(w.name()).equals(norm))
                .findFirst();
    }

    public Optional<Ward> byId(UUID wardId) {
        return index.byId(wardId);
    }

    public List<Ward> all() {
        return index.all();
    }

    /** Ward id bound to an anchor, from the live mirror (O(1); block events only). */
    public Optional<UUID> wardIdAtAnchor(Ward.AnchorKey anchor) {
        return Optional.ofNullable(anchors.get(anchor));
    }

    /** Live detection state accessor for /ward info (reported insiders only). */
    public int reportedInsiders(UUID wardId) {
        return detectionState.insidersOf(wardId);
    }

    /** Detection state reset hook used by the plugin on world change. */
    public void clearPlayerDetection(UUID player) {
        detectionState.clearPlayer(player);
    }

    /**
     * Management rights: the owner always manages; otherwise the actor must
     * be a cached teammate of the ward's team holding a configured manage
     * rank. Without the teams module (or a cold cache) only the owner can.
     */
    public boolean canManage(UUID actor, Ward ward) {
        if (actor.equals(ward.owner())) {
            return true;
        }
        if (teams == null || ward.teamId().isEmpty()) {
            return false;
        }
        if (!ward.teamId().equals(teams.teamOf(actor))) {
            return false;
        }
        return teams.rankOf(actor)
                .map(rank -> config.manageRanks().contains(rank))
                .orElse(false);
    }

    private void forget(Ward ward) {
        index.remove(ward.id());
        anchors.remove(ward.anchor(), ward.id());
        detectionState.clearWard(ward.id());
    }

    private WardResult mapUnique(java.sql.SQLException e) {
        if (WardStore.isUniqueViolation(e)) {
            String constraint = WardStore.uniqueConstraint(e);
            if ("owner_name_unique".equals(constraint)) {
                return WardResult.failure(WardOutcome.NAME_TAKEN, null);
            }
            if ("anchor_unique".equals(constraint)) {
                return WardResult.failure(WardOutcome.ANCHOR_TAKEN, null);
            }
        }
        throw new RebellionException(FailureCode.PERSISTENCE_FAILURE,
                "ward operation failed: " + e.getMessage(), null, e);
    }

    /** Converts a failed future into a typed result (saturation = fail-closed). */
    public static WardResult failureToResult(Throwable t) {
        if (t instanceof RebellionException re) {
            if (re.code() == FailureCode.PERSISTENCE_OVERLOADED) {
                return WardResult.failure(WardOutcome.DB_ERROR, "persistence queue saturated");
            }
            return WardResult.failure(WardOutcome.DB_ERROR, "persistence failure");
        }
        return WardResult.failure(WardOutcome.DB_ERROR,
                t.getMessage() == null ? "unknown failure" : t.getMessage());
    }
}
