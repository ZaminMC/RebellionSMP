package net.zaminmc.rebellion.prison.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.zaminmc.rebellion.api.economy.EconomyResult;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.economy.EconomyConfig;
import net.zaminmc.rebellion.economy.EconomyService;
import net.zaminmc.rebellion.economy.db.EconomySchema;
import net.zaminmc.rebellion.prison.FeeRecord;
import net.zaminmc.rebellion.prison.FeeState;
import net.zaminmc.rebellion.prison.PlayerProfile;
import net.zaminmc.rebellion.prison.PrisonConfig;
import net.zaminmc.rebellion.prison.PrisonConfig.DeathPolicy;
import net.zaminmc.rebellion.prison.PrisonConfig.RehabTask;
import net.zaminmc.rebellion.prison.PrisonOutcome;
import net.zaminmc.rebellion.prison.PrisonResult;
import net.zaminmc.rebellion.prison.PrisonService;
import net.zaminmc.rebellion.prison.ReleaseReason;
import net.zaminmc.rebellion.prison.Sentence;
import net.zaminmc.rebellion.prison.SentenceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PrisonService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0021 evidence set — with the REAL
 * rebellion-economy service injected as the {@code EconomyProvider}: the
 * cross-module fee contract is proven over both modules' actual
 * persistence, not a fake.
 *
 * <p>Every §5.8/§5.9 persistence invariant is exercised for real: death
 * idempotency (double-delivery no-op), score accumulation with the
 * write-clamped scar ceiling, all three routing policies, the structural
 * one-live-sentence guard (death in prison), the PENDING→JAILED CAS with
 * idempotent confirms, the frozen-quote fee protocol (insufficient funds
 * keeps the quote reusable, the mid-protocol crash reconciles through the
 * debit's idempotency key, the all-or-none commit), non-paid releases
 * voiding quotes while debt survives, structurally idempotent rehab with
 * clamped healing, and the restart round-trip.</p>
 */
final class PrisonServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private PrisonService service;
    private EconomyService economy;
    private PrisonConfig config;

    /** Mutable service clock: deterministic timestamps. */
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private final UUID player = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        config = PrisonConfig.defaults();
        economy = new EconomyService(db, EconomyConfig.defaults(), clock::get);
        service = new PrisonService(db, config, clock::get);
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    private DatabaseService newService() throws Exception {
        DatabaseService fresh = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(fresh.bootstrap());
        await(fresh.applyModuleMigrations("economy", EconomySchema.migrations()));
        await(fresh.applyModuleMigrations("prison", PrisonSchema.migrations()));
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

    /** Imprisons the player through the real death path, then confirms. */
    private Sentence imprison(UUID who, String deathKey) {
        PrisonResult<PrisonService.DeathCase> death = awaitVal(service.applyDeath(
                who, deathKey));
        assertTrue(death.success(), death::toString);
        assertEquals(PrisonOutcome.SENTENCE_PENDING, death.payload().sentenceOutcome());
        PrisonResult<Sentence> confirmed = awaitVal(service.confirmJailed(who));
        assertTrue(confirmed.success(), confirmed::toString);
        return confirmed.payload();
    }

    /** Config with the routing policy replaced. */
    private void withPolicy(DeathPolicy death) {
        config = new PrisonConfig(death, config.release(), config.rehab(),
                config.anchor());
        service = new PrisonService(db, config, clock::get);
    }

    // ------------------------------------------------------------------
    // Death processing
    // ------------------------------------------------------------------

    @Test
    void deathIsIdempotentPerKey() {
        PrisonResult<PrisonService.DeathCase> first = awaitVal(service.applyDeath(
                player, "death:tick:100"));
        assertEquals(PrisonOutcome.DEATH_APPLIED, first.outcome());
        assertTrue(first.mutated());

        // §5.8: death and respawn both firing, or a relog re-delivery —
        // the same key resolves as a no-op, never a second deduction.
        PrisonResult<PrisonService.DeathCase> replay = awaitVal(service.applyDeath(
                player, "death:tick:100"));
        assertEquals(PrisonOutcome.DEATH_NOOP, replay.outcome());
        assertFalse(replay.mutated());
        assertTrue(replay.success());
        assertEquals(1L, replay.payload().profile().deathScore());
        assertEquals(2, replay.payload().profile().scarPoints());
    }

    @Test
    void deathKeysAreNamespacedPerPlayer() {
        UUID other = UUID.randomUUID();
        awaitVal(service.applyDeath(player, "death:tick:100"));
        PrisonResult<PrisonService.DeathCase> theirs = awaitVal(service.applyDeath(
                other, "death:tick:100"));
        assertEquals(PrisonOutcome.DEATH_APPLIED, theirs.outcome());
        assertEquals(1L, theirs.payload().profile().deathScore());
    }

    @Test
    void scarsClampAtTheCeilingButScoreGrowsForever() {
        for (int i = 1; i <= 10; i++) {
            PrisonResult<PrisonService.DeathCase> death = awaitVal(service.applyDeath(
                    player, "death:tick:" + i));
            assertTrue(death.success(), death::toString);
        }
        PlayerProfile profile = awaitVal(service.profile(player));
        assertEquals(10L, profile.deathScore());
        // ceiling = 20 - 6 = 14 points; 10 deaths x 2 points would be 20.
        assertEquals(14, profile.scarPoints());
        assertEquals(14, profile.activePenalty(14));
    }

    @Test
    void deathInPrisonScoresButNeverDoubleJails() {
        imprison(player, "death:tick:1");

        PrisonResult<PrisonService.DeathCase> inPrison = awaitVal(service.applyDeath(
                player, "death:tick:2"));
        assertEquals(PrisonOutcome.DEATH_APPLIED, inPrison.outcome());
        assertEquals(PrisonOutcome.SENTENCE_EXISTS, inPrison.payload().sentenceOutcome());
        assertEquals(2L, inPrison.payload().profile().deathScore());

        // Still exactly one live sentence (the structural live guard).
        Sentence live = awaitVal(service.liveSentence(player));
        assertNotNull(live);
        assertEquals(SentenceState.JAILED, live.state());
        assertEquals(1L, awaitVal(db.queryOne(
                "SELECT COUNT(*) FROM rb_prison_sentences WHERE live_guard = ?",
                rs -> rs.getLong(1), player)).orElse(0L));
    }

    @Test
    void routingNeverScoresWithoutJailing() {
        withPolicy(new DeathPolicy(1L, 2, 6, 20, DeathPolicy.Route.NEVER, 3L));
        PrisonResult<PrisonService.DeathCase> death = awaitVal(service.applyDeath(
                player, "death:tick:1"));
        assertEquals(PrisonOutcome.ROUTE_DISABLED, death.payload().sentenceOutcome());
        assertNull(awaitVal(service.liveSentence(player)));
        assertEquals(1L, death.payload().profile().deathScore());
    }

    @Test
    void routingThresholdJailsOnlyAtTheConfiguredScore() {
        withPolicy(new DeathPolicy(1L, 2, 6, 20,
                DeathPolicy.Route.SCORE_THRESHOLD, 3L));
        assertEquals(PrisonOutcome.SCORE_BELOW_THRESHOLD,
                awaitVal(service.applyDeath(player, "death:tick:1")).payload()
                        .sentenceOutcome());
        assertEquals(PrisonOutcome.SCORE_BELOW_THRESHOLD,
                awaitVal(service.applyDeath(player, "death:tick:2")).payload()
                        .sentenceOutcome());
        assertNull(awaitVal(service.liveSentence(player)));

        PrisonResult<PrisonService.DeathCase> third = awaitVal(service.applyDeath(
                player, "death:tick:3"));
        assertEquals(PrisonOutcome.SENTENCE_PENDING, third.payload().sentenceOutcome());
        assertEquals(SentenceState.PENDING, awaitVal(service.liveSentence(player)).state());
    }

    // ------------------------------------------------------------------
    // Sentence lifecycle
    // ------------------------------------------------------------------

    @Test
    void confirmJailedIsIdempotent() {
        awaitVal(service.applyDeath(player, "death:tick:1"));
        assertEquals(PrisonOutcome.JAILED,
                awaitVal(service.confirmJailed(player)).outcome());
        PrisonResult<Sentence> again = awaitVal(service.confirmJailed(player));
        assertEquals(PrisonOutcome.JAILED_NOOP, again.outcome());
        assertFalse(again.mutated());
        assertTrue(again.success());
    }

    @Test
    void confirmWithoutASentenceRefuses() {
        PrisonResult<Sentence> none = awaitVal(service.confirmJailed(player));
        assertEquals(PrisonOutcome.NO_SENTENCE, none.outcome());
        assertFalse(none.success());
    }

    @Test
    void revertPendingRequiresTheStaffActorAndRecords() {
        awaitVal(service.applyDeath(player, "death:tick:1"));
        PrisonResult<Sentence> anonymous = awaitVal(service.revertPending(player,
                null, "stuck"));
        assertEquals(PrisonOutcome.ADMIN_ACTOR_REQUIRED, anonymous.outcome());

        PrisonResult<Sentence> reverted = awaitVal(service.revertPending(player,
                UUID.randomUUID(), "world missing, fail-closed recovery"));
        assertEquals(PrisonOutcome.RELEASED, reverted.outcome());
        assertEquals(ReleaseReason.ADMIN_OVERRIDE, reverted.payload().releaseReason());
        assertNull(awaitVal(service.liveSentence(player)));
        // The override event recorded the actor.
        assertEquals(1L, awaitVal(db.queryOne(
                "SELECT COUNT(*) FROM rb_prison_events WHERE type = 'SENTENCE'"
                + " AND outcome = 'ADMIN_OVERRIDE'", rs -> rs.getLong(1))).orElse(0L));
    }

    @Test
    void releasedHistorySurvivesAndReleasesFreeTheSlot() {
        imprison(player, "death:tick:1");
        awaitVal(service.markReleased(player, ReleaseReason.RESCUE,
                UUID.randomUUID(), "external rescue"));
        assertNull(awaitVal(service.liveSentence(player)));

        // A fresh death books a NEW sentence while the released row survives.
        awaitVal(service.applyDeath(player, "death:tick:2"));
        assertEquals(SentenceState.PENDING, awaitVal(service.liveSentence(player)).state());
        assertEquals(2L, awaitVal(db.queryOne(
                "SELECT COUNT(*) FROM rb_prison_sentences WHERE player_uuid = ?",
                rs -> rs.getLong(1), player)).orElse(0L));
    }

    // ------------------------------------------------------------------
    // Release fee protocol
    // ------------------------------------------------------------------

    @Test
    void paidReleaseDebitsThroughTheSharedProviderAndCommitsAllOrNone() {
        Sentence jailed = imprison(player, "death:tick:1");
        economy.credit(player, 100, "grant:wallet", "admin.grant");

        PrisonResult<FeeRecord> paid = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.PAID_RELEASED, paid.outcome());
        assertTrue(paid.mutated());

        // fee = score(1) x 1.00 x 5 = 5 minor units (frozen audit).
        FeeRecord fee = paid.payload();
        assertEquals(5L, fee.amountMinor());
        assertEquals(FeeState.PAID, fee.state());
        assertEquals("rebellion-economy", fee.provider());
        assertNotNull(fee.ledgerEntry());
        assertEquals(95L, economy.balance(player));

        assertEquals(1L, awaitVal(db.queryOne(
                "SELECT COUNT(*) FROM rb_prison_sentences WHERE sentence_id = ?"
                + " AND state = 'RELEASED' AND release_reason = 'PAID'",
                rs -> rs.getLong(1), jailed.sentenceId())).orElse(0L));
        assertNull(awaitVal(service.liveSentence(player)));
    }

    @Test
    void insufficientFundsKeepsTheFrozenQuoteReusable() {
        imprison(player, "death:tick:1");

        PrisonResult<FeeRecord> refused = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.INSUFFICIENT_FUNDS, refused.outcome());
        assertFalse(refused.success());
        assertEquals(FeeState.QUOTED, refused.payload().state());

        // The player dies again in prison: the live score grows, but the
        // FROZEN quote must not drift when the payment is retried.
        awaitVal(service.applyDeath(player, "death:tick:2"));
        economy.credit(player, 100, "grant:wallet", "admin.grant");

        PrisonResult<FeeRecord> retry = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.PAID_RELEASED, retry.outcome());
        assertEquals(refused.payload().feeId(), retry.payload().feeId());
        // Still the frozen 5, not the recomputed 10.
        assertEquals(5L, retry.payload().amountMinor());
        assertEquals(95L, economy.balance(player));
    }

    @Test
    void crashBetweenDebitAndCommitReconcilesThroughTheDebitKey() {
        imprison(player, "death:tick:1");

        // Freeze the quote via a failed attempt (empty account), then fund
        // the account and simulate the crash window: the debit committed,
        // the prison commit did not.
        PrisonResult<FeeRecord> refused = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.INSUFFICIENT_FUNDS, refused.outcome());
        economy.credit(player, 100, "grant:wallet", "admin.grant");
        EconomyResult crashedDebit = economy.debit(player,
                refused.payload().amountMinor(),
                "prison-fee:" + refused.payload().feeId(), "prison.release-fee");
        assertTrue(crashedDebit.success());

        // The retry resolves the debit as a no-op and finishes the commit
        // with the ORIGINAL entry id — money moved exactly once.
        PrisonResult<FeeRecord> reconciled = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.PAID_RELEASED, reconciled.outcome());
        assertEquals(crashedDebit.entryId(), reconciled.payload().ledgerEntry());
        assertEquals(95L, economy.balance(player));
        assertNull(awaitVal(service.liveSentence(player)));
    }

    @Test
    void payAfterAReleaseFindsNoSentence() {
        imprison(player, "death:tick:1");
        economy.credit(player, 100, "grant:wallet", "admin.grant");
        assertEquals(PrisonOutcome.PAID_RELEASED,
                service.payRelease(player, economy).outcome());

        // The sentence is released; there is nothing left to pay. The
        // race-window PAID_NOOP branch is the same read answered under
        // concurrency — this is its settled end state.
        PrisonResult<FeeRecord> again = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.NO_SENTENCE, again.outcome());
        assertFalse(again.success());
        assertEquals(95L, economy.balance(player));
    }

    @Test
    void payReleaseRefusesPendingAndSentencelessPlayers() {
        assertEquals(PrisonOutcome.NO_SENTENCE,
                service.payRelease(player, economy).outcome());

        awaitVal(service.applyDeath(player, "death:tick:1"));
        assertEquals(PrisonOutcome.NOT_JAILED,
                service.payRelease(player, economy).outcome());
    }

    // ------------------------------------------------------------------
    // Non-paid releases
    // ------------------------------------------------------------------

    @Test
    void escapeReleaseVoidsTheQuoteAndKeepsTheDebt() {
        imprison(player, "death:tick:1");
        PrisonResult<FeeRecord> refused = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.INSUFFICIENT_FUNDS, refused.outcome());

        PrisonResult<Sentence> escaped = awaitVal(service.markReleased(player,
                ReleaseReason.ESCAPE, null, "guards defeated"));
        assertEquals(PrisonOutcome.RELEASED, escaped.outcome());
        assertEquals(ReleaseReason.ESCAPE, escaped.payload().releaseReason());

        // The frozen quote died with the sentence; the debt survived.
        UUID escapedSentence = escaped.payload().sentenceId();
        assertEquals(FeeState.VOID, awaitVal(db.transaction(tx ->
                PrisonStore.feeBySentence(tx, escapedSentence).orElseThrow())).state());
        assertEquals(1L, awaitVal(service.profile(player)).deathScore());
    }

    @Test
    void adminOverrideReleaseRequiresTheActor() {
        imprison(player, "death:tick:1");
        assertEquals(PrisonOutcome.ADMIN_ACTOR_REQUIRED,
                awaitVal(service.markReleased(player, ReleaseReason.ADMIN_OVERRIDE,
                        null, null)).outcome());
        PrisonResult<Sentence> override = awaitVal(service.markReleased(player,
                ReleaseReason.ADMIN_OVERRIDE, UUID.randomUUID(), "appealed"));
        assertEquals(PrisonOutcome.RELEASED, override.outcome());
        assertEquals(ReleaseReason.ADMIN_OVERRIDE, override.payload().releaseReason());
    }

    @Test
    void paidReasonCannotBypassTheFeeProtocol() {
        imprison(player, "death:tick:1");
        PrisonResult<Sentence> refused = awaitVal(service.markReleased(player,
                ReleaseReason.PAID, UUID.randomUUID(), "bypass"));
        assertEquals(PrisonOutcome.ARGUMENT_INVALID, refused.outcome());
        assertEquals(SentenceState.JAILED, awaitVal(service.liveSentence(player)).state());
    }

    // ------------------------------------------------------------------
    // Rehabilitation
    // ------------------------------------------------------------------

    @Test
    void rehabIsIdempotentClampedAndCapped() {
        for (int i = 1; i <= 7; i++) {
            awaitVal(service.applyDeath(player, "death:tick:" + i));
        }
        // 14 scar points; convalescence_rest heals 2 x max 7.
        for (int i = 1; i <= 7; i++) {
            PrisonResult<PlayerProfile> done = awaitVal(service.completeRehab(
                    player, "convalescence_rest", "rest:camp:" + i));
            assertEquals(PrisonOutcome.REHAB_COMPLETED, done.outcome());
        }
        PlayerProfile healed = awaitVal(service.profile(player));
        assertEquals(14, healed.restoredPoints());
        assertEquals(0, healed.remainingScars());

        // The 8th occurrence is refused.
        assertEquals(PrisonOutcome.REHAB_EXHAUSTED,
                awaitVal(service.completeRehab(player, "convalescence_rest",
                        "rest:camp:8")).outcome());

        // Big heals clamp to what is left (nothing).
        assertEquals(PrisonOutcome.SCARS_FULLY_RESTORED,
                awaitVal(service.completeRehab(player, "field_medicine",
                        "med:1")).outcome());

        // Replay of a recorded completion is a no-op carrying the profile.
        PrisonResult<PlayerProfile> replay = awaitVal(service.completeRehab(
                player, "convalescence_rest", "rest:camp:3"));
        assertEquals(PrisonOutcome.REHAB_NOOP, replay.outcome());
        assertFalse(replay.mutated());
    }

    @Test
    void rehabHealingNeverExceedsTheRemainingScars() {
        awaitVal(service.applyDeath(player, "death:tick:1")); // 2 points
        PrisonResult<PlayerProfile> big = awaitVal(service.completeRehab(
                player, "field_medicine", "med:1")); // heals min(6, 2) = 2
        assertEquals(PrisonOutcome.REHAB_COMPLETED, big.outcome());
        assertEquals(2, big.payload().restoredPoints());
        assertEquals(0, big.payload().remainingScars());
    }

    @Test
    void rehabRefusesUnknownAndDisabledTasks() {
        assertEquals(PrisonOutcome.REHAB_UNKNOWN_TASK,
                awaitVal(service.completeRehab(player, "nope", "k:1")).outcome());
        PrisonConfig disabled = new PrisonConfig(config.death(), config.release(),
                Map.of("rest", new RehabTask("rest", false, 2, 1)),
                config.anchor());
        service = new PrisonService(db, disabled, clock::get);
        assertEquals(PrisonOutcome.REHAB_DISABLED,
                awaitVal(service.completeRehab(player, "rest", "k:1")).outcome());
    }

    // ------------------------------------------------------------------
    // Reads + restart round-trip
    // ------------------------------------------------------------------

    @Test
    void pendingSweepReadsLivePendingSentences() {
        awaitVal(service.applyDeath(player, "death:tick:1"));
        UUID other = UUID.randomUUID();
        withPolicy(new DeathPolicy(1L, 0, 6, 20, DeathPolicy.Route.NEVER, 3L));
        awaitVal(service.applyDeath(other, "death:tick:1"));
        assertEquals(1, awaitVal(service.pendingSentences()).size());
    }

    @Test
    void stateSurvivesARestart() throws Exception {
        imprison(player, "death:tick:1");
        PrisonResult<FeeRecord> refused = service.payRelease(player, economy);
        assertEquals(PrisonOutcome.INSUFFICIENT_FUNDS, refused.outcome());
        UUID frozenFee = refused.payload().feeId();
        UUID sentenceId = awaitVal(service.liveSentence(player)).sentenceId();
        db.shutdown();

        db = newService();
        economy = new EconomyService(db, EconomyConfig.defaults(), clock::get);
        service = new PrisonService(db, config, clock::get);

        assertEquals(SentenceState.JAILED, awaitVal(service.liveSentence(player)).state());
        assertEquals(1L, awaitVal(service.profile(player)).deathScore());
        FeeRecord fee = awaitVal(db.transaction(tx -> PrisonStore.feeBySentence(tx,
                sentenceId).orElseThrow()));
        assertEquals(FeeState.QUOTED, fee.state());
        assertEquals(frozenFee, fee.feeId());

        // The frozen quote still pays after the restart.
        economy.credit(player, 100, "grant:wallet", "admin.grant");
        assertEquals(PrisonOutcome.PAID_RELEASED,
                service.payRelease(player, economy).outcome());
    }
}
