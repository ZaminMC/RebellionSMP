package net.zaminmc.rebellion.rpg.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.rpg.ClaimState;
import net.zaminmc.rebellion.rpg.Discovery;
import net.zaminmc.rebellion.rpg.Encounter;
import net.zaminmc.rebellion.rpg.EncounterDefinition;
import net.zaminmc.rebellion.rpg.EncounterState;
import net.zaminmc.rebellion.rpg.Precision;
import net.zaminmc.rebellion.rpg.RitualClaim;
import net.zaminmc.rebellion.rpg.RitualDefinition;
import net.zaminmc.rebellion.rpg.RitualKind;
import net.zaminmc.rebellion.rpg.RpgConfig;
import net.zaminmc.rebellion.rpg.RpgOutcome;
import net.zaminmc.rebellion.rpg.RpgResult;
import net.zaminmc.rebellion.rpg.RpgService;
import net.zaminmc.rebellion.rpg.TrackingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RpgService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0016 evidence set. Every §5.3 persistence
 * invariant is exercised for real: PK-idempotent discoveries, the claim
 * protocol (reserve refusal families, refund release, all-or-none commits,
 * the failure roll spending the claim, session-cap auto-release, re-delivery
 * no-ops, the stale sweep), mask degradation composition with derived
 * expiry, the encounter ledger (guards, concurrency cap, cooldown, TTL
 * sweep, one-winner rewards, admin despawn) and the restart round-trip.
 * The clock and the failure-roll source are injected — deterministic.
 */
final class RpgServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private RpgService service;

    /** Mutable service clock: cooldown/duration/sweep tests advance it. */
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    /** Failure-roll source: 0.01 while the seed is 0 (always lose), 0.99 after. */
    private final AtomicLong rollSeed = new AtomicLong();

    private final UUID caster = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID helper = UUID.randomUUID();
    private final UUID helper2 = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static RpgConfig config() {
        return RpgConfig.defaults();
    }

    /**
     * Test-friendly tracking ritual: injectable cooldown / precision /
     * failure / duration / session cap, no practical rate limit.
     */
    private static RitualDefinition tracking(long cooldownSeconds,
            Precision precision, double failure, long durationSeconds,
            int maxSessions) {
        return new RitualDefinition("tracking", true, RitualKind.TRACKING,
                List.of(), cooldownSeconds, 100, 1L, durationSeconds, precision,
                failure, maxSessions, Optional.empty());
    }

    /** Defaults with the tracking ritual replaced. */
    private static RpgConfig config(RitualDefinition tracking) {
        RpgConfig base = RpgConfig.defaults();
        Map<String, RitualDefinition> rituals = new HashMap<>(base.rituals());
        rituals.put("tracking", tracking);
        return new RpgConfig(rituals, base.encounters());
    }

    /** The sample encounter enabled with a 1h ledger cooldown. */
    private static EncounterDefinition wanderingSoul(long cooldownSeconds) {
        return new EncounterDefinition("wandering_soul", true, List.of("world"),
                List.of(), EncounterDefinition.TimeWindow.NIGHT, 1, 0, 1,
                cooldownSeconds, 1800L, List.of("ritual_soul_communion"));
    }

    private static RpgConfig encounters(EncounterDefinition definition) {
        return new RpgConfig(config().rituals(), Map.of("wandering_soul", definition));
    }

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        service = new RpgService(db, config(), clock::get, this::roll);
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    /** Deterministic roll: 0.01 while the seed is 0, 0.99 after flipping. */
    private double roll() {
        return rollSeed.get() == 0 ? 0.01 : 0.99;
    }

    private DatabaseService newService() throws Exception {
        DatabaseService fresh = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(fresh.bootstrap());
        await(fresh.applyModuleMigrations("rpg", RpgSchema.migrations()));
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

    private static <T> T awaitVal(CompletableFuture<T> future) {
        await(future);
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Reserves + commits a tracking cast in one motion (success roll). */
    private TrackingSession castTracking(UUID theCaster, UUID theTarget) {
        rollSeed.set(1);
        RpgResult<RitualClaim> reserved = awaitVal(service.reserveRitual(theCaster,
                "tracking", "echo_shard:4,diamond:2"));
        assertTrue(reserved.success(), reserved::toString);
        RpgResult<TrackingSession> committed = awaitVal(service.commitTracking(
                reserved.payload().claimId(), theCaster, theTarget));
        assertTrue(committed.success(), committed::toString);
        return committed.payload();
    }

    // ------------------------------------------------------------------
    // Discoveries
    // ------------------------------------------------------------------

    @Test
    void discoveryIsIdempotentByPk() {
        RpgResult<Discovery> first = awaitVal(service.discover(helper,
                "ritual_soul_communion", Discovery.SOURCE_ENCOUNTER));
        assertEquals(RpgOutcome.DISCOVERED, first.outcome());
        assertTrue(first.mutated());

        RpgResult<Discovery> second = awaitVal(service.discover(helper,
                "ritual_soul_communion", Discovery.SOURCE_ADMIN));
        assertEquals(RpgOutcome.ALREADY_KNOWN, second.outcome());
        assertFalse(second.mutated());
        // The original row is untouched — source stays ENCOUNTER.
        assertEquals(Discovery.SOURCE_ENCOUNTER, second.payload().source());

        List<Discovery> all = awaitVal(service.discoveries(helper));
        assertEquals(1, all.size());
        assertTrue(awaitVal(service.hasDiscovery(helper, "ritual_soul_communion")));
        assertFalse(awaitVal(service.hasDiscovery(helper, "something_else")));
        // Exactly one audit event for the discovery (idempotent key).
        assertEquals(1, awaitVal(service.eventsFor(helper)).stream()
                .filter(e -> e.type().equals("DISCOVERY")).count());
    }

    @Test
    void discoveryRejectsBadKeys() {
        RpgResult<Discovery> bad = awaitVal(service.discover(helper, "Bad Key!",
                Discovery.SOURCE_ADMIN));
        assertEquals(RpgOutcome.ARGUMENT_INVALID, bad.outcome());
        assertFalse(bad.success());
    }

    // ------------------------------------------------------------------
    // Reserve / release
    // ------------------------------------------------------------------

    @Test
    void reserveCreatesClaimWithDigest() {
        RpgResult<RitualClaim> reserved = awaitVal(service.reserveRitual(caster,
                "tracking", "echo_shard:4,diamond:2"));
        assertEquals(RpgOutcome.RESERVED, reserved.outcome());
        RitualClaim claim = reserved.payload();
        assertEquals(ClaimState.RESERVED, claim.state());
        assertEquals(caster, claim.playerUuid());
        assertEquals("echo_shard:4,diamond:2", claim.ingredientsDigest());
        assertNull(claim.resolvedUtc());
    }

    @Test
    void reserveRefusalsAreTyped() {
        assertEquals(RpgOutcome.RITUAL_UNKNOWN, awaitVal(service.reserveRitual(caster,
                "nope", "x")).outcome());
        // A disabled ritual refuses before any database access.
        Map<String, RitualDefinition> rituals = new HashMap<>(config().rituals());
        rituals.put("off", new RitualDefinition("off", false, RitualKind.TRACKING,
                List.of(), 0L, 100, 1L, 60L, Precision.REGION, 0.0, 1,
                Optional.empty()));
        RpgService withDisabled = new RpgService(db, new RpgConfig(rituals,
                config().encounters()), clock::get, this::roll);
        assertEquals(RpgOutcome.RITUAL_DISABLED, awaitVal(withDisabled.reserveRitual(
                caster, "off", "x")).outcome());
        assertEquals(RpgOutcome.ARGUMENT_INVALID, awaitVal(service.reserveRitual(
                caster, " ", "x")).outcome());
    }

    @Test
    void rateLimitCountsReleasedAttemptsToo() {
        // Default tracking: 3 attempts / 6h window.
        for (int i = 0; i < 2; i++) {
            RpgResult<RitualClaim> reserved = awaitVal(service.reserveRitual(caster,
                    "tracking", "d"));
            assertEquals(RpgOutcome.RESERVED, reserved.outcome());
            assertEquals(RpgOutcome.RELEASED, awaitVal(service.releaseReservation(
                    reserved.payload().claimId(), caster, "test")).outcome());
        }
        // Third attempt still allowed; fourth is refused even though nothing
        // was ever committed — released attempts count (conservative).
        RpgResult<RitualClaim> third = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.RESERVED, third.outcome());
        RpgResult<RitualClaim> fourth = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.RATE_LIMITED, fourth.outcome());
        assertFalse(fourth.success());
        // A different ritual is unaffected (per-ritual limit).
        assertEquals(RpgOutcome.RESERVED, awaitVal(service.reserveRitual(caster,
                "mask", "amethyst_shard:2")).outcome());
    }

    @Test
    void cooldownCountsOnlyCommittedAttempts() {
        // High rate cap so the cooldown check is what fires, not the limit.
        service = new RpgService(db, config(tracking(3600L, Precision.REGION, 0.0,
                1800L, 1)), clock::get, this::roll);

        // Released reservations never buy a cooldown.
        RpgResult<RitualClaim> first = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        awaitVal(service.releaseReservation(first.payload().claimId(), caster,
                "test"));
        RpgResult<RitualClaim> second = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.RESERVED, second.outcome());
        awaitVal(service.releaseReservation(second.payload().claimId(), caster,
                "test"));

        // A committed cast (custom config: no failure) buys the 1h cooldown
        // only from its commit time.
        castTracking(caster, target);
        RpgResult<RitualClaim> blocked = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.COOLDOWN_ACTIVE, blocked.outcome());
        assertFalse(blocked.success());

        clock.addAndGet(3601_000L);
        assertEquals(RpgOutcome.RESERVED, awaitVal(service.reserveRitual(caster,
                "tracking", "d")).outcome());
    }

    @Test
    void doubleReservationIsRefused() {
        assertEquals(RpgOutcome.RESERVED, awaitVal(service.reserveRitual(caster,
                "tracking", "d")).outcome());
        RpgResult<RitualClaim> second = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.RESERVATION_HELD, second.outcome());
        assertFalse(second.success());
    }

    @Test
    void releaseIsIdempotentAndAudited() {
        UUID claimId = awaitVal(service.reserveRitual(caster, "mask", "d"))
                .payload().claimId();
        RpgResult<RitualClaim> released = awaitVal(service.releaseReservation(claimId,
                caster, "inventory-refusal"));
        assertEquals(RpgOutcome.RELEASED, released.outcome());
        assertTrue(released.mutated());
        assertEquals(ClaimState.RELEASED, released.payload().state());
        assertNotNull(released.payload().resolvedUtc());

        RpgResult<RitualClaim> again = awaitVal(service.releaseReservation(claimId,
                caster, "again"));
        assertEquals(RpgOutcome.RELEASE_NOOP, again.outcome());
        assertFalse(again.mutated());

        // One release event, carrying the reason; unknown claim typed.
        assertEquals(1, awaitVal(service.eventsFor(caster)).stream()
                .filter(e -> e.type().equals("CLAIM_RELEASE")).count());
        assertEquals(RpgOutcome.CLAIM_NOT_FOUND, awaitVal(service.releaseReservation(
                UUID.randomUUID(), caster, "x")).outcome());
    }

    // ------------------------------------------------------------------
    // Commits
    // ------------------------------------------------------------------

    @Test
    void trackingCommitCreatesSessionWithGrantedPrecision() {
        TrackingSession session = castTracking(caster, target);
        assertEquals(Precision.REGION, session.precision());
        assertEquals(caster, session.casterUuid());
        assertEquals(target, session.targetUuid());
        assertNull(session.revokedUtc());
        assertEquals(clock.get(), session.beganUtc());
        // Default duration 1800s stamped on the row.
        assertEquals(clock.get() + 1800_000L, session.expiresUtc());

        assertEquals(1, awaitVal(service.activeSessions(caster)).size());
        // The claim is committed and audited SUCCESS.
        assertTrue(awaitVal(service.eventsFor(caster)).stream().anyMatch(
                e -> e.type().equals("RITUAL_COMMIT")
                        && "SUCCESS".equals(e.outcome())));
    }

    @Test
    void failureRollSpendsClaimWithoutSession() {
        // rollSeed 0 => roll 0.01 < 0.25 default failure chance.
        RpgResult<RitualClaim> reserved = awaitVal(service.reserveRitual(caster,
                "tracking", "echo_shard:4,diamond:2"));
        RpgResult<TrackingSession> failed = awaitVal(service.commitTracking(
                reserved.payload().claimId(), caster, target));
        assertEquals(RpgOutcome.RITUAL_FAILED, failed.outcome());
        assertTrue(failed.mutated(), "the claim was still spent");
        assertTrue(awaitVal(service.activeSessions(caster)).isEmpty());
        // Cooldown WAS bought — the very next reserve is blocked (attempt 2
        // of 3 stays under the rate cap, so the cooldown check is what fires).
        assertEquals(RpgOutcome.COOLDOWN_ACTIVE, awaitVal(service.reserveRitual(
                caster, "tracking", "d")).outcome());
        // The durable outcome is FAILED, so re-delivery carries it.
        RpgResult<TrackingSession> reDelivery = awaitVal(service.commitTracking(
                reserved.payload().claimId(), caster, target));
        assertEquals(RpgOutcome.COMMIT_NOOP, reDelivery.outcome());
        assertTrue(reDelivery.detail().contains("FAILED"));
    }

    @Test
    void reDeliveryAfterSuccessCarriesOriginalOutcome() {
        TrackingSession session = castTracking(caster, target);
        RpgResult<TrackingSession> again = awaitVal(service.commitTracking(
                session.claimId(), caster, target));
        assertEquals(RpgOutcome.COMMIT_NOOP, again.outcome());
        assertFalse(again.mutated());
        assertTrue(again.detail().contains("SUCCESS"));
        // Still exactly one session (the UNIQUE claim_id backstop held).
        assertEquals(1, awaitVal(service.activeSessions(caster)).size());
    }

    @Test
    void wrongCommitPathRollsBackAndKeepsReservation() {
        RpgResult<RitualClaim> maskReserved = awaitVal(service.reserveRitual(caster,
                "mask", "amethyst_shard:2"));
        // Tracking-commit against a COUNTER_MASK reservation: refused.
        RpgResult<TrackingSession> wrong = awaitVal(service.commitTracking(
                maskReserved.payload().claimId(), caster, target));
        assertEquals(RpgOutcome.ARGUMENT_INVALID, wrong.outcome());
        // The refusal threw inside the transaction -> rollback: still RESERVED.
        RitualClaim claim = awaitVal(service.claims(caster)).get(0);
        assertEquals(ClaimState.RESERVED, claim.state());
        // And the proper path still works on the same claim.
        rollSeed.set(1);
        assertEquals(RpgOutcome.MASK_GRANTED, awaitVal(service.commitCounterMask(
                claim.claimId(), caster, caster)).outcome());
    }

    @Test
    void sessionCapAutoReleasesTheClaim() {
        // Two allowed sessions, no cooldown/rate cap.
        service = new RpgService(db, config(tracking(0L, Precision.REGION, 0.0,
                1800L, 2)), clock::get, this::roll);
        castTracking(caster, target);
        castTracking(caster, UUID.randomUUID());

        RpgResult<RitualClaim> third = awaitVal(service.reserveRitual(caster,
                "tracking", "d"));
        assertEquals(RpgOutcome.RESERVED, third.outcome());
        RpgResult<TrackingSession> refused = awaitVal(service.commitTracking(
                third.payload().claimId(), caster, target));
        assertEquals(RpgOutcome.SESSION_CAP_REACHED, refused.outcome());
        assertFalse(refused.success());
        // The claim was auto-RELEASED (mutated refusal) — refund path open.
        List<RitualClaim> claims = awaitVal(service.claims(caster));
        assertEquals(ClaimState.RELEASED, claims.get(claims.size() - 1).state());
    }

    // ------------------------------------------------------------------
    // Mask composition + derived expiry
    // ------------------------------------------------------------------

    @Test
    void maskDegradesEffectivePrecisionOneTier() {
        service = new RpgService(db, config(tracking(0L, Precision.COORDINATE, 0.0,
                7200L, 1)), clock::get, this::roll);
        castTracking(caster, target);
        // Unmasked: full granted tier.
        List<RpgService.TrackingView> before = awaitVal(service.resolveTracking(
                caster));
        assertEquals(1, before.size());
        assertFalse(before.get(0).masked());
        assertEquals(Optional.of(Precision.COORDINATE),
                before.get(0).effectivePrecision());

        // Target masks: COORDINATE degrades to REGION (not erased).
        rollSeed.set(1);
        UUID maskClaim = awaitVal(service.reserveRitual(target, "mask",
                "amethyst_shard:2")).payload().claimId();
        assertEquals(RpgOutcome.MASK_GRANTED, awaitVal(service.commitCounterMask(
                maskClaim, target, target)).outcome());

        List<RpgService.TrackingView> after = awaitVal(service.resolveTracking(
                caster));
        assertTrue(after.get(0).masked());
        assertEquals(Optional.of(Precision.REGION),
                after.get(0).effectivePrecision());
        assertFalse(after.get(0).fullyMasked());
    }

    @Test
    void dimensionTrackIsFullyErasedByMask() {
        service = new RpgService(db, config(tracking(0L, Precision.DIMENSION, 0.0,
                7200L, 1)), clock::get, this::roll);
        castTracking(caster, target);
        rollSeed.set(1);
        UUID maskClaim = awaitVal(service.reserveRitual(target, "mask",
                "amethyst_shard:2")).payload().claimId();
        awaitVal(service.commitCounterMask(maskClaim, target, target));

        List<RpgService.TrackingView> views = awaitVal(service.resolveTracking(
                caster));
        assertTrue(views.get(0).masked());
        assertTrue(views.get(0).fullyMasked());
        assertTrue(views.get(0).effectivePrecision().isEmpty());
    }

    @Test
    void maskExpiryRestoresPrecisionWithoutAnySweep() {
        service = new RpgService(db, config(tracking(0L, Precision.COORDINATE, 0.0,
                7200L, 1)), clock::get, this::roll);
        castTracking(caster, target);
        rollSeed.set(1);
        UUID maskClaim = awaitVal(service.reserveRitual(target, "mask",
                "amethyst_shard:2")).payload().claimId();
        awaitVal(service.commitCounterMask(maskClaim, target, target));
        assertTrue(awaitVal(service.resolveTracking(caster)).get(0).masked());

        // Default mask duration is 3600s; the session lasts 7200s.
        clock.addAndGet(3601_000L);
        List<RpgService.TrackingView> restored = awaitVal(service.resolveTracking(
                caster));
        assertFalse(restored.get(0).masked());
        assertEquals(Optional.of(Precision.COORDINATE),
                restored.get(0).effectivePrecision());
    }

    @Test
    void sessionExpiryIsDerivedNotSwept() {
        castTracking(caster, target);
        clock.addAndGet(1801_000L);
        assertTrue(awaitVal(service.resolveTracking(caster)).isEmpty());
        assertTrue(awaitVal(service.activeSessions(caster)).isEmpty());
    }

    @Test
    void adminRevocationEndsASession() {
        TrackingSession session = castTracking(caster, target);
        RpgResult<TrackingSession> revoked = awaitVal(service.revokeSession(
                session.sessionId(), "test-revoke"));
        assertEquals(RpgOutcome.SESSION_REVOKED, revoked.outcome());
        assertEquals("test-revoke", revoked.payload().revokeReason());
        assertTrue(awaitVal(service.activeSessions(caster)).isEmpty());
        // Idempotent: re-revocation no-ops; unknown session typed.
        assertEquals(RpgOutcome.SESSION_REVOKE_NOOP, awaitVal(service.revokeSession(
                session.sessionId(), "again")).outcome());
        assertEquals(RpgOutcome.SESSION_NOT_FOUND, awaitVal(service.revokeSession(
                UUID.randomUUID(), "x")).outcome());
    }

    // ------------------------------------------------------------------
    // Knowledge rituals
    // ------------------------------------------------------------------

    @Test
    void knowledgeRitualRecordsDiscoveryIdempotently() {
        rollSeed.set(1);
        UUID claim = awaitVal(service.reserveRitual(caster, "soul_lore",
                "book:1,lapis_lazuli:8")).payload().claimId();
        RpgResult<Discovery> first = awaitVal(service.commitKnowledge(claim, caster));
        assertEquals(RpgOutcome.KNOWLEDGE_COMMITTED, first.outcome());
        assertEquals("ritual_soul_communion", first.payload().key());
        assertEquals(Discovery.SOURCE_RITUAL, first.payload().source());

        // Past cooldown + rate window, a second cast is spent but learns
        // nothing new (PK idempotency) — the detail says which.
        clock.addAndGet(86_401_000L);
        rollSeed.set(1);
        UUID claim2 = awaitVal(service.reserveRitual(caster, "soul_lore",
                "book:1,lapis_lazuli:8")).payload().claimId();
        RpgResult<Discovery> second = awaitVal(service.commitKnowledge(claim2,
                caster));
        assertEquals(RpgOutcome.KNOWLEDGE_COMMITTED, second.outcome());
        assertTrue(second.detail().contains("already known"));
        assertEquals(1, awaitVal(service.discoveries(caster)).size());
    }

    // ------------------------------------------------------------------
    // Stale sweep
    // ------------------------------------------------------------------

    @Test
    void staleSweepReleasesOnlyOldReservations() {
        UUID oldClaim = awaitVal(service.reserveRitual(caster, "tracking", "d"))
                .payload().claimId();
        clock.addAndGet(7200_000L);
        UUID freshClaim = awaitVal(service.reserveRitual(caster, "mask", "d"))
                .payload().claimId();

        int released = awaitVal(service.releaseStaleReservations(3600_000L));
        assertEquals(1, released);
        List<RitualClaim> claims = awaitVal(service.claims(caster));
        // Old released with a sweep event; fresh untouched.
        assertEquals(ClaimState.RELEASED, claims.stream()
                .filter(c -> c.claimId().equals(oldClaim)).findFirst().orElseThrow()
                .state());
        assertEquals(ClaimState.RESERVED, claims.stream()
                .filter(c -> c.claimId().equals(freshClaim)).findFirst()
                .orElseThrow().state());
        assertTrue(awaitVal(service.eventsFor(caster)).stream().anyMatch(
                e -> e.detail() != null && e.detail().contains("stale-sweep")));
    }

    // ------------------------------------------------------------------
    // Encounters
    // ------------------------------------------------------------------

    @Test
    void encounterLifecycleWithOneWinnerRewards() {
        service = new RpgService(db, encounters(wanderingSoul(0L)), clock::get,
                this::roll);
        RpgResult<Encounter> begun = awaitVal(service.beginEncounter(
                "wandering_soul", "world", 100, 64, -200));
        assertEquals(RpgOutcome.ENCOUNTER_BEGUN, begun.outcome());
        Encounter encounter = begun.payload();
        assertEquals(EncounterState.ACTIVE, encounter.state());
        assertNull(encounter.soulEntity());

        UUID soul = UUID.randomUUID();
        assertEquals(RpgOutcome.SOUL_ATTACHED, awaitVal(service.attachSoul(
                encounter.encounterId(), soul)).outcome());
        assertEquals(RpgOutcome.SOUL_ATTACH_NOOP, awaitVal(service.attachSoul(
                encounter.encounterId(), UUID.randomUUID())).outcome());
        assertEquals(soul, awaitVal(service.encounter(encounter.encounterId()))
                .orElseThrow().soulEntity());

        RpgResult<Encounter> completed = awaitVal(service.completeEncounter(
                encounter.encounterId(), helper));
        assertEquals(RpgOutcome.ENCOUNTER_COMPLETED, completed.outcome());
        assertEquals(helper, completed.payload().completedBy());

        // The loser earns nothing — unique reward claims.
        RpgResult<Encounter> lost = awaitVal(service.completeEncounter(
                encounter.encounterId(), helper2));
        assertEquals(RpgOutcome.ENCOUNTER_ALREADY_RESOLVED, lost.outcome());
        assertFalse(lost.mutated());
        assertTrue(awaitVal(service.hasDiscovery(helper, "ritual_soul_communion")));
        assertFalse(awaitVal(service.hasDiscovery(helper2, "ritual_soul_communion")));

        // A helper who already knows the reward can still complete a fresh
        // encounter — the completion CAS is what matters, not novelty.
        Encounter second = awaitVal(service.beginEncounter("wandering_soul",
                "world", 20, 64, 20)).payload();
        RpgResult<Encounter> known = awaitVal(service.completeEncounter(
                second.encounterId(), helper));
        assertEquals(RpgOutcome.ENCOUNTER_COMPLETED, known.outcome());
        assertTrue(known.detail().contains("0 new"));
    }

    @Test
    void encounterGuardsAreTyped() {
        assertEquals(RpgOutcome.ENCOUNTER_UNKNOWN, awaitVal(service.beginEncounter(
                "ghost", "world", 0, 64, 0)).outcome());
        // Default config ships the sample encounter disabled.
        assertEquals(RpgOutcome.ENCOUNTER_DISABLED, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 0, 64, 0)).outcome());
        service = new RpgService(db, encounters(wanderingSoul(0L)), clock::get,
                this::roll);
        assertEquals(RpgOutcome.WORLD_REFUSED, awaitVal(service.beginEncounter(
                "wandering_soul", "world_nether", 0, 64, 0)).outcome());
    }

    @Test
    void encounterConcurrencyCap() {
        service = new RpgService(db, encounters(wanderingSoul(0L)), clock::get,
                this::roll);
        assertEquals(RpgOutcome.ENCOUNTER_BEGUN, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 0, 64, 0)).outcome());
        assertEquals(RpgOutcome.CONCURRENCY_CAP, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 50, 64, 50)).outcome());

        // Resolve it; with a 0s ledger cooldown a fresh one may begin.
        Encounter first = awaitVal(service.encounters(EncounterState.ACTIVE))
                .get(0);
        awaitVal(service.completeEncounter(first.encounterId(), helper));
        assertEquals(RpgOutcome.ENCOUNTER_BEGUN, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 500, 64, 500)).outcome());
    }

    @Test
    void encounterCooldownBlocksUntilElapsed() {
        service = new RpgService(db, encounters(wanderingSoul(3600L)), clock::get,
                this::roll);
        Encounter first = awaitVal(service.beginEncounter("wandering_soul", "world",
                0, 64, 0)).payload();
        awaitVal(service.completeEncounter(first.encounterId(), helper));
        assertEquals(RpgOutcome.ENCOUNTER_COOLDOWN, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 0, 64, 0)).outcome());
        clock.addAndGet(3601_000L);
        assertEquals(RpgOutcome.ENCOUNTER_BEGUN, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 0, 64, 0)).outcome());
    }

    @Test
    void encounterTtlSweepExpiresAndLocks() {
        service = new RpgService(db, encounters(wanderingSoul(0L)), clock::get,
                this::roll);
        Encounter encounter = awaitVal(service.beginEncounter("wandering_soul",
                "world", 0, 64, 0)).payload();
        assertEquals(0, awaitVal(service.expireEncounters()));
        clock.addAndGet(1801_000L);
        assertEquals(1, awaitVal(service.expireEncounters()));
        // Idempotent: nothing left to expire.
        assertEquals(0, awaitVal(service.expireEncounters()));
        Encounter expired = awaitVal(service.encounter(encounter.encounterId()))
                .orElseThrow();
        assertEquals(EncounterState.EXPIRED, expired.state());
        // An expired encounter can no longer be completed.
        assertEquals(RpgOutcome.ENCOUNTER_ALREADY_RESOLVED, awaitVal(
                service.completeEncounter(encounter.encounterId(), helper)).outcome());
        assertFalse(awaitVal(service.hasDiscovery(helper, "ritual_soul_communion")));
    }

    @Test
    void adminDespawnUnwedgesAndCountsAsResolved() {
        service = new RpgService(db, encounters(wanderingSoul(3600L)), clock::get,
                this::roll);
        Encounter encounter = awaitVal(service.beginEncounter("wandering_soul",
                "world", 0, 64, 0)).payload();
        RpgResult<Encounter> despawned = awaitVal(service.despawnEncounter(
                encounter.encounterId(), "definition-removed"));
        assertEquals(RpgOutcome.ENCOUNTER_EXPIRED, despawned.outcome());
        assertEquals(EncounterState.DESPAWNED, despawned.payload().state());
        // DESPAWNED counts as resolved: cooldown is active.
        assertEquals(RpgOutcome.ENCOUNTER_COOLDOWN, awaitVal(service.beginEncounter(
                "wandering_soul", "world", 0, 64, 0)).outcome());
    }

    // ------------------------------------------------------------------
    // Restart round-trip
    // ------------------------------------------------------------------

    @Test
    void restartRoundTripPreservesEverything() throws Exception {
        service = new RpgService(db, encounters(wanderingSoul(0L)), clock::get,
                this::roll);
        awaitVal(service.discover(helper, "some_flag", Discovery.SOURCE_ADMIN));
        TrackingSession session = castTracking(caster, target);
        Encounter encounter = awaitVal(service.beginEncounter("wandering_soul",
                "world", 10, 70, 20)).payload();

        // "Restart": a fresh DatabaseService + service over the same file.
        db.shutdown();
        db = newService();
        RpgService restarted = new RpgService(db, encounters(wanderingSoul(0L)),
                clock::get, this::roll);

        assertTrue(awaitVal(restarted.hasDiscovery(helper, "some_flag")));
        List<TrackingSession> sessions = awaitVal(restarted.activeSessions(caster));
        assertEquals(1, sessions.size());
        assertEquals(session.sessionId(), sessions.get(0).sessionId());
        assertEquals(Precision.REGION, sessions.get(0).precision());
        Encounter survived = awaitVal(restarted.encounter(encounter.encounterId()))
                .orElseThrow();
        assertEquals(EncounterState.ACTIVE, survived.state());
        // And the restarted service keeps working.
        assertEquals(RpgOutcome.ENCOUNTER_COMPLETED, awaitVal(restarted
                .completeEncounter(survived.encounterId(), helper)).outcome());
        assertTrue(awaitVal(restarted.hasDiscovery(helper, "ritual_soul_communion")));
    }
}
