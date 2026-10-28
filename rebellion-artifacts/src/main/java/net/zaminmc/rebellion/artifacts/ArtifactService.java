package net.zaminmc.rebellion.artifacts;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.artifacts.db.ArtifactStore;
import net.zaminmc.rebellion.artifacts.db.ArtifactStore.ArtifactRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Artifact authority (ADR-0013): transactional persistence plus the live
 * in-memory mirror. The database is the durable authority; the mirror is a
 * loaded view updated only after a normal commit — the same strategy the
 * teams (ADR-0006), wards (ADR-0007) and dragons (ADR-0009) modules proved.
 *
 * <h2>Rare-asset guarantees (master prompt §5.5)</h2>
 * <ul>
 *   <li><strong>Exactly three:</strong> rows are created only by the
 *       idempotent {@link #loadAll()} seed (INSERT-if-absent per dimension
 *       key); there is no gameplay mint path anywhere, and a table that does
 *       not hold exactly {@link ArtifactsPolicy#ARTIFACT_COUNT} rows fails
 *       the load loudly.</li>
 *   <li><strong>No duplicate valid copies:</strong> every possession change
 *       compare-and-sets on the presented live instance and supersedes it,
 *       so cloned/stale physical copies resolve to {@code INSTANCE_STALE}
 *       and can never move a row.</li>
 *   <li><strong>Rebellion once per occurrence:</strong> the singleton guard
 *       CAS makes duplicate inventory events, retried listeners and racing
 *       observers collapse into one durable event row; a copied artifact
 *       cannot fake qualification because a copy never moved a row to
 *       HELD. After completion a new full possession mints a NEW event id —
 *       no permanent winner.</li>
 *   <li><strong>Crash recoverable:</strong> every transition commits
 *       atomically with its audit row(s); a crash leaves the pre- or
 *       post-transition state, both valid re-entry points, and re-delivered
 *       events classify through the CAS as stale or typed no-ops.</li>
 * </ul>
 */
public final class ArtifactService {

    private final RebellionDatabase db;
    private final ArtifactsConfig config;
    private final LongSupplier clock;

    private final Map<ArtifactKey, Artifact> artifacts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ArtifactKey> byInstance =
            new ConcurrentHashMap<>();

    /** At most one active occurrence, structurally (guard singleton). */
    private volatile Optional<RebellionEvent> activeEvent = Optional.empty();

    public ArtifactService(@NotNull RebellionDatabase db, @NotNull ArtifactsConfig config,
            @NotNull LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    /** Transaction carrier: result + pre-mutation row (for mirror swaps). */
    private record Outcome(ArtifactResult result, @Nullable Artifact before) {
    }

    /** Spend carrier: all-or-none across three rows; mirrors swap per row. */
    private record SpendOutcome(ArtifactResult result, List<Artifact> befores,
            List<Artifact> afters) {
    }

    /** Loaded snapshot from the seed transaction. */
    private record Loaded(List<ArtifactRow> rows, List<RebellionEvent> activeEvents) {
    }

    /** Internal rollback signal: a spend CAS lost a race mid-set. */
    private static final class SpendConflict extends RuntimeException {
        final String detail;

        SpendConflict(String detail) {
            super(detail, null, false, false);
            this.detail = detail;
        }
    }

    // ------------------------------------------------------------------
    // Load / seed / mirror
    // ------------------------------------------------------------------

    /**
     * Seeds the three dimension rows (idempotent INSERT-if-absent), ensures
     * the singleton guard row exists, re-syncs the config-owned origin
     * anchors, and loads the mirror. Fails the future loudly when the table
     * does not hold exactly three rows afterwards or when more than one
     * active rebellion event exists — both are tamper signatures, not states
     * the service can produce.
     */
    public CompletableFuture<Void> loadAll() {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            for (ArtifactKey key : ArtifactKey.values()) {
                var anchor = config.origin(key);
                try {
                    ArtifactStore.seedInsert(tx, key, UUID.randomUUID(),
                            UUID.randomUUID(), anchor.world(), anchor.x(),
                            anchor.y(), anchor.z(), now);
                } catch (SQLException e) {
                    if (!ArtifactStore.isUniqueViolation(e)) {
                        throw e;
                    }
                    // Row exists (re-seed): the PK backstop makes this a no-op.
                }
                // Config-owned anchor sync — touches no gameplay columns.
                ArtifactStore.syncOrigin(tx, key, anchor.world(), anchor.x(),
                        anchor.y(), anchor.z());
            }
            try {
                ArtifactStore.guardInsert(tx);
            } catch (SQLException e) {
                if (!ArtifactStore.isUniqueViolation(e)) {
                    throw e;
                }
                // Guard row exists (restart).
            }
            return new Loaded(ArtifactStore.all(tx), ArtifactStore.activeEvents(tx));
        }).thenApply(loaded -> {
            if (loaded.rows().size() != ArtifactsPolicy.ARTIFACT_COUNT) {
                throw new IllegalStateException("artifact registry must hold exactly "
                        + ArtifactsPolicy.ARTIFACT_COUNT + " rows (got "
                        + loaded.rows().size() + ") - refusing to serve a tampered table");
            }
            if (loaded.activeEvents().size() > 1) {
                throw new IllegalStateException("multiple active rebellion events ("
                        + loaded.activeEvents().size() + ") - data corruption");
            }
            for (ArtifactRow row : loaded.rows()) {
                swap(null, row.toDomain());
            }
            activeEvent = loaded.activeEvents().isEmpty()
                    ? Optional.empty() : Optional.of(loaded.activeEvents().get(0));
            return null;
        });
    }

    private void swap(@Nullable Artifact before, @NotNull Artifact after) {
        artifacts.put(after.artifactKey(), after);
        if (before != null && before.state().isPresentable()) {
            byInstance.remove(before.instance(), before.artifactKey());
        }
        if (after.state().isPresentable()) {
            byInstance.put(after.instance(), after.artifactKey());
        }
    }

    // ------------------------------------------------------------------
    // Readers (mirror-backed, no SQL)
    // ------------------------------------------------------------------

    /** @return how many artifact rows the mirror holds (structurally 3). */
    public int count() {
        return artifacts.size();
    }

    /** @return one artifact by its dimension key. */
    public @NotNull Optional<Artifact> byKey(@NotNull ArtifactKey key) {
        return Optional.ofNullable(artifacts.get(key));
    }

    /**
     * Resolves the artifact a physical item's instance stamp belongs to —
     * only while that stamp is the row's LIVE stamp in a presentable state
     * (ORIGIN/HELD/LOST). Superseded copies in RETURNING/SPENT are inert by
     * design (ADR-0013 §2).
     */
    public @NotNull Optional<Artifact> byInstance(@NotNull UUID instance) {
        ArtifactKey key = byInstance.get(instance);
        return key == null ? Optional.empty() : byKey(key);
    }

    /** @return all three artifacts in dimension order. */
    public @NotNull List<Artifact> all() {
        return artifacts.values().stream()
                .sorted(Comparator.comparing(Artifact::artifactKey))
                .toList();
    }

    /** @return the currently active rebellion occurrence, when one exists. */
    public @NotNull Optional<RebellionEvent> activeEvent() {
        return activeEvent;
    }

    /** @return the audit trail of one artifact (admin forensics). */
    public CompletableFuture<List<ArtifactStore.AuditRow>> auditTrail(
            @NotNull ArtifactKey key) {
        if (key == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.transaction(tx -> ArtifactStore.auditTrail(tx, key));
    }

    /** @return every rebellion event occurrence, oldest first. */
    public CompletableFuture<List<RebellionEvent>> eventHistory() {
        return db.transaction(ArtifactStore::eventsAll);
    }

    // ------------------------------------------------------------------
    // Possession (CAS on the live instance)
    // ------------------------------------------------------------------

    /**
     * Captures the artifact from its protected origin realm (the discoverer
     * conquered the encounter and presents the live origin instance). Only
     * an ORIGIN row whose live stamp matches can win; cloned origin copies
     * and re-captures resolve to typed refusals.
     */
    public CompletableFuture<ArtifactResult> capture(@NotNull ArtifactKey key,
            @NotNull UUID presentedInstance, @NotNull UUID discoverer) {
        if (key == null || presentedInstance == null || discoverer == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "artifact key, presented instance and discoverer are required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (before.state() != ArtifactState.ORIGIN) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state() + ", not at its origin"), before);
            }
            if (!before.instance().equals(presentedInstance)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                        "cloned or stale origin copy"), before);
            }
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.capture(tx, key, discoverer, presentedInstance, fresh,
                    now) != 1) {
                return new Outcome(classifyCaptureLoss(tx, key), before);
            }
            ArtifactStore.audit(tx, key, "CAPTURE", discoverer,
                    "captured from the protected origin", now);
            Artifact after = before.with(ArtifactState.HELD, discoverer, fresh,
                    now, now, now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.CAPTURED, after),
                    before);
        }).thenApply(this::publish);
    }

    private ArtifactResult classifyCaptureLoss(TransactionContext tx, ArtifactKey key)
            throws SQLException {
        return ArtifactStore.byKey(tx, key)
                .map(r -> r.state() == ArtifactState.ORIGIN
                        ? ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                                "origin instance superseded before the capture")
                        : ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                "artifact is " + r.state() + ", not at its origin"))
                .orElseGet(() -> ArtifactResult.failure(ArtifactOutcome.NOT_FOUND, null));
    }

    /**
     * Moves possession between two players (a valid gameplay hand-change:
     * trade, gift). The recorded holder must present the live instance; the
     * stamp is superseded so the old physical copy becomes inert.
     */
    public CompletableFuture<ArtifactResult> transfer(@NotNull ArtifactKey key,
            @NotNull UUID fromHolder, @NotNull UUID toHolder,
            @NotNull UUID presentedInstance) {
        if (key == null || fromHolder == null || toHolder == null
                || presentedInstance == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "artifact key, both holders and presented instance are required"));
        }
        if (fromHolder.equals(toHolder)) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "from and to holder must differ"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (before.state() != ArtifactState.HELD) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state() + ", not held"), before);
            }
            if (!before.holder().equals(fromHolder)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "presented holder is not the recorded holder"), before);
            }
            if (!before.instance().equals(presentedInstance)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                        "cloned or stale copy"), before);
            }
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.transfer(tx, key, fromHolder, toHolder, presentedInstance,
                    fresh, now) != 1) {
                return new Outcome(classifyHeldLoss(tx, key, fromHolder,
                        presentedInstance), before);
            }
            ArtifactStore.audit(tx, key, "TRANSFER", fromHolder,
                    "transferred to " + toHolder, now);
            Artifact after = before.with(ArtifactState.HELD, toHolder, fresh,
                    now, before.stateSinceUtc(), now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.TRANSFERRED, after),
                    before);
        }).thenApply(this::publish);
    }

    private ArtifactResult classifyHeldLoss(TransactionContext tx, ArtifactKey key,
            UUID expectedHolder, UUID presentedInstance) throws SQLException {
        return ArtifactStore.byKey(tx, key)
                .map(r -> {
                    if (r.state() != ArtifactState.HELD) {
                        return ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                "artifact is " + r.state() + ", not held");
                    }
                    return r.instance().equals(presentedInstance)
                            ? ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                    "recorded holder changed before the transfer")
                            : ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                                    "instance superseded before the transfer");
                })
                .orElseGet(() -> ArtifactResult.failure(ArtifactOutcome.NOT_FOUND, null));
    }

    /**
     * Parks a held artifact in the recoverable LOST state (dropped in the
     * void, undeliverable, despawned). The last rightful holder stays on the
     * row for forensics and the admin route. Idempotent: re-parking an
     * already-lost artifact is a {@code LOST_ALREADY} no-op, so item-layer
     * retry loops cannot wedge.
     */
    public CompletableFuture<ArtifactResult> markLost(@NotNull ArtifactKey key,
            @Nullable String reason) {
        if (key == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "artifact key is required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (ArtifactStore.markLost(tx, key, now) != 1) {
                return ArtifactStore.byKey(tx, key)
                        .map(fresh -> new Outcome(fresh.state() == ArtifactState.LOST
                                ? ArtifactResult.unchanged(ArtifactOutcome.LOST_ALREADY,
                                        "already parked in recovery", fresh.toDomain())
                                : ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                        "artifact is " + fresh.state()
                                                + ", loss recording refused"),
                                before))
                        .orElseGet(() -> new Outcome(ArtifactResult.failure(
                                ArtifactOutcome.NOT_FOUND, null), null));
            }
            ArtifactStore.audit(tx, key, "LOST", before.holder(), reason, now);
            Artifact after = before.with(ArtifactState.LOST, before.holder(),
                    before.instance(), before.holderSinceUtc(), now, now);
            return new Outcome(
                    ArtifactResult.changed(ArtifactOutcome.LOST_RECORDED, after), before);
        }).thenApply(this::publish);
    }

    /**
     * A finder presents the lost artifact's live instance and becomes its
     * holder (this is also how a stolen drop changes hands — possession of
     * the physical item, §5.5). A clone carries the same stamp as the live
     * item, but only one presentation can win the CAS: the winner's commit
     * supersedes the stamp, so the clone and every later presentation
     * resolve to {@code INSTANCE_STALE} — never two valid copies.
     */
    public CompletableFuture<ArtifactResult> found(@NotNull ArtifactKey key,
            @NotNull UUID presentedInstance, @NotNull UUID finder) {
        if (key == null || presentedInstance == null || finder == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "artifact key, presented instance and finder are required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (before.state() != ArtifactState.LOST) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state() + ", not lost"), before);
            }
            if (!before.instance().equals(presentedInstance)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                        "cloned or stale copy"), before);
            }
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.found(tx, key, finder, presentedInstance, fresh, now) != 1) {
                return ArtifactStore.byKey(tx, key)
                        .map(r -> r.state() == ArtifactState.LOST
                                ? ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                                        "instance superseded before the find")
                                : ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                        "artifact is " + r.state() + ", not lost"))
                        .map(result -> new Outcome(result, before))
                        .orElseGet(() -> new Outcome(ArtifactResult.failure(
                                ArtifactOutcome.NOT_FOUND, null), null));
            }
            ArtifactStore.audit(tx, key, "FOUND", finder,
                    "found by " + finder, now);
            Artifact after = before.with(ArtifactState.HELD, finder, fresh,
                    now, now, now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.FOUND, after),
                    before);
        }).thenApply(this::publish);
    }

    /**
     * The recorded holder voluntarily surrenders the artifact: it enters
     * RETURNING and the return pipeline routes it home. The physical copy
     * dies with the superseded stamp.
     */
    public CompletableFuture<ArtifactResult> returnToOrigin(@NotNull ArtifactKey key,
            @NotNull UUID holder, @NotNull UUID presentedInstance) {
        if (key == null || holder == null || presentedInstance == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "artifact key, holder and presented instance are required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (before.state() != ArtifactState.HELD) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state() + ", not held"), before);
            }
            if (!before.holder().equals(holder)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "presented holder is not the recorded holder"), before);
            }
            if (!before.instance().equals(presentedInstance)) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INSTANCE_STALE,
                        "cloned or stale copy"), before);
            }
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.voluntaryReturn(tx, key, holder, presentedInstance, fresh,
                    now) != 1) {
                return new Outcome(classifyHeldLoss(tx, key, holder, presentedInstance),
                        before);
            }
            ArtifactStore.audit(tx, key, "RETURN_SUBMIT", holder,
                    "voluntary return to the protected origin", now);
            Artifact after = before.with(ArtifactState.RETURNING, null, fresh,
                    0L, now, now);
            return new Outcome(
                    ArtifactResult.changed(ArtifactOutcome.RETURN_SUBMITTED, after),
                    before);
        }).thenApply(this::publish);
    }

    // ------------------------------------------------------------------
    // Return pipeline (sweep-driven; both steps idempotent)
    // ------------------------------------------------------------------

    /**
     * Return-sweep step 1: a LOST or SPENT artifact whose configured return
     * delay has elapsed enters RETURNING. Refused while a rebellion
     * occurrence is active (§5.5's "event check" — the conservative reading);
     * re-queueing an in-transit artifact is a benign {@code RETURN_QUEUED}
     * no-op so a crashed sweep cannot wedge the pipeline.
     */
    public CompletableFuture<ArtifactResult> beginReturn(@NotNull ArtifactKey key) {
        if (key == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "artifact key is required"));
        }
        long now = clock.getAsLong();
        long eligibilityFloor = now - config.returnDelayMs();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            if (before.state() == ArtifactState.RETURNING) {
                return new Outcome(ArtifactResult.unchanged(
                        ArtifactOutcome.RETURN_QUEUED, "already in transit", before),
                        before);
            }
            if (before.state() != ArtifactState.LOST && before.state() != ArtifactState.SPENT) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state() + ", not return-eligible"), before);
            }
            if (before.stateSinceUtc() > eligibilityFloor) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "return delay not elapsed (" + (config.returnDelayMs()
                                - (now - before.stateSinceUtc())) + " ms remaining)"),
                        before);
            }
            Optional<ArtifactStore.GuardRow> guard = ArtifactStore.guard(tx);
            if (guard.isPresent() && guard.get().activeEventId() != null) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.EVENT_ACTIVE,
                        "a rebellion event occurrence is active"), before);
            }
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.beginReturn(tx, key, fresh, eligibilityFloor, now) != 1) {
                return new Outcome(classifyBeginReturnLoss(tx, key), before);
            }
            ArtifactStore.audit(tx, key, "RETURN_BEGIN", null,
                    "return sweep from " + before.state() + " (delay "
                            + config.returnDelaySeconds() + "s elapsed)", now);
            Artifact after = before.with(ArtifactState.RETURNING, null, fresh,
                    0L, now, now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.RETURN_QUEUED, after),
                    before);
        }).thenApply(this::publish);
    }

    private ArtifactResult classifyBeginReturnLoss(TransactionContext tx, ArtifactKey key)
            throws SQLException {
        return ArtifactStore.byKey(tx, key)
                .map(r -> r.state() == ArtifactState.RETURNING
                        ? ArtifactResult.unchanged(ArtifactOutcome.RETURN_QUEUED,
                                "already in transit", r.toDomain())
                        : ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                                "artifact is " + r.state() + ", not return-eligible"))
                .orElseGet(() -> ArtifactResult.failure(ArtifactOutcome.NOT_FOUND, null));
    }

    /**
     * Return-sweep step 2: the in-transit artifact arrives at its protected
     * origin realm with a fresh live instance (the discoverable item the
     * encounter presents). Arriving twice is a benign {@code RETURN_NOOP} —
     * the artifact is home; the invariant held.
     */
    public CompletableFuture<ArtifactResult> completeReturn(@NotNull ArtifactKey key) {
        if (key == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "artifact key is required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.completeReturn(tx, key, fresh, now) != 1) {
                return ArtifactStore.byKey(tx, key)
                        .map(r -> r.state() == ArtifactState.ORIGIN
                                ? new Outcome(ArtifactResult.unchanged(
                                        ArtifactOutcome.RETURN_NOOP,
                                        "artifact is already at its origin",
                                        r.toDomain()), before)
                                : new Outcome(ArtifactResult.failure(
                                        ArtifactOutcome.INVALID_STATE,
                                        "artifact is " + r.state()
                                                + ", not in transit"), before))
                        .orElseGet(() -> new Outcome(ArtifactResult.failure(
                                ArtifactOutcome.NOT_FOUND, null), null));
            }
            ArtifactStore.audit(tx, key, "RETURN_COMPLETE", null,
                    "reappeared at the protected origin", now);
            Artifact after = before.with(ArtifactState.ORIGIN, null, fresh,
                    before.holderSinceUtc(), now, now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.RETURNED, after),
                    before);
        }).thenApply(this::publish);
    }

    // ------------------------------------------------------------------
    // Admin recovery
    // ------------------------------------------------------------------

    /**
     * Admin routes a stuck artifact (LOST or RETURNING) to a player with a
     * fresh instance — the audited recovery path. An ORIGIN artifact is
     * refused: the protected realm must be conquered through its encounter,
     * never bypassed by an admin grant. An HELD artifact is not the admin's
     * to move (use the gameplay transfer path).
     */
    public CompletableFuture<ArtifactResult> adminRoute(@NotNull ArtifactKey key,
            @NotNull UUID target, @Nullable UUID admin) {
        if (key == null || target == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "artifact key and target player are required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            Artifact before = row.get().toDomain();
            UUID fresh = UUID.randomUUID();
            if (ArtifactStore.route(tx, key, target, fresh, now) != 1) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "artifact is " + before.state()
                                + " - admin route only recovers LOST or RETURNING rows"),
                        before);
            }
            ArtifactStore.audit(tx, key, "ADMIN_ROUTE", admin,
                    "admin routed to " + target, now);
            Artifact after = before.with(ArtifactState.HELD, target, fresh,
                    now, now, now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.ROUTED, after),
                    before);
        }).thenApply(this::publish);
    }

    // ------------------------------------------------------------------
    // Rebellion event (idempotent per occurrence)
    // ------------------------------------------------------------------

    /**
     * Fires the Rebellion event occurrence for {@code holder}: requires all
     * three artifacts HELD by that one player (default scope, §5.5), mints a
     * durable event id, and is idempotent per occurrence — the singleton
     * guard CAS collapses duplicate inventory events, retried listeners and
     * racing observers into the one active occurrence. A copied artifact
     * cannot fake qualification: a copy never moved a row to HELD.
     */
    public CompletableFuture<ArtifactResult> triggerRebellion(@NotNull UUID holder,
            @NotNull String reason) {
        if (!config.rebellionEventEnabled()) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.FEATURE_DISABLED,
                    "artifacts.rebellion-event.enabled is false"));
        }
        if (holder == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "holder is required"));
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "an auditable initiation reason of 1..255 characters is required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            List<String> notControlled = new ArrayList<>();
            for (ArtifactKey key : ArtifactKey.values()) {
                Optional<ArtifactRow> row = ArtifactStore.byKey(tx, key);
                if (row.isEmpty() || row.get().state() != ArtifactState.HELD
                        || !row.get().holder().equals(holder)) {
                    notControlled.add(row.isEmpty()
                            ? key.name() + " (missing)"
                            : key.name() + " (" + row.get().state() + ")");
                }
            }
            if (!notControlled.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_QUALIFIED,
                        "holder does not control all three: "
                                + String.join(", ", notControlled)), null);
            }
            Optional<ArtifactStore.GuardRow> guard = ArtifactStore.guard(tx);
            if (guard.isPresent() && guard.get().activeEventId() != null) {
                return new Outcome(existingActive(tx, guard.get().activeEventId()), null);
            }
            UUID eventId = UUID.randomUUID();
            if (ArtifactStore.guardAcquire(tx, eventId) != 1) {
                // A racing trigger won the guard; resolve to its occurrence.
                Optional<ArtifactStore.GuardRow> settled = ArtifactStore.guard(tx);
                return new Outcome(existingActive(tx,
                        settled.map(ArtifactStore.GuardRow::activeEventId).orElse(null)),
                        null);
            }
            ArtifactStore.insertEvent(tx, eventId, holder, trimmed, now);
            for (ArtifactKey key : ArtifactKey.values()) {
                ArtifactStore.audit(tx, key, "REBELLION_TRIGGER", holder,
                        "event " + eventId + ": " + trimmed, now);
            }
            RebellionEvent event = new RebellionEvent(eventId, holder, trimmed,
                    RebellionEvent.State.ACTIVE, now, null);
            return new Outcome(
                    ArtifactResult.changed(ArtifactOutcome.EVENT_TRIGGERED,
                            "event " + eventId + " triggered by full control of "
                                    + "all three artifacts", event),
                    null);
        }).thenApply(outcome -> {
            if (outcome.result().mutated() && outcome.result().event() != null) {
                activeEvent = Optional.of(outcome.result().event());
            }
            return outcome.result();
        });
    }

    private ArtifactResult existingActive(TransactionContext tx, @Nullable UUID eventId)
            throws SQLException {
        if (eventId == null) {
            return ArtifactResult.failure(ArtifactOutcome.DB_ERROR,
                    "guard settled without an active event id");
        }
        return ArtifactStore.eventById(tx, eventId)
                .map(event -> ArtifactResult.unchanged(
                        ArtifactOutcome.EVENT_ALREADY_ACTIVE,
                        "rebellion event " + eventId + " is already active", event, true))
                .orElseGet(() -> ArtifactResult.failure(ArtifactOutcome.DB_ERROR,
                        "guard references unknown event " + eventId));
    }

    /**
     * The overpowering action's persistence half: the trigger holder — and
     * no one else — spends all three artifacts in ONE transaction
     * (all-or-none). The old physical copies die with the superseded
     * instances; the SPENT rows ride the return sweep back to their origins
     * (the lineage is not permanently invalidated, §5.5). A retried spend
     * resolves to {@code SPENT_CONFLICT} with a "already spent" detail — the
     * idempotent outcome for restarts mid-action.
     */
    public CompletableFuture<ArtifactResult> spendArtifacts(@NotNull UUID actor,
            @NotNull String reason) {
        if (actor == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "actor is required"));
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID,
                    "an auditable spend reason of 1..255 characters is required"));
        }
        long now = clock.getAsLong();
        return db.<SpendOutcome>transaction(tx -> {
            Optional<ArtifactStore.GuardRow> guard = ArtifactStore.guard(tx);
            if (guard.isEmpty() || guard.get().activeEventId() == null) {
                throw new IllegalStateException("no active rebellion event to spend from");
            }
            UUID eventId = guard.get().activeEventId();
            RebellionEvent event = ArtifactStore.eventById(tx, eventId)
                    .orElseThrow(() -> new IllegalStateException(
                            "guard references unknown event " + eventId));
            if (!event.triggerHolder().equals(actor)) {
                throw new SpendRefusal(ArtifactOutcome.INVALID_STATE,
                        "only the trigger holder may spend the artifacts");
            }
            List<Artifact> befores = new ArrayList<>();
            List<Artifact> afters = new ArrayList<>();
            Map<ArtifactKey, UUID> freshInstances = new EnumMap<>(ArtifactKey.class);
            List<String> notHeld = new ArrayList<>();
            for (ArtifactKey key : ArtifactKey.values()) {
                ArtifactRow row = ArtifactStore.byKey(tx, key).orElseThrow(
                        () -> new IllegalStateException("registry row missing: " + key));
                Artifact before = row.toDomain();
                befores.add(before);
                if (before.state() != ArtifactState.HELD
                        || !before.holder().equals(event.triggerHolder())) {
                    notHeld.add(key.name() + " (" + before.state()
                            + (before.state() == ArtifactState.SPENT ? ", already spent"
                                    : "") + ")");
                }
            }
            if (!notHeld.isEmpty()) {
                throw new SpendRefusal(ArtifactOutcome.SPENT_CONFLICT,
                        "not held by the trigger holder: " + String.join(", ", notHeld));
            }
            for (int i = 0; i < befores.size(); i++) {
                Artifact before = befores.get(i);
                UUID fresh = UUID.randomUUID();
                freshInstances.put(before.artifactKey(), fresh);
                if (ArtifactStore.spend(tx, before.artifactKey(), event.triggerHolder(),
                        fresh, now) != 1) {
                    // All-or-none: the exception rolls back the earlier CASes.
                    throw new SpendConflict("concurrent change on "
                            + before.artifactKey() + " - nothing spent");
                }
                afters.add(before.with(ArtifactState.SPENT, null, fresh, 0L, now, now));
            }
            for (ArtifactKey key : ArtifactKey.values()) {
                ArtifactStore.audit(tx, key, "SPEND", actor,
                        "event " + eventId + ": " + trimmed, now);
            }
            return new SpendOutcome(
                    ArtifactResult.changed(ArtifactOutcome.SPENT,
                            "event " + eventId + ": all three artifacts spent"),
                    befores, afters);
        }).handle((outcome, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException c && c.getCause() != null
                        ? c.getCause() : error;
                if (cause instanceof SpendRefusal refusal) {
                    return ArtifactResult.failure(refusal.outcome, refusal.detail);
                }
                if (cause instanceof SpendConflict conflict) {
                    return ArtifactResult.failure(ArtifactOutcome.SPENT_CONFLICT,
                            conflict.detail);
                }
                if (cause instanceof IllegalStateException) {
                    return ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                            cause.getMessage());
                }
                throw new CompletionException(cause);
            }
            for (int i = 0; i < outcome.befores().size(); i++) {
                swap(outcome.befores().get(i), outcome.afters().get(i));
            }
            return outcome.result();
        });
    }

    /** Internal typed-refusal signal (translated to a result, never a crash). */
    private static final class SpendRefusal extends RuntimeException {
        final ArtifactOutcome outcome;
        final String detail;

        SpendRefusal(ArtifactOutcome outcome, String detail) {
            super(detail, null, false, false);
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /**
     * Closes the rebellion occurrence: the event row completes and the guard
     * releases, so the artifacts (once home and reconquered) can mint a NEW
     * occurrence — the SMP has no permanent winner. Idempotent: completing a
     * finished occurrence is an {@code EVENT_COMPLETE_NOOP} no-op.
     */
    public CompletableFuture<ArtifactResult> completeRebellion(@NotNull UUID eventId,
            @Nullable UUID actor) {
        if (eventId == null) {
            return CompletableFuture.completedFuture(ArtifactResult.failure(
                    ArtifactOutcome.ARGUMENT_INVALID, "event id is required"));
        }
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            Optional<RebellionEvent> row = ArtifactStore.eventById(tx, eventId);
            if (row.isEmpty()) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.NOT_FOUND,
                        null), null);
            }
            RebellionEvent event = row.get();
            if (event.state() == RebellionEvent.State.COMPLETED) {
                return new Outcome(ArtifactResult.unchanged(
                        ArtifactOutcome.EVENT_COMPLETE_NOOP, "already completed",
                        event, true), null);
            }
            Optional<ArtifactStore.GuardRow> guard = ArtifactStore.guard(tx);
            if (guard.isEmpty() || !eventId.equals(guard.get().activeEventId())) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "the guard does not reference this event"), null);
            }
            if (ArtifactStore.guardRelease(tx, eventId) != 1) {
                return new Outcome(ArtifactResult.failure(ArtifactOutcome.INVALID_STATE,
                        "guard release raced - completion refused"), null);
            }
            if (ArtifactStore.completeEvent(tx, eventId, now) != 1) {
                return new Outcome(ArtifactResult.unchanged(
                        ArtifactOutcome.EVENT_COMPLETE_NOOP,
                        "completed by a concurrent call", event, true), null);
            }
            for (ArtifactKey key : ArtifactKey.values()) {
                ArtifactStore.audit(tx, key, "REBELLION_COMPLETE", actor,
                        "event " + eventId + " completed", now);
            }
            RebellionEvent completed = new RebellionEvent(event.eventId(),
                    event.triggerHolder(), event.reason(),
                    RebellionEvent.State.COMPLETED, event.initiatedAtUtc(), now);
            return new Outcome(ArtifactResult.changed(ArtifactOutcome.EVENT_COMPLETED,
                    "event " + eventId + " completed; the guard is clear", completed),
                    null);
        }).thenApply(outcome -> {
            if (outcome.result().outcome() == ArtifactOutcome.EVENT_COMPLETED) {
                activeEvent = Optional.empty();
            }
            return outcome.result();
        });
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private ArtifactResult publish(Outcome outcome) {
        if (outcome.result().mutated() && outcome.result().artifact() != null) {
            swap(outcome.before(), outcome.result().artifact());
        }
        return outcome.result();
    }
}
