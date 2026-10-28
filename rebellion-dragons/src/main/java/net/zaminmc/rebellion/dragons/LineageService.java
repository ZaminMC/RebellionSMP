package net.zaminmc.rebellion.dragons;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;
import net.zaminmc.rebellion.dragons.db.LineageStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lineage authority (ADR-0009): transactional persistence plus the live
 * in-memory mirror. The database is the durable authority; the mirror is a
 * loaded view updated only after a normal commit — the same strategy the
 * teams (ADR-0006) and wards (ADR-0007) modules proved.
 *
 * <h2>Rare-asset guarantees (master prompt §5.6)</h2>
 * <ul>
 *   <li><strong>Cap:</strong> rows are minted only by {@link #issueFromGateway},
 *       which count-checks inside the transaction against the effective cap
 *       (config clamped to {@link LineagePolicy#HARD_CEILING}). Transfers and
 *       hatch cycles are pure UPDATEs, so they cannot exceed the cap.</li>
 *   <li><strong>No mint on transfer:</strong> death processing compare-and-sets
 *       the HATCHED row back to an egg with a <em>fresh</em> egg instance —
 *       nothing is inserted, so simultaneous owner death + dragon death and
 *       duplicate event delivery resolve to one egg ({@code EGG_RETURN_NOOP}).</li>
 *   <li><strong>Egg idempotency:</strong> hatching compare-and-sets on the live
 *       egg instance; cloned or stale items lose the race
 *       ({@code HATCH_CONFLICT}) and no second entity is ever bound.</li>
 *   <li><strong>Gateway idempotency:</strong> issuance is keyed by the observed
 *       vanilla gateway anchor; a UNIQUE backstop makes duplicate victory
 *       events resolve to {@code ISSUED_ALREADY} with the existing lineage.</li>
 *   <li><strong>Audited admin retirement:</strong> {@link #retire} is terminal,
 *       refuses double-retire, keeps the slot and gateway consumed, and appends
 *       an audit row in the same transaction.</li>
 * </ul>
 *
 * <p>Controller, listeners, commands and the physical egg-item protection are
 * gated on the ADR-0008 live-server proofs; this service deliberately exposes
 * no entity/world operations.</p>
 */
public final class LineageService {

    /** Issuance retry bound for slot-ordinal races (UNIQUE backstop + retry). */
    private static final int MAX_ISSUANCE_ATTEMPTS = 3;

    private final RebellionDatabase db;
    private final DragonsConfig config;
    private final LongSupplier clock;

    private final ConcurrentHashMap<UUID, Lineage> lineages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> lineageByEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> lineageByEggInstance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Lineage.GatewayKey, UUID> lineageByGateway = new ConcurrentHashMap<>();

    public LineageService(@NotNull RebellionDatabase db, @NotNull DragonsConfig config,
            @NotNull LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    /** Transaction carrier: result + pre-mutation row (for mirror swaps). */
    private record Outcome(LineageResult result, @Nullable Lineage before) {
    }

    /** Issuance attempt result: terminal outcome or a race-retry marker. */
    private record Attempt(LineageResult result, boolean retryRace) {
        static Attempt terminal(LineageResult result) {
            return new Attempt(result, false);
        }

        static Attempt retry() {
            return new Attempt(null, true);
        }
    }

    // ------------------------------------------------------------------
    // Load / mirror
    // ------------------------------------------------------------------

    /**
     * Loads every persisted lineage into the mirror at enable. Lineages are
     * few (hard cap 20), so one full read is the honest, simple strategy.
     */
    public CompletableFuture<Void> loadAll() {
        return db.queryList(LineageStore.SELECT_ALL, LineageStore.LINEAGE)
                .thenAccept(rows -> {
                    for (LineageStore.LineageRow row : rows) {
                        remember(row.toDomain());
                    }
                });
    }

    private void remember(@NotNull Lineage lineage) {
        swap(null, lineage);
    }

    /** Post-commit mirror swap; {@code before} may be null for fresh rows. */
    private void swap(@Nullable Lineage before, @NotNull Lineage after) {
        lineages.put(after.lineageId(), after);
        lineageByGateway.put(after.gateway(), after.lineageId());
        if (before != null) {
            if (before.entityUuid() != null) {
                lineageByEntity.remove(before.entityUuid(), before.lineageId());
            }
            if (!before.eggInstance().equals(after.eggInstance())) {
                lineageByEggInstance.remove(before.eggInstance(), before.lineageId());
            }
        }
        if (after.entityUuid() != null) {
            lineageByEntity.put(after.entityUuid(), after.lineageId());
        }
        lineageByEggInstance.put(after.eggInstance(), after.lineageId());
    }

    // ------------------------------------------------------------------
    // Issuance (the only mint path)
    // ------------------------------------------------------------------

    /**
     * Mints the lineage egg for a legit boss victory observed at a vanilla
     * End gateway anchor, routed to the victor. Idempotent per gateway:
     * duplicate victory events resolve to {@code ISSUED_ALREADY} with the
     * existing lineage and never mint twice. Refuses with
     * {@code CAP_REACHED} at the effective cap (vanilla may keep its own
     * reward policy; the registry mints nothing).
     *
     * <p>Slot-ordinal races settle through the UNIQUE backstop plus a bounded
     * fresh-transaction retry; gateway races resolve immediately through the
     * same backstop.</p>
     */
    public CompletableFuture<LineageResult> issueFromGateway(@NotNull String world,
            int gatewayX, int gatewayY, int gatewayZ, @NotNull UUID victor) {
        if (victor == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "victor required"));
        }
        String w = world == null ? "" : world.trim();
        if (w.isEmpty() || w.length() > 64) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "gateway world must be 1..64 characters"));
        }
        return issueAttempt(w, gatewayX, gatewayY, gatewayZ, victor, 0);
    }

    private CompletableFuture<LineageResult> issueAttempt(String world, int gatewayX,
            int gatewayY, int gatewayZ, UUID victor, int tries) {
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> existing =
                    LineageStore.byGateway(tx, world, gatewayX, gatewayY, gatewayZ);
            if (existing.isPresent()) {
                return Attempt.terminal(LineageResult.unchanged(LineageOutcome.ISSUED_ALREADY,
                        null, existing.get().toDomain()));
            }
            int cap = config.effectiveMaxLineages();
            if (LineageStore.countAll(tx) >= cap) {
                return Attempt.terminal(LineageResult.failure(
                        LineageOutcome.CAP_REACHED, "lineage cap " + cap));
            }
            int slot = LineageStore.maxSlot(tx) + 1;
            if (slot > cap) {
                // Defense in depth: ordinals are contiguous so this cannot
                // fire before the count check, but a corrupted table must
                // fail closed, not mint past the cap.
                return Attempt.terminal(LineageResult.failure(
                        LineageOutcome.CAP_REACHED, "lineage cap " + cap));
            }
            UUID lineageId = UUID.randomUUID();
            UUID eggInstance = UUID.randomUUID();
            long now = clock.getAsLong();
            try {
                LineageStore.insert(tx, lineageId, slot, victor, eggInstance, world,
                        gatewayX, gatewayY, gatewayZ, now);
            } catch (SQLException e) {
                if (LineageStore.isUniqueViolation(e)) {
                    String constraint = LineageStore.uniqueConstraint(e);
                    if ("gateway_unique".equals(constraint)) {
                        Optional<LineageStore.LineageRow> raced = LineageStore.byGateway(
                                tx, world, gatewayX, gatewayY, gatewayZ);
                        if (raced.isPresent()) {
                            return Attempt.terminal(LineageResult.unchanged(
                                    LineageOutcome.ISSUED_ALREADY, null, raced.get().toDomain()));
                        }
                        // The winner committed outside this snapshot; a fresh
                        // transaction will observe it.
                        return Attempt.retry();
                    }
                    if ("slot_unique".equals(constraint)) {
                        return Attempt.retry();
                    }
                }
                throw e;
            }
            LineageStore.audit(tx, lineageId, "ISSUE", victor,
                    "gateway " + world + "(" + gatewayX + "," + gatewayY + "," + gatewayZ
                            + ") slot " + slot, now);
            Lineage minted = new Lineage(lineageId, slot, LineageState.EGG_HELD, victor,
                    eggInstance, null, world, gatewayX, gatewayY, gatewayZ, null,
                    0L, null, null, null, null, 0L, now, now);
            return Attempt.terminal(LineageResult.changed(LineageOutcome.ISSUED, minted));
        }).thenCompose(attempt -> {
            if (attempt.retryRace()) {
                if (tries + 1 >= MAX_ISSUANCE_ATTEMPTS) {
                    return CompletableFuture.completedFuture(LineageResult.failure(
                            LineageOutcome.DB_ERROR,
                            "issuance race did not settle after "
                                    + MAX_ISSUANCE_ATTEMPTS + " attempts"));
                }
                return issueAttempt(world, gatewayX, gatewayY, gatewayZ, victor, tries + 1);
            }
            if (attempt.result().mutated() && attempt.result().lineage() != null) {
                remember(attempt.result().lineage());
            }
            return CompletableFuture.completedFuture(attempt.result());
        });
    }

    // ------------------------------------------------------------------
    // Hatch (CAS on the live egg instance)
    // ------------------------------------------------------------------

    /**
     * Hatches the egg {@code eggInstance} of {@code lineageId}, binding the
     * fresh living entity and recording the hatch-time health snapshot.
     * Only a HELD egg whose live instance stamp matches can win the
     * compare-and-set; recovery eggs must exit through claim or admin route
     * (both re-issue the instance), so pre-park clones always lose.
     */
    public CompletableFuture<LineageResult> hatch(@NotNull UUID lineageId,
            @NotNull UUID eggInstance, @NotNull UUID owner, @NotNull UUID entityUuid,
            double health) {
        if (lineageId == null || eggInstance == null || owner == null || entityUuid == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "lineage, egg instance, owner and entity are required"));
        }
        if (!Double.isFinite(health) || health <= 0.0
                || health > LineagePolicy.MAX_HATCH_HEALTH) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "health must be finite in 0 < health <= "
                            + LineagePolicy.MAX_HATCH_HEALTH));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (r.state() == LineageState.HATCHED) {
                return new Outcome(LineageResult.failure(LineageOutcome.HATCH_CONFLICT,
                        "lineage already hatched"), before);
            }
            if (r.state() == LineageState.RETIRED) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage retired"), before);
            }
            if (r.state() == LineageState.EGG_RECOVERY) {
                // Recovery exits only through claim/route; both re-issue the
                // instance, so a pre-park clone can never hatch from here.
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage in recovery - claim or route it first"), before);
            }
            if (!r.eggInstance().equals(eggInstance)) {
                return new Outcome(LineageResult.failure(LineageOutcome.HATCH_CONFLICT,
                        "stale egg instance"), before);
            }
            int affected;
            try {
                affected = LineageStore.hatch(tx, owner, entityUuid, health, now,
                        lineageId, eggInstance);
            } catch (SQLException e) {
                if (LineageStore.isUniqueViolation(e)
                        && "entity_unique".equals(LineageStore.uniqueConstraint(e))) {
                    return new Outcome(LineageResult.failure(LineageOutcome.HATCH_CONFLICT,
                            "entity already bound to a lineage"), before);
                }
                throw e;
            }
            if (affected != 1) {
                // Lost a concurrent hatch: classify from the committed row.
                return new Outcome(classifyPostHatch(tx, lineageId), before);
            }
            LineageStore.audit(tx, lineageId, "HATCH", owner,
                    "entity " + entityUuid + " health " + health, now);
            Lineage updated = before.with(LineageState.HATCHED, owner, eggInstance,
                    entityUuid, health, now);
            return new Outcome(LineageResult.changed(LineageOutcome.HATCHED, updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    private LineageResult classifyPostHatch(TransactionContext tx,
            UUID lineageId) throws SQLException {
        return LineageStore.byId(tx, lineageId)
                .map(after -> after.state() == LineageState.HATCHED
                        ? LineageResult.failure(LineageOutcome.HATCH_CONFLICT, "lineage already hatched")
                        : LineageResult.failure(LineageOutcome.HATCH_CONFLICT, "egg instance superseded"))
                .orElseGet(() -> LineageResult.failure(LineageOutcome.NOT_FOUND, null));
    }

    // ------------------------------------------------------------------
    // Death processing (CAS on the bound entity; idempotent)
    // ------------------------------------------------------------------

    /**
     * Returns a living lineage to its egg. A valid attributed killer
     * receives the egg ({@code EGG_HELD}); without one the lineage parks in
     * the protected recovery mailbox with the last owner as claimant
     * ({@code EGG_RECOVERY}). Idempotent by construction: the
     * compare-and-set pins the bound entity, so simultaneous owner-death +
     * dragon-death handling and duplicate event delivery all resolve to one
     * egg and typed no-ops ({@code EGG_RETURN_NOOP}).
     */
    public CompletableFuture<LineageResult> processDeath(@NotNull UUID lineageId,
            @NotNull UUID entityUuid, @NotNull Optional<UUID> killer) {
        if (lineageId == null || entityUuid == null || killer == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "lineage, entity and killer (possibly empty) are required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (r.state() != LineageState.HATCHED) {
                // Already processed (egg state) or retired: idempotent no-op.
                return new Outcome(LineageResult.unchanged(LineageOutcome.EGG_RETURN_NOOP,
                        r.state() == LineageState.RETIRED ? "lineage retired"
                                : "lineage already returned to egg", before), before);
            }
            if (!entityUuid.equals(r.entityUuid())) {
                // Stale event for a superseded entity binding.
                return new Outcome(LineageResult.unchanged(LineageOutcome.EGG_RETURN_NOOP,
                        "stale entity binding", before), before);
            }
            LineageState newState = killer.isPresent()
                    ? LineageState.EGG_HELD : LineageState.EGG_RECOVERY;
            UUID newOwner = killer.isPresent() ? killer.get() : r.owner();
            UUID freshEggInstance = UUID.randomUUID();
            int affected = LineageStore.death(tx, newState, newOwner, freshEggInstance,
                    now, lineageId, entityUuid);
            if (affected != 1) {
                // Concurrent death processing won: idempotent no-op.
                return LineageStore.byId(tx, lineageId)
                        .map(fresh -> new Outcome(LineageResult.unchanged(
                                LineageOutcome.EGG_RETURN_NOOP, "death already processed",
                                fresh.toDomain()), fresh.toDomain()))
                        .orElseGet(() -> new Outcome(LineageResult.failure(
                                LineageOutcome.NOT_FOUND, null), null));
            }
            LineageStore.audit(tx, lineageId, "DEATH_RETURN", killer.orElse(null),
                    killer.isPresent() ? "killer " + killer.get()
                            : "no valid killer - recovery", now);
            Lineage updated = before.with(newState, newOwner, freshEggInstance, null,
                    null, now);
            return new Outcome(LineageResult.changed(LineageOutcome.EGG_RETURNED,
                    killer.isPresent() ? "egg re-issued to killer"
                            : "egg parked in recovery", updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    // ------------------------------------------------------------------
    // Recovery parking / claim / admin route
    // ------------------------------------------------------------------

    /**
     * Parks a held egg in the protected recovery mailbox (undeliverable
     * inventory, offline victor, destroyed copy). Idempotent: parking an
     * already-parked lineage is a {@code RECOVERY_ENTERED} no-op, so
     * delivery-retry loops cannot wedge.
     */
    public CompletableFuture<LineageResult> markEggUndeliverable(@NotNull UUID lineageId,
            @Nullable String reason) {
        if (lineageId == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "lineage required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (r.state() == LineageState.EGG_RECOVERY) {
                return new Outcome(LineageResult.unchanged(LineageOutcome.RECOVERY_ENTERED,
                        "already parked", before), before);
            }
            if (r.state() != LineageState.EGG_HELD) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "not a held egg"), before);
            }
            int affected = LineageStore.markUndeliverable(tx, lineageId, now);
            if (affected != 1) {
                return LineageStore.byId(tx, lineageId)
                        .map(fresh -> fresh.state() == LineageState.EGG_RECOVERY
                                ? new Outcome(LineageResult.unchanged(
                                        LineageOutcome.RECOVERY_ENTERED, "already parked",
                                        fresh.toDomain()), fresh.toDomain())
                                : new Outcome(LineageResult.failure(
                                        LineageOutcome.INVALID_STATE, "not a held egg"),
                                        fresh.toDomain()))
                        .orElseGet(() -> new Outcome(LineageResult.failure(
                                LineageOutcome.NOT_FOUND, null), null));
            }
            LineageStore.audit(tx, lineageId, "PARK_RECOVERY", null,
                    reason == null ? "undeliverable" : reason, now);
            Lineage updated = before.with(LineageState.EGG_RECOVERY, r.owner(),
                    r.eggInstance(), null, null, now);
            return new Outcome(LineageResult.changed(LineageOutcome.RECOVERY_ENTERED, updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    /**
     * Claim flow: the recorded claimant re-issues a recovery egg to
     * themselves. Only the recorded claimant can win; the fresh egg
     * instance invalidates every pre-park copy. Deliberately does not
     * reveal who the claimant is.
     */
    public CompletableFuture<LineageResult> claimRecoveredEgg(@NotNull UUID lineageId,
            @NotNull UUID claimant) {
        if (lineageId == null || claimant == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "lineage and claimant required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (r.state() != LineageState.EGG_RECOVERY) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage not in recovery"), before);
            }
            UUID freshEggInstance = UUID.randomUUID();
            int affected = LineageStore.claim(tx, lineageId, claimant, freshEggInstance, now);
            if (affected != 1) {
                // Wrong claimant, or a concurrent claim/route won.
                return LineageStore.byId(tx, lineageId)
                        .map(fresh -> fresh.state() == LineageState.EGG_HELD
                                && claimant.equals(fresh.owner())
                                ? new Outcome(LineageResult.unchanged(LineageOutcome.CLAIMED,
                                        "already claimed by you", fresh.toDomain()),
                                        fresh.toDomain())
                                : new Outcome(LineageResult.failure(
                                        LineageOutcome.INVALID_STATE,
                                        "lineage not claimable by you"), fresh.toDomain()))
                        .orElseGet(() -> new Outcome(LineageResult.failure(
                                LineageOutcome.NOT_FOUND, null), null));
            }
            LineageStore.audit(tx, lineageId, "CLAIM", claimant, null, now);
            Lineage updated = before.with(LineageState.EGG_HELD, claimant, freshEggInstance,
                    null, null, now);
            return new Outcome(LineageResult.changed(LineageOutcome.CLAIMED, updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    /**
     * Admin recovery: re-routes any egg (held or recovery) to a chosen
     * holder with a fresh egg instance. Audited with the acting admin.
     */
    public CompletableFuture<LineageResult> adminRouteEgg(@NotNull UUID lineageId,
            @NotNull UUID admin, @NotNull UUID newHolder) {
        if (lineageId == null || admin == null || newHolder == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "lineage, admin and new holder required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (!r.state().isEgg()) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage is not an egg"), before);
            }
            UUID freshEggInstance = UUID.randomUUID();
            int affected = LineageStore.route(tx, lineageId, newHolder, freshEggInstance, now);
            if (affected != 1) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage is not an egg"), before);
            }
            LineageStore.audit(tx, lineageId, "ADMIN_ROUTE", admin,
                    "to " + newHolder, now);
            Lineage updated = before.with(LineageState.EGG_HELD, newHolder, freshEggInstance,
                    null, null, now);
            return new Outcome(LineageResult.changed(LineageOutcome.ROUTED, updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    // ------------------------------------------------------------------
    // Retirement (audited, terminal)
    // ------------------------------------------------------------------

    /**
     * Retires a lineage: terminal, audited with the acting admin and the
     * stated reason. The slot and the gateway anchor stay consumed forever.
     * The command layer is responsible for the required operator
     * confirmation before calling this.
     */
    public CompletableFuture<LineageResult> retire(@NotNull UUID lineageId,
            @NotNull UUID admin, @Nullable String reason) {
        if (lineageId == null || admin == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "lineage and admin required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            if (r.state() == LineageState.RETIRED) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage already retired"), before);
            }
            int affected = LineageStore.retire(tx, lineageId, now);
            if (affected != 1) {
                return new Outcome(LineageResult.failure(LineageOutcome.INVALID_STATE,
                        "lineage already retired"), before);
            }
            LineageStore.audit(tx, lineageId, "RETIRE", admin,
                    (reason == null ? "" : reason + " ") + "(was " + r.state() + ")", now);
            // The egg instance is kept for forensics; state gates all use.
            Lineage updated = before.with(LineageState.RETIRED, null, r.eggInstance(),
                    null, null, now);
            return new Outcome(LineageResult.changed(LineageOutcome.RETIRED, updated), before);
        }).thenApply(outcome -> publish(outcome));
    }

    // ------------------------------------------------------------------
    // Pearl healing (CAS on the bound entity + cooldown; idempotent)
    // ------------------------------------------------------------------

    /**
     * Records one pearl heal on the living pet: health moves to
     * {@code newHealth} and the cooldown stamp advances to {@code nowUtc}.
     * The compare-and-set pins the bound entity <em>and</em> requires the
     * previous heal stamp to be at least {@code cooldownMs} old, so a
     * retried interact event, a double-click or a duplicate delivery can
     * never heal (or consume a pearl) twice — the second attempt affects
     * zero rows and resolves to the typed {@code PEARL_COOLDOWN} refusal.
     *
     * <p>The caller consumes the physical pearl and applies
     * {@code LivingEntity.heal(amount)} only after this reports
     * {@link LineageOutcome#PEARL_HEALED}, so a refused heal never costs a
     * pearl (master prompt §5.6: atomic pearl consumption + health update).</p>
     *
     * @param newHealth post-heal health (finite, 0 < h <= {@link
     *                  LineagePolicy#MAX_HATCH_HEALTH}; the caller clamps to
     *                  the entity's real maximum before calling)
     * @param cooldownMs configured pearl-heal cooldown in milliseconds
     */
    public CompletableFuture<LineageResult> pearlHeal(@NotNull UUID lineageId,
            @NotNull UUID entityUuid, double newHealth, long cooldownMs) {
        if (lineageId == null || entityUuid == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "lineage and entity required"));
        }
        if (!Double.isFinite(newHealth) || newHealth <= 0.0
                || newHealth > LineagePolicy.MAX_HATCH_HEALTH) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "health must be finite in 0 < health <= "
                            + LineagePolicy.MAX_HATCH_HEALTH));
        }
        if (cooldownMs < 0) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "cooldown must not be negative"));
        }
        long now = clock.getAsLong();
        long cooldownFloor = now - cooldownMs;
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            int affected = LineageStore.pearlHeal(tx, lineageId, entityUuid, newHealth,
                    now, cooldownFloor, now);
            if (affected != 1) {
                return new Outcome(classifyPearlRefusal(r, entityUuid, cooldownFloor), before);
            }
            LineageStore.audit(tx, lineageId, "PEARL_HEAL", r.owner(),
                    "health " + newHealth, now);
            return new Outcome(LineageResult.changed(LineageOutcome.PEARL_HEALED,
                    before.withPearlHeal(newHealth, now, now)), before);
        }).thenApply(outcome -> publish(outcome));
    }

    private LineageResult classifyPearlRefusal(LineageStore.LineageRow r,
            UUID entityUuid, long cooldownFloor) {
        if (r.state() != LineageState.HATCHED) {
            return LineageResult.failure(LineageOutcome.INVALID_STATE, "lineage not hatched");
        }
        if (!entityUuid.equals(r.entityUuid())) {
            return LineageResult.failure(LineageOutcome.HATCH_CONFLICT, "stale entity binding");
        }
        return LineageResult.failure(LineageOutcome.PEARL_COOLDOWN,
                "pearl heal cooldown active");
    }

    // ------------------------------------------------------------------
    // Controller persistence snapshots (CAS on the bound entity)
    // ------------------------------------------------------------------

    /**
     * Periodic controller persist for a living pet: last observed health
     * and position plus the persist stamp. The compare-and-set pins the
     * bound entity, so snapshots of a superseded or already-dead binding
     * affect zero rows and are silently dropped (typed
     * {@code SNAPSHOT_STALE} — the sweep treats it as a benign race, never
     * an error). Health may be null when the entity was already gone by
     * the time the snapshot was built.
     */
    public CompletableFuture<LineageResult> persistSnapshot(@NotNull UUID lineageId,
            @NotNull UUID entityUuid, @Nullable Double health,
            @NotNull Lineage.PersistedLocation location) {
        if (lineageId == null || entityUuid == null || location == null) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "lineage, entity and location required"));
        }
        if (health != null && (!Double.isFinite(health) || health < 0.0
                || health > LineagePolicy.MAX_HATCH_HEALTH)) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "health must be finite in 0 <= health <= "
                            + LineagePolicy.MAX_HATCH_HEALTH));
        }
        String world = location.world().trim();
        if (world.isEmpty() || world.length() > 64) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID,
                    "world must be 1..64 characters"));
        }
        if (!Double.isFinite(location.x()) || !Double.isFinite(location.y())
                || !Double.isFinite(location.z())) {
            return CompletableFuture.completedFuture(LineageResult.failure(
                    LineageOutcome.ARGUMENT_INVALID, "position must be finite"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<LineageStore.LineageRow> row = LineageStore.byId(tx, lineageId);
            if (row.isEmpty()) {
                return new Outcome(LineageResult.failure(LineageOutcome.NOT_FOUND, null), null);
            }
            LineageStore.LineageRow r = row.get();
            Lineage before = r.toDomain();
            int affected = LineageStore.snapshot(tx, lineageId, entityUuid, health,
                    world, location.x(), location.y(), location.z(), now, now);
            if (affected != 1) {
                return new Outcome(LineageResult.unchanged(LineageOutcome.SNAPSHOT_STALE,
                        "binding superseded", before), before);
            }
            // A snapshot is a real row mutation (health + loc + persist
            // stamp), so it swaps the mirror like any other write.
            return new Outcome(LineageResult.changed(LineageOutcome.SNAPSHOT_SAVED,
                    before.withSnapshot(health, location, now, now)), before);
        }).thenApply(outcome -> publish(outcome));
    }

    // ------------------------------------------------------------------
    // Reads (mirror + cold audit)
    // ------------------------------------------------------------------

    /** @return the lineage by id, from the live mirror. */
    public Optional<Lineage> byId(@NotNull UUID lineageId) {
        return Optional.ofNullable(lineages.get(lineageId));
    }

    /**
     * @return the lineage owning this live egg instance (mirror; hot path
     * for item handlers). Resolves only while the lineage actually IS an
     * egg: hatched, retired and superseded instances stop resolving, so a
     * stale physical copy can never be mistaken for a hatching egg.
     */
    public Optional<Lineage> byEggInstance(@NotNull UUID eggInstance) {
        UUID lineageId = lineageByEggInstance.get(eggInstance);
        if (lineageId == null) {
            return Optional.empty();
        }
        return byId(lineageId)
                .filter(l -> eggInstance.equals(l.eggInstance()) && l.state().isEgg());
    }

    /** @return the lineage bound to this living entity (mirror; hot path for death events). */
    public Optional<Lineage> byEntity(@NotNull UUID entityUuid) {
        UUID lineageId = lineageByEntity.get(entityUuid);
        if (lineageId == null) {
            return Optional.empty();
        }
        return byId(lineageId).filter(l -> entityUuid.equals(l.entityUuid()));
    }

    /** @return the lineage minted by this gateway anchor (mirror). */
    public Optional<Lineage> byGateway(@NotNull String world, int x, int y, int z) {
        UUID lineageId = lineageByGateway.get(Lineage.GatewayKey.of(world, x, y, z));
        return lineageId == null ? Optional.empty() : byId(lineageId);
    }

    /** @return every lineage, ordered by slot (mirror snapshot). */
    public List<Lineage> all() {
        return lineages.values().stream()
                .sorted(Comparator.comparingInt(Lineage::slot))
                .toList();
    }

    /** @return the number of lineage rows (mirror; equals the durable row count). */
    public int count() {
        return lineages.size();
    }

    /** @return the effective mint cap after policy clamping. */
    public int cap() {
        return config.effectiveMaxLineages();
    }

    /** @return the audit trail of one lineage (cold path; admin forensics). */
    public CompletableFuture<List<LineageStore.AuditRow>> auditTrail(@NotNull UUID lineageId) {
        return db.queryList(LineageStore.SELECT_AUDIT_BY_LINEAGE, LineageStore.AUDIT, lineageId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private LineageResult publish(Outcome outcome) {
        if (outcome.result().mutated() && outcome.result().lineage() != null) {
            swap(outcome.before(), outcome.result().lineage());
        }
        return outcome.result();
    }

    /** Converts a failed future into a typed result (saturation = fail-closed). */
    public static LineageResult failureToResult(Throwable t) {
        if (t instanceof RebellionException re) {
            if (re.code() == FailureCode.PERSISTENCE_OVERLOADED) {
                return LineageResult.failure(LineageOutcome.DB_ERROR,
                        "persistence queue saturated");
            }
            return LineageResult.failure(LineageOutcome.DB_ERROR, "persistence failure");
        }
        return LineageResult.failure(LineageOutcome.DB_ERROR,
                t.getMessage() == null ? "unknown failure" : t.getMessage());
    }
}
