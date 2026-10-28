package net.zaminmc.rebellion.artifacts.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactResult;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactState;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ArtifactService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0013 evidence set. Every rare-asset invariant
 * from master prompt §5.5 is exercised for real: the exactly-three seed and
 * its idempotency, config-owned anchor sync, instance-stamped possession
 * (capture/transfer/find/voluntary return), cloned-copy refusals, idempotent
 * loss recording, the return-delay pipeline with its event check, the
 * Rebellion event's copy-proof qualification and once-per-occurrence
 * idempotency, all-or-none spending, guard release + reconquest, admin
 * routing of stuck states, audit-trail discipline, and the full restart
 * round-trip. No mocks.
 */
final class ArtifactServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private ArtifactService service;

    /** Mutable service clock: delay tests advance it deterministically. */
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private final UUID playerA = UUID.randomUUID();
    private final UUID playerB = UUID.randomUUID();
    private final UUID finder = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private ArtifactsConfig config() {
        return ArtifactsConfig.defaults();
    }

    /** Defaults with a custom return delay (seconds). */
    private static ArtifactsConfig config(long returnDelaySeconds) {
        return ArtifactsConfig.defaults().withReturnDelay(returnDelaySeconds);
    }

    /** Defaults with the rebellion-event toggle flipped. */
    private static ArtifactsConfig config(boolean eventEnabled) {
        return ArtifactsConfig.defaults().withEventEnabled(eventEnabled);
    }

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        service = new ArtifactService(db, config(), clock::get);
        await(service.loadAll());
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    private DatabaseService newService() throws Exception {
        DatabaseService fresh = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(fresh.bootstrap());
        await(fresh.applyModuleMigrations("artifacts",
                ArtifactsSchema.migrations()));
        return fresh;
    }

    private DiagnosticsService diagnostics() {
        CoreConfig core = new CoreConfig(1, false, 30, "diagnostics", 5, 50);
        return new DiagnosticsService(core, tmp,
                java.util.logging.Logger.getLogger("test"));
    }

    private DatabaseConfig sqliteConfig() {
        return new DatabaseConfig(
                DatabaseConfig.Backend.SQLITE, "rebellion.sqlite",
                "localhost", 3306, "rebellion", "user", "",
                Map.of(), 10, 2, 5_000, 1_800_000, 2, 2000, true);
    }

    private static void await(CompletableFuture<?> future) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed: " + e, e);
        }
    }

    private <T> T run(CompletableFuture<T> future) {
        await(future);
        return future.join();
    }

    private ArtifactResult runResult(CompletableFuture<ArtifactResult> future) {
        return run(future);
    }

    /** Captures one artifact for {@code who}; asserts and returns the HELD row. */
    private Artifact captureOk(ArtifactKey key, UUID who) {
        Artifact origin = service.byKey(key).orElseThrow();
        ArtifactResult result = runResult(
                service.capture(key, origin.instance(), who));
        assertTrue(result.success(), "capture of " + key + " failed: " + result.detail());
        assertEquals(ArtifactOutcome.CAPTURED, result.outcome());
        return result.artifact();
    }

    /** Parks one HELD artifact in LOST; asserts and returns the LOST row. */
    private Artifact lostOk(ArtifactKey key, String reason) {
        ArtifactResult result = runResult(service.markLost(key, reason));
        assertTrue(result.success(), "markLost failed: " + result.detail());
        assertEquals(ArtifactOutcome.LOST_RECORDED, result.outcome());
        return result.artifact();
    }

    /** Restores one LOST artifact to {@code who}; returns the HELD row. */
    private Artifact foundOk(ArtifactKey key, UUID who) {
        Artifact lost = service.byKey(key).orElseThrow();
        ArtifactResult result = runResult(service.found(key, lost.instance(), who));
        assertTrue(result.success(), "found failed: " + result.detail());
        assertEquals(ArtifactOutcome.FOUND, result.outcome());
        return result.artifact();
    }

    /** Triggers the Rebellion occurrence for a fully-controlling holder. */
    private RebellionEvent triggerOk(UUID holder, String reason) {
        ArtifactResult result = runResult(service.triggerRebellion(holder, reason));
        assertTrue(result.success(), "trigger failed: " + result.detail());
        assertEquals(ArtifactOutcome.EVENT_TRIGGERED, result.outcome());
        return result.event();
    }

    /** Captures all three artifacts for one holder (rebellion qualification). */
    private void captureAllFor(UUID holder) {
        for (ArtifactKey key : ArtifactKey.values()) {
            captureOk(key, holder);
        }
    }

    // ------------------------------------------------------------------
    // Seed: exactly three, idempotent, config-synced
    // ------------------------------------------------------------------

    @Test
    void seedCreatesExactlyThreeDimensionRowsIdempotently() {
        assertEquals(3, service.count(), "exactly three artifacts, forever");
        assertEquals(ArtifactsConfig.defaults().origins().size(), service.all().size());

        for (ArtifactKey key : ArtifactKey.values()) {
            Artifact artifact = service.byKey(key).orElseThrow();
            assertEquals(ArtifactState.ORIGIN, artifact.state());
            assertTrue(artifact.holderOpt().isEmpty(),
                    "an origin artifact has no holder");
            assertEquals(0L, artifact.holderSinceUtc());
            assertTrue(service.byInstance(artifact.instance()).isPresent(),
                    "the origin item presents the live instance");
        }

        // Distinct identities and stamps across the three rows.
        var ids = service.all().stream().map(Artifact::artifactId).toList();
        var stamps = service.all().stream().map(Artifact::instance).toList();
        assertEquals(3, ids.stream().distinct().count());
        assertEquals(3, stamps.stream().distinct().count());

        // Re-boot over the same database: the seed is a no-op, ids are stable.
        var before = service.all();
        ArtifactService second = new ArtifactService(db, config(), clock::get);
        await(second.loadAll());
        assertEquals(3, second.count());
        for (int i = 0; i < 3; i++) {
            assertEquals(before.get(i).artifactId(), second.all().get(i).artifactId());
            assertEquals(before.get(i).instance(), second.all().get(i).instance(),
                    "re-seeding must not supersede the live origin instance");
        }

        // A fresh seed writes no audit rows: the trail starts at the first
        // real mutation, not at boot bookkeeping.
        for (ArtifactKey key : ArtifactKey.values()) {
            assertTrue(run(service.auditTrail(key)).isEmpty());
        }
    }

    @Test
    void seedResyncsConfigOwnedOriginAnchorsWithoutTouchingState() {
        Artifact held = captureOk(ArtifactKey.OVERWORLD, playerA);

        // Operator recalibrates the realms; only the anchors may follow.
        var calibrated = Map.of(
                ArtifactKey.OVERWORLD, new ArtifactsConfig.OriginAnchor(
                        "earth", 100, 70, -200),
                ArtifactKey.NETHER, new ArtifactsConfig.OriginAnchor(
                        "hell", 0, 40, 0),
                ArtifactKey.END, new ArtifactsConfig.OriginAnchor(
                        "void", 0, 60, 0));
        ArtifactsConfig recalibrated = new ArtifactsConfig(calibrated, 60L, true,
                ArtifactsConfig.defaults().itemMaterials(),
                ArtifactsConfig.defaults().sweepIntervalSeconds(),
                ArtifactsConfig.defaults().eventDurationMinutes(),
                ArtifactsConfig.defaults().eventReminderMinutes(),
                ArtifactsConfig.defaults().purge());
        ArtifactService second = new ArtifactService(db, recalibrated, clock::get);
        await(second.loadAll());

        Artifact synced = second.byKey(ArtifactKey.OVERWORLD).orElseThrow();
        assertEquals("earth", synced.originWorld());
        assertEquals(100, synced.originX());
        assertEquals(70, synced.originY());
        assertEquals(-200, synced.originZ());
        // Gameplay state is untouched by the config sync.
        assertEquals(ArtifactState.HELD, synced.state());
        assertEquals(held.holder(), synced.holder());
        assertEquals(held.instance(), synced.instance());
    }

    // ------------------------------------------------------------------
    // Possession: capture / transfer / find (CAS on the live instance)
    // ------------------------------------------------------------------

    @Test
    void argumentValidationRefusesBeforeAnyDatabaseAccess() {
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.capture(null, UUID.randomUUID(), playerA)).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.capture(ArtifactKey.END, null, playerA)).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.found(ArtifactKey.END, UUID.randomUUID(), null)).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.markLost(null, "void")).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.beginReturn(null)).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.completeReturn(null)).outcome());
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.adminRoute(ArtifactKey.END, null, admin)).outcome());

        Artifact held = captureOk(ArtifactKey.OVERWORLD, playerA);
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.transfer(ArtifactKey.OVERWORLD, playerA, playerA,
                        held.instance())).outcome(),
                "a self-transfer is refused, not a silent re-stamp");

        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.triggerRebellion(playerA, "   ")).outcome(),
                "an auditable initiation reason is required");
        assertEquals(ArtifactOutcome.ARGUMENT_INVALID, runResult(
                service.spendArtifacts(playerA, "")).outcome());
    }

    @Test
    void captureFromOriginMakesTheDiscovererTheHolder() {
        Artifact origin = service.byKey(ArtifactKey.OVERWORLD).orElseThrow();
        Artifact held = captureOk(ArtifactKey.OVERWORLD, playerA);

        assertEquals(ArtifactState.HELD, held.state());
        assertEquals(Optional.of(playerA), held.holderOpt());
        assertNotEquals(origin.instance(), held.instance(),
                "the origin stamp is superseded by the discoverer's fresh stamp");
        assertTrue(held.holderSinceUtc() > 0);

        // The old origin copy is inert; the new live stamp resolves.
        assertTrue(service.byInstance(origin.instance()).isEmpty());
        assertEquals(Optional.of(held), service.byInstance(held.instance()));

        var trail = run(service.auditTrail(ArtifactKey.OVERWORLD));
        assertEquals(1, trail.size());
        assertEquals("CAPTURE", trail.get(0).action());
    }

    @Test
    void captureRefusesClonedOriginCopies() {
        Artifact origin = service.byKey(ArtifactKey.END).orElseThrow();
        ArtifactResult cloned = runResult(
                service.capture(ArtifactKey.END, UUID.randomUUID(), playerA));
        assertFalse(cloned.success());
        assertEquals(ArtifactOutcome.INSTANCE_STALE, cloned.outcome());
        assertEquals(ArtifactState.ORIGIN,
                service.byKey(ArtifactKey.END).orElseThrow().state(),
                "a refused capture must leave the row untouched");
    }

    @Test
    void aCapturedArtifactCannotBeCapturedAgain() {
        captureOk(ArtifactKey.NETHER, playerA);
        Artifact held = service.byKey(ArtifactKey.NETHER).orElseThrow();

        // Even the live instance cannot re-enter: the artifact is not at its
        // origin anymore.
        ArtifactResult again = runResult(
                service.capture(ArtifactKey.NETHER, held.instance(), playerB));
        assertFalse(again.success());
        assertEquals(ArtifactOutcome.INVALID_STATE, again.outcome());
    }

    @Test
    void transferMovesPossessionAndSupersedesTheInstance() {
        Artifact held = captureOk(ArtifactKey.OVERWORLD, playerA);
        ArtifactResult moved = runResult(service.transfer(
                ArtifactKey.OVERWORLD, playerA, playerB, held.instance()));

        assertTrue(moved.success(), moved.detail());
        assertEquals(ArtifactOutcome.TRANSFERRED, moved.outcome());
        assertEquals(Optional.of(playerB), moved.artifact().holderOpt());
        assertNotEquals(held.instance(), moved.artifact().instance());
        assertTrue(service.byInstance(held.instance()).isEmpty(),
                "the old physical copy is inert after the transfer");

        // The superseded copy cannot transfer even from the CURRENT holder:
        // the stamp check fires (state first, then holder, then instance).
        Artifact live = moved.artifact();
        ArtifactResult staleCopy = runResult(service.transfer(
                ArtifactKey.OVERWORLD, playerB, finder, held.instance()));
        assertEquals(ArtifactOutcome.INSTANCE_STALE, staleCopy.outcome());

        // A third party cannot transfer what they do not hold.
        ArtifactResult stranger = runResult(service.transfer(
                ArtifactKey.OVERWORLD, playerA, finder, live.instance()));
        assertEquals(ArtifactOutcome.INVALID_STATE, stranger.outcome(),
                "the recorded holder is pinned in the CAS");
    }

    // ------------------------------------------------------------------
    // Loss: recoverable, idempotent
    // ------------------------------------------------------------------

    @Test
    void markLostParksRecoverablyAndIsIdempotent() {
        captureOk(ArtifactKey.END, playerA);
        Artifact lost = lostOk(ArtifactKey.END, "dropped into the void");

        assertEquals(ArtifactState.LOST, lost.state());
        assertEquals(Optional.of(playerA), lost.holderOpt(),
                "the last rightful holder stays recorded for forensics");
        assertEquals(lost.instance(), service.byKey(ArtifactKey.END)
                .orElseThrow().instance(), "loss does not supersede the stamp - "
                + "the physical item can still be presented by a finder");
        assertTrue(service.byInstance(lost.instance()).isPresent(),
                "a lost artifact's live instance still resolves");

        // Re-parking is a typed no-op, not an error and not a new audit row.
        ArtifactResult again = runResult(service.markLost(ArtifactKey.END, "retry"));
        assertTrue(again.success());
        assertEquals(ArtifactOutcome.LOST_ALREADY, again.outcome());
        assertFalse(again.mutated());
        assertEquals(2, run(service.auditTrail(ArtifactKey.END)).size(),
                "CAPTURE + LOST; the idempotent re-park added nothing");

        // A state that was never held cannot be lost.
        ArtifactResult origin = runResult(
                service.markLost(ArtifactKey.OVERWORLD, "not held"));
        assertEquals(ArtifactOutcome.INVALID_STATE, origin.outcome());
    }

    @Test
    void foundRestoresToTheFinderAndKillsClones() {
        captureOk(ArtifactKey.NETHER, playerA);
        Artifact lost = lostOk(ArtifactKey.NETHER, "dropped");

        // While LOST, a forged stamp is refused before anything moves.
        ArtifactResult forged = runResult(
                service.found(ArtifactKey.NETHER, UUID.randomUUID(), playerB));
        assertFalse(forged.success());
        assertEquals(ArtifactOutcome.INSTANCE_STALE, forged.outcome());

        Artifact restored = foundOk(ArtifactKey.NETHER, finder);
        assertEquals(Optional.of(finder), restored.holderOpt());
        assertNotEquals(lost.instance(), restored.instance(),
                "the find supersedes the stamp");
        assertTrue(service.byInstance(lost.instance()).isEmpty(),
                "the dropped original (and any clone of it) is now inert");

        // A clone of the pre-find item loses: the row is not LOST anymore,
        // so the second presentation is an honest INVALID_STATE refusal
        // (the first presentation already won and superseded the stamp).
        ArtifactResult clone = runResult(
                service.found(ArtifactKey.NETHER, lost.instance(), playerB));
        assertFalse(clone.success());
        assertEquals(ArtifactOutcome.INVALID_STATE, clone.outcome());

        // Finding something that is not lost is refused.
        ArtifactResult notLost = runResult(
                service.found(ArtifactKey.OVERWORLD,
                        service.byKey(ArtifactKey.OVERWORLD).orElseThrow().instance(),
                        finder));
        assertEquals(ArtifactOutcome.INVALID_STATE, notLost.outcome());
    }

    // ------------------------------------------------------------------
    // Return pipeline: voluntary, delayed, event-checked, idempotent steps
    // ------------------------------------------------------------------

    @Test
    void voluntaryReturnEntersTransitHolderOnly() {
        Artifact held = captureOk(ArtifactKey.END, playerA);

        ArtifactResult stranger = runResult(service.returnToOrigin(
                ArtifactKey.END, playerB, held.instance()));
        assertEquals(ArtifactOutcome.INVALID_STATE, stranger.outcome());

        ArtifactResult stale = runResult(service.returnToOrigin(
                ArtifactKey.END, playerA, UUID.randomUUID()));
        assertEquals(ArtifactOutcome.INSTANCE_STALE, stale.outcome());

        ArtifactResult surrendered = runResult(service.returnToOrigin(
                ArtifactKey.END, playerA, held.instance()));
        assertTrue(surrendered.success(), surrendered.detail());
        assertEquals(ArtifactOutcome.RETURN_SUBMITTED, surrendered.outcome());
        assertEquals(ArtifactState.RETURNING, surrendered.artifact().state());
        assertTrue(surrendered.artifact().holderOpt().isEmpty());
        assertTrue(service.byInstance(held.instance()).isEmpty(),
                "the surrendered copy is inert in transit");
    }

    @Test
    void beginReturnGatesOnDelayStateAndActiveEvent() {
        // HELD is not return-eligible at all.
        captureOk(ArtifactKey.OVERWORLD, playerA);
        assertEquals(ArtifactOutcome.INVALID_STATE,
                runResult(service.beginReturn(ArtifactKey.OVERWORLD)).outcome());

        // LOST, but the configured delay has not elapsed yet.
        captureOk(ArtifactKey.NETHER, playerA);
        lostOk(ArtifactKey.NETHER, "dropped");
        ArtifactResult early = runResult(service.beginReturn(ArtifactKey.NETHER));
        assertFalse(early.success());
        assertEquals(ArtifactOutcome.INVALID_STATE, early.outcome());
        assertTrue(early.detail().contains("delay"),
                "the refusal must explain the remaining delay: " + early.detail());

        // Delay elapsed: the sweep may queue the return.
        clock.addAndGet(3_600_000L + 1_000L);
        ArtifactResult queued = runResult(service.beginReturn(ArtifactKey.NETHER));
        assertTrue(queued.success(), queued.detail());
        assertEquals(ArtifactOutcome.RETURN_QUEUED, queued.outcome());
        assertEquals(ArtifactState.RETURNING, queued.artifact().state());

        // Re-queueing an in-transit artifact is a benign no-op (a crashed
        // sweep cannot wedge the pipeline).
        ArtifactResult requeued = runResult(service.beginReturn(ArtifactKey.NETHER));
        assertTrue(requeued.success());
        assertEquals(ArtifactOutcome.RETURN_QUEUED, requeued.outcome());
        assertFalse(requeued.mutated());
    }

    @Test
    void beginReturnRefusesWhileARebellionEventIsActive() {
        captureAllFor(playerA);
        triggerOk(playerA, "full control");
        lostOk(ArtifactKey.END, "lost mid-catastrophe");
        clock.addAndGet(3_600_000L + 1_000L);

        ArtifactResult blocked = runResult(service.beginReturn(ArtifactKey.END));
        assertFalse(blocked.success());
        assertEquals(ArtifactOutcome.EVENT_ACTIVE, blocked.outcome(),
                "the event check: returns are deferred while the occurrence runs");
        assertEquals(ArtifactState.LOST,
                service.byKey(ArtifactKey.END).orElseThrow().state());
    }

    @Test
    void completeReturnRestoresOriginWithAFreshInstance() {
        captureOk(ArtifactKey.OVERWORLD, playerA);
        lostOk(ArtifactKey.OVERWORLD, "dropped");
        clock.addAndGet(3_600_000L + 1_000L);
        Artifact inTransit = runResult(
                service.beginReturn(ArtifactKey.OVERWORLD)).artifact();

        ArtifactResult home = runResult(service.completeReturn(ArtifactKey.OVERWORLD));
        assertTrue(home.success(), home.detail());
        assertEquals(ArtifactOutcome.RETURNED, home.outcome());
        assertEquals(ArtifactState.ORIGIN, home.artifact().state());
        assertNotEquals(inTransit.instance(), home.artifact().instance(),
                "arrival mints the fresh discoverable origin instance");
        assertTrue(service.byInstance(home.artifact().instance()).isPresent());

        // Arriving twice is a benign no-op: the artifact is home.
        ArtifactResult again = runResult(service.completeReturn(ArtifactKey.OVERWORLD));
        assertTrue(again.success());
        assertEquals(ArtifactOutcome.RETURN_NOOP, again.outcome());
        assertFalse(again.mutated());
    }

    // ------------------------------------------------------------------
    // Rebellion event: qualification, idempotency, spending, completion
    // ------------------------------------------------------------------

    @Test
    void rebellionTriggerRequiresAllThreeHeldByOnePlayer() {
        captureOk(ArtifactKey.OVERWORLD, playerA);
        captureOk(ArtifactKey.NETHER, playerA);

        ArtifactResult twoOfThree = runResult(
                service.triggerRebellion(playerA, "two is not three"));
        assertFalse(twoOfThree.success());
        assertEquals(ArtifactOutcome.NOT_QUALIFIED, twoOfThree.outcome());
        assertTrue(twoOfThree.detail().contains("END"),
                "the refusal names the uncontrolled artifacts");

        captureOk(ArtifactKey.END, playerB);
        ArtifactResult splitHolders = runResult(
                service.triggerRebellion(playerA, "split control"));
        assertEquals(ArtifactOutcome.NOT_QUALIFIED, splitHolders.outcome());
        assertTrue(service.activeEvent().isEmpty());
        assertTrue(run(service.eventHistory()).isEmpty());
    }

    @Test
    void rebellionTriggerIsIdempotentPerOccurrence() {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "the world trembles");

        // Duplicate delivery (same holder, same reason, same instant).
        ArtifactResult duplicate = runResult(
                service.triggerRebellion(playerA, "the world trembles"));
        assertTrue(duplicate.success(), "idempotent no-ops are successes");
        assertEquals(ArtifactOutcome.EVENT_ALREADY_ACTIVE, duplicate.outcome());
        assertFalse(duplicate.mutated());
        assertEquals(event.eventId(), duplicate.eventOpt().orElseThrow().eventId(),
                "the duplicate resolves to the SAME occurrence");

        // A different fully-controlling scenario is impossible while the
        // guard is held - but a *different holder presentation* of the same
        // facts still collapses into the same occurrence.
        ArtifactResult otherReason = runResult(
                service.triggerRebellion(playerA, "duplicate inventory event"));
        assertEquals(ArtifactOutcome.EVENT_ALREADY_ACTIVE, otherReason.outcome());
        assertEquals(event.eventId(), otherReason.eventOpt().orElseThrow().eventId());

        assertEquals(1, run(service.eventHistory()).size(),
                "exactly one durable event row per occurrence");
        assertEquals(Optional.of(event), service.activeEvent());

        for (ArtifactKey key : ArtifactKey.values()) {
            List<String> actions = run(service.auditTrail(key)).stream()
                    .map(row -> row.action()).toList();
            assertEquals(2, actions.size(), "CAPTURE + REBELLION_TRIGGER per artifact");
            assertEquals("REBELLION_TRIGGER", actions.get(1));
        }
    }

    @Test
    void rebellionTriggerRefusedWhenDisabledByConfig() throws Exception {
        DatabaseService disabledDb = newService();
        ArtifactService disabled = new ArtifactService(disabledDb, config(false),
                clock::get);
        await(disabled.loadAll());
        for (ArtifactKey key : ArtifactKey.values()) {
            Artifact origin = disabled.byKey(key).orElseThrow();
            assertTrue(runResult(disabled.capture(key, origin.instance(), playerA))
                    .success());
        }

        ArtifactResult refused = runResult(
                disabled.triggerRebellion(playerA, "disabled by policy"));
        assertFalse(refused.success());
        assertEquals(ArtifactOutcome.FEATURE_DISABLED, refused.outcome());
        assertTrue(disabled.activeEvent().isEmpty());
        disabledDb.shutdown();
    }

    @Test
    void spendArtifactsIsAllOrNoneAndTriggerHolderOnly() {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "the catastrophe begins");

        // Only the trigger holder may spend.
        ArtifactResult stranger = runResult(
                service.spendArtifacts(playerB, "usurper"));
        assertFalse(stranger.success());
        assertEquals(ArtifactOutcome.INVALID_STATE, stranger.outcome());

        // One artifact slips away mid-event: spending is refused and NOTHING
        // is spent (all-or-none), even though two artifacts were still held.
        lostOk(ArtifactKey.NETHER, "lost mid-catastrophe");
        ArtifactResult partial = runResult(
                service.spendArtifacts(playerA, "overpowering attempt"));
        assertFalse(partial.success());
        assertEquals(ArtifactOutcome.SPENT_CONFLICT, partial.outcome());
        assertTrue(partial.detail().contains("NETHER"));
        for (ArtifactKey key : ArtifactKey.values()) {
            assertNotEquals(ArtifactState.SPENT,
                    service.byKey(key).orElseThrow().state(),
                    "all-or-none: no row may spend on a refused attempt");
        }

        // The artifact is recovered; the spend now succeeds for all three.
        foundOk(ArtifactKey.NETHER, playerA);
        ArtifactResult spent = runResult(
                service.spendArtifacts(playerA, "the world-wide purge"));
        assertTrue(spent.success(), spent.detail());
        assertEquals(ArtifactOutcome.SPENT, spent.outcome());
        for (ArtifactKey key : ArtifactKey.values()) {
            Artifact artifact = service.byKey(key).orElseThrow();
            assertEquals(ArtifactState.SPENT, artifact.state());
            assertTrue(artifact.holderOpt().isEmpty());
            assertTrue(service.byInstance(artifact.instance()).isEmpty(),
                    "SPENT stamps do not resolve - the copies are inert");
        }
        // The occurrence stays active until the catastrophe completes.
        assertEquals(Optional.of(event), service.activeEvent());

        // A retried spend is the typed idempotent refusal (restart mid-action).
        ArtifactResult retried = runResult(
                service.spendArtifacts(playerA, "retried after restart"));
        assertFalse(retried.success());
        assertEquals(ArtifactOutcome.SPENT_CONFLICT, retried.outcome());
        assertTrue(retried.detail().contains("already spent"),
                "the detail distinguishes a retry from a conflict: "
                        + retried.detail());
    }

    @Test
    void spentArtifactsRideTheReturnSweepHome() {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "spent, not destroyed forever");
        runResult(service.spendArtifacts(playerA, "the sacrifice"));

        // The occurrence completes, then the sweep may route the SPENT rows
        // home (the event check defers returns while it runs).
        assertTrue(runResult(service.completeRebellion(event.eventId(), null))
                .success());
        clock.addAndGet(3_600_000L + 1_000L);
        for (ArtifactKey key : ArtifactKey.values()) {
            ArtifactResult queued = runResult(service.beginReturn(key));
            assertEquals(ArtifactOutcome.RETURN_QUEUED, queued.outcome(),
                    "SPENT rows are return-eligible after the delay");
            ArtifactResult home = runResult(service.completeReturn(key));
            assertEquals(ArtifactOutcome.RETURNED, home.outcome());
            assertEquals(ArtifactState.ORIGIN, home.artifact().state(),
                    "the lineage is not invalidated - the world can reconquer");
        }
    }

    @Test
    void completeRebellionClearsTheGuardAndAllowsTheNextOccurrence() {
        captureAllFor(playerA);
        RebellionEvent first = triggerOk(playerA, "first catastrophe");

        ArtifactResult completed = runResult(
                service.completeRebellion(first.eventId(), admin));
        assertTrue(completed.success(), completed.detail());
        assertEquals(ArtifactOutcome.EVENT_COMPLETED, completed.outcome());
        assertEquals(RebellionEvent.State.COMPLETED,
                completed.eventOpt().orElseThrow().state());
        assertTrue(service.activeEvent().isEmpty(), "the guard is clear");

        // Idempotent completion.
        ArtifactResult again = runResult(
                service.completeRebellion(first.eventId(), admin));
        assertTrue(again.success());
        assertEquals(ArtifactOutcome.EVENT_COMPLETE_NOOP, again.outcome());

        // No permanent winner: the still-holding player (or, after the
        // return sweep, a reconqueror) mints a NEW durable occurrence.
        RebellionEvent second = triggerOk(playerA, "the second catastrophe");
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(2, run(service.eventHistory()).size());

        // Completing an unknown event is NOT_FOUND, not a crash.
        assertEquals(ArtifactOutcome.NOT_FOUND, runResult(
                service.completeRebellion(UUID.randomUUID(), admin)).outcome());
    }

    // ------------------------------------------------------------------
    // Admin recovery
    // ------------------------------------------------------------------

    @Test
    void adminRouteRecoversStuckStatesOnly() {
        // ORIGIN cannot be bypassed: the realm must be conquered.
        ArtifactResult origin = runResult(
                service.adminRoute(ArtifactKey.OVERWORLD, playerB, admin));
        assertEquals(ArtifactOutcome.INVALID_STATE, origin.outcome());

        // HELD is not the admin's to move.
        captureOk(ArtifactKey.OVERWORLD, playerA);
        assertEquals(ArtifactOutcome.INVALID_STATE, runResult(
                service.adminRoute(ArtifactKey.OVERWORLD, playerB, admin)).outcome());

        // LOST recovers to the routed target with a fresh stamp.
        captureOk(ArtifactKey.NETHER, playerA);
        Artifact lost = lostOk(ArtifactKey.NETHER, "stuck in the void");
        ArtifactResult routed = runResult(
                service.adminRoute(ArtifactKey.NETHER, playerB, admin));
        assertTrue(routed.success(), routed.detail());
        assertEquals(ArtifactOutcome.ROUTED, routed.outcome());
        assertEquals(Optional.of(playerB), routed.artifact().holderOpt());
        assertNotEquals(lost.instance(), routed.artifact().instance());
        assertTrue(service.byInstance(lost.instance()).isEmpty(),
                "the recovered-from stamp is inert");

        // RETURNING recovers too (a sweep that can never land its arrival).
        captureOk(ArtifactKey.END, playerA);
        lostOk(ArtifactKey.END, "dropped");
        clock.addAndGet(3_600_000L + 1_000L);
        runResult(service.beginReturn(ArtifactKey.END));
        ArtifactResult midTransit = runResult(
                service.adminRoute(ArtifactKey.END, playerB, admin));
        assertEquals(ArtifactOutcome.ROUTED, midTransit.outcome());

        var trail = run(service.auditTrail(ArtifactKey.NETHER)).stream()
                .map(row -> row.action()).toList();
        assertTrue(trail.contains("ADMIN_ROUTE"), "the route is audited");
    }

    // ------------------------------------------------------------------
    // Audit discipline + restart round-trip
    // ------------------------------------------------------------------

    @Test
    void auditTrailRecordsEveryRealMutationAndNoNoOp() {
        captureOk(ArtifactKey.OVERWORLD, playerA);
        Artifact held = service.byKey(ArtifactKey.OVERWORLD).orElseThrow();
        runResult(service.transfer(ArtifactKey.OVERWORLD, playerA, playerB,
                held.instance()));
        lostOk(ArtifactKey.OVERWORLD, "dropped");
        runResult(service.markLost(ArtifactKey.OVERWORLD, "idempotent re-park"));
        foundOk(ArtifactKey.OVERWORLD, finder);
        runResult(service.returnToOrigin(ArtifactKey.OVERWORLD, finder,
                service.byKey(ArtifactKey.OVERWORLD).orElseThrow().instance()));
        runResult(service.completeReturn(ArtifactKey.OVERWORLD));

        List<String> actions = run(service.auditTrail(ArtifactKey.OVERWORLD)).stream()
                .map(row -> row.action()).toList();
        assertEquals(List.of("CAPTURE", "TRANSFER", "LOST", "FOUND",
                "RETURN_SUBMIT", "RETURN_COMPLETE"), actions,
                "one row per real mutation; the re-park no-op wrote nothing");
    }

    @Test
    void fullRestartRoundTripPreservesEveryStateStampAndEvent() throws Exception {
        // Phase 1: distribute the three artifacts across distinct states.
        Artifact heldOverworld = captureOk(ArtifactKey.OVERWORLD, playerA);
        Artifact lostEnd = captureOk(ArtifactKey.END, playerA);
        lostOk(ArtifactKey.END, "dropped before the restart");
        Artifact originNether = service.byKey(ArtifactKey.NETHER).orElseThrow();

        // Restart: a fresh service over the same SQLite file. Helpers now
        // operate on the restarted authority.
        db.shutdown();
        db = newService();
        ArtifactService restarted = new ArtifactService(db, config(), clock::get);
        await(restarted.loadAll());
        service = restarted;

        assertEquals(3, service.count());
        Artifact overworld = service.byKey(ArtifactKey.OVERWORLD).orElseThrow();
        Artifact end = service.byKey(ArtifactKey.END).orElseThrow();
        Artifact nether = service.byKey(ArtifactKey.NETHER).orElseThrow();
        assertEquals(ArtifactState.HELD, overworld.state());
        assertEquals(Optional.of(playerA), overworld.holderOpt());
        assertEquals(heldOverworld.instance(), overworld.instance(),
                "identity stamps round-trip exactly");
        assertEquals(heldOverworld.artifactId(), overworld.artifactId());
        assertEquals(ArtifactState.LOST, end.state());
        assertEquals(Optional.of(playerA), end.holderOpt(), "last holder kept");
        assertEquals(lostEnd.instance(), end.instance());
        assertEquals(ArtifactState.ORIGIN, nether.state());
        assertEquals(originNether.instance(), nether.instance());
        assertTrue(service.activeEvent().isEmpty());

        // Phase 2: the lifecycle continues seamlessly on the restarted
        // authority - and a full possession still triggers after a restart.
        foundOk(ArtifactKey.END, playerA);
        captureOk(ArtifactKey.NETHER, playerA);
        RebellionEvent event = triggerOk(playerA, "triggered after a restart");
        ArtifactResult spent = runResult(
                service.spendArtifacts(playerA, "spent after a restart"));
        assertTrue(spent.success(), spent.detail());

        // Phase 3: a second restart preserves SPENT rows and the ACTIVE
        // occurrence (crash mid-catastrophe).
        db.shutdown();
        db = newService();
        ArtifactService resumed = new ArtifactService(db, config(), clock::get);
        await(resumed.loadAll());
        service = resumed;
        for (ArtifactKey key : ArtifactKey.values()) {
            assertEquals(ArtifactState.SPENT,
                    service.byKey(key).orElseThrow().state());
        }
        assertEquals(event.eventId(), service.activeEvent()
                .orElseThrow().eventId(),
                "the active occurrence survives the crash");
        assertEquals(event.eventId(), run(service.eventHistory()).get(0).eventId());

        // Phase 4: completion + the return sweep bring the world back.
        assertTrue(runResult(service.completeRebellion(event.eventId(), null))
                .success());
        clock.addAndGet(3_600_000L + 1_000L);
        for (ArtifactKey key : ArtifactKey.values()) {
            assertEquals(ArtifactOutcome.RETURN_QUEUED,
                    runResult(service.beginReturn(key)).outcome());
            assertEquals(ArtifactOutcome.RETURNED,
                    runResult(service.completeReturn(key)).outcome());
        }
        assertTrue(service.activeEvent().isEmpty());
    }

    // ------------------------------------------------------------------
    // Purge stamp (ADR-0015 §4, schema v2)
    // ------------------------------------------------------------------

    @Test
    void purgeClaimIsOncePerOccurrence() {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "purge gate test");
        ArtifactResult first = runResult(
                service.executePurge(event.eventId(), admin));
        assertEquals(ArtifactOutcome.PURGE_EXECUTED, first.outcome());
        assertNotNull(first.event());
        assertNotNull(first.event().purgeExecutedAtUtc(),
                "the winner carries the execution stamp");

        ArtifactResult second = runResult(
                service.executePurge(event.eventId(), admin));
        assertEquals(ArtifactOutcome.PURGE_ALREADY_EXECUTED, second.outcome());
        assertFalse(second.mutated(), "the retry must be a typed no-op");

        // The active-event mirror carries the stamped occurrence.
        assertTrue(service.activeEvent().isPresent());
        assertEquals(event.eventId(), service.activeEvent().get().eventId());
        assertNotNull(service.activeEvent().get().purgeExecutedAtUtc());
    }

    @Test
    void purgeStampSurvivesRestartAndBlocksReExecution() throws Exception {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "restart round-trip");
        runResult(service.executePurge(event.eventId(), admin));

        // Restart: a fresh service over the same database file.
        ArtifactService second = new ArtifactService(db, config(), clock::get);
        await(second.loadAll());
        assertTrue(second.activeEvent().isPresent());
        assertNotNull(second.activeEvent().get().purgeExecutedAtUtc(),
                "the once-per-occurrence stamp must survive the restart");

        ArtifactResult retried = runResult(
                second.executePurge(event.eventId(), admin));
        assertEquals(ArtifactOutcome.PURGE_ALREADY_EXECUTED, retried.outcome());
        assertFalse(retried.mutated());
    }

    @Test
    void completeRebellionPreservesThePurgeStamp() {
        captureAllFor(playerA);
        RebellionEvent event = triggerOk(playerA, "completion keeps the stamp");
        runResult(service.executePurge(event.eventId(), admin));

        ArtifactResult completed = runResult(
                service.completeRebellion(event.eventId(), admin));
        assertEquals(ArtifactOutcome.EVENT_COMPLETED, completed.outcome());
        assertNotNull(completed.event().purgeExecutedAtUtc(),
                "completion must not rewrite or clear the purge stamp");
    }

    @Test
    void purgeRefusesUnknownEvents() {
        ArtifactResult result = runResult(
                service.executePurge(UUID.randomUUID(), admin));
        assertEquals(ArtifactOutcome.NOT_FOUND, result.outcome());
        assertFalse(result.mutated());
    }

    @Test
    void migrationSetIsAppendOnlyAndReApplyIsANoOp() throws Exception {
        // ADR-0005 discipline, pinned: the runner refuses a CHANGED set for
        // an already-migrated module (a v1-only list after v1+v2 is a loud
        // refusal, never a silent partial state), and re-applying the same
        // list is an idempotent no-op. No v1-era artifacts database can
        // exist in the wild (the module shipped no jar before this commit),
        // so the v1+v2 split is the append-only audit trail, not an upgrade
        // path that needs live data.
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> await(db.applyModuleMigrations("artifacts",
                        java.util.List.of(ArtifactsSchema.v1()))),
                "a shrunk migration set must be refused loudly");
        await(db.applyModuleMigrations("artifacts", ArtifactsSchema.migrations()));
        assertEquals(3, service.count(), "re-apply changed nothing");
    }

}
