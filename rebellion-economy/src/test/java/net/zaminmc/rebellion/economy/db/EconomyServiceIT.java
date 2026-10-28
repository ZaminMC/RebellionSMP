package net.zaminmc.rebellion.economy.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.zaminmc.rebellion.api.economy.EconomyOutcome;
import net.zaminmc.rebellion.api.economy.EconomyResult;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.economy.EconomyConfig;
import net.zaminmc.rebellion.economy.EconomyService;
import net.zaminmc.rebellion.economy.db.EconomyStore.LedgerRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EconomyService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0021 evidence set. Every §5.9 persistence
 * invariant is exercised for real: idempotent credit/debit (replayed keys
 * resolve to typed no-ops carrying the original entry), refusal families
 * (insufficient funds, bounds, argument validation), balance_after audit
 * integrity, the account/ledger all-or-none pairing, the first-mutation
 * race path, and the restart round-trip.
 */
final class EconomyServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private EconomyService service;
    private EconomyConfig config;

    /** Mutable service clock: deterministic timestamps. */
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private final UUID account = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        config = EconomyConfig.defaults();
        service = new EconomyService(db, config, clock::get);
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
                java.util.Map.of(), 10, 2, 5_000, 1_800_000, 2, 2000, true);
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

    // ------------------------------------------------------------------
    // Credit / debit idempotency
    // ------------------------------------------------------------------

    @Test
    void creditIsIdempotentPerKey() {
        UUID first = service.credit(account, 500, "grant:welcome", "admin.grant")
                .entryId();
        EconomyResult replay = service.credit(account, 500, "grant:welcome",
                "admin.grant");

        assertEquals(EconomyOutcome.NOOP_DUPLICATE, replay.outcome());
        assertFalse(replay.mutated());
        assertTrue(replay.success());
        assertEquals(first, replay.entryId());
        assertEquals(500, service.balance(account));
    }

    @Test
    void debitIsIdempotentPerKey() {
        service.credit(account, 1_000, "grant:welcome", "admin.grant");
        UUID first = service.debit(account, 250, "prison-fee:abc", "prison.release-fee")
                .entryId();
        EconomyResult replay = service.debit(account, 250, "prison-fee:abc",
                "prison.release-fee");

        assertEquals(EconomyOutcome.NOOP_DUPLICATE, replay.outcome());
        assertFalse(replay.mutated());
        assertEquals(first, replay.entryId());
        assertEquals(750, service.balance(account));
    }

    @Test
    void freshKeysEachCharge() {
        service.credit(account, 1_000, "grant:welcome", "admin.grant");
        service.debit(account, 250, "prison-fee:one", "prison.release-fee");
        EconomyResult second = service.debit(account, 250, "prison-fee:two",
                "prison.release-fee");

        assertEquals(EconomyOutcome.DEBITED, second.outcome());
        assertTrue(second.mutated());
        assertEquals(500, service.balance(account));
        assertNotEquals(second.entryId(), service.debit(account, 250,
                "prison-fee:one", "prison.release-fee").entryId());
    }

    // ------------------------------------------------------------------
    // Refusal families (nothing changes)
    // ------------------------------------------------------------------

    @Test
    void debitRefusesInsufficientFunds() {
        service.credit(account, 100, "grant:welcome", "admin.grant");
        EconomyResult refused = service.debit(account, 101, "prison-fee:big",
                "prison.release-fee");

        assertEquals(EconomyOutcome.INSUFFICIENT_FUNDS, refused.outcome());
        assertFalse(refused.mutated());
        assertEquals(100, refused.balanceAfter());
        assertEquals(100, service.balance(account));
        // The refused charge left no ledger row behind.
        assertEquals(0L, awaitVal(db.queryOne(
                "SELECT COUNT(*) FROM rb_economy_ledger WHERE idempotency_key = ?",
                rs -> rs.getLong(1), "prison-fee:big")).orElse(0L));
    }

    @Test
    void creditRefusesAboveTheConfiguredMaximum() {
        // A deliberately tight bound so a legal single credit (below the
        // 2^40 mutation ceiling) still cannot fit twice.
        EconomyConfig tight = new EconomyConfig(config.currency(), 1_000L);
        EconomyService tightService = new EconomyService(db, tight, clock::get);
        tightService.credit(account, 600, "grant:half", "admin.grant");
        EconomyResult refused = tightService.credit(account, 600, "grant:more",
                "admin.grant");

        assertEquals(EconomyOutcome.BOUNDS_EXCEEDED, refused.outcome());
        assertEquals(600, tightService.balance(account));
    }

    @Test
    void zeroAndOversizedAmountsAreRefused() {
        assertEquals(EconomyOutcome.AMOUNT_INVALID,
                service.credit(account, 0, "grant:zero", "admin.grant").outcome());
        assertEquals(EconomyOutcome.AMOUNT_INVALID,
                service.debit(account, -5, "grant:neg", "admin.grant").outcome());
        assertEquals(EconomyOutcome.AMOUNT_INVALID,
                service.credit(account, EconomyService.MAX_AMOUNT + 1, "grant:over",
                        "admin.grant").outcome());
        assertEquals(0, service.balance(account));
    }

    @Test
    void badKeysAndReasonsAreRefused() {
        assertEquals(EconomyOutcome.ARGUMENT_INVALID,
                service.credit(account, 10, "UPPER KEY", "admin.grant").outcome());
        assertEquals(EconomyOutcome.ARGUMENT_INVALID,
                service.credit(account, 10, "grant:x", "Not A Reason").outcome());
        assertEquals(EconomyOutcome.ARGUMENT_INVALID,
                service.credit(account, 10, null, "admin.grant").outcome());
        assertEquals(0, service.balance(account));
    }

    // ------------------------------------------------------------------
    // Audit integrity
    // ------------------------------------------------------------------

    @Test
    void ledgerReconstructsTheBalanceExactly() {
        // Advance the clock per mutation so created_utc ties never make the
        // "newest entry" ambiguous — the balance_after chain is what actually
        // pins the ordering, and distinct timestamps keep display queries
        // deterministic too.
        service.credit(account, 250, "grant:a", "admin.grant");
        clock.incrementAndGet();
        service.credit(account, 250, "grant:b", "admin.grant");
        clock.incrementAndGet();
        service.debit(account, 125, "prison-fee:one", "prison.release-fee");

        // Sum of deltas equals the live balance; every entry pins its own
        // balance_after, so the walk reproduces each historical step.
        long sum = awaitVal(db.queryList(
                "SELECT entry_id, account_uuid, idempotency_key, reason, delta,"
                + " balance_after, created_utc FROM rb_economy_ledger"
                + " WHERE account_uuid = ? ORDER BY created_utc",
                rs -> new Object[] {rs.getLong(5), rs.getLong(6)}, account))
                .stream().mapToLong(r -> (Long) ((Object[]) r)[0]).sum();
        assertEquals(375, sum);
        assertEquals(375, service.balance(account));

        LedgerRow last = awaitVal(db.queryOne(
                "SELECT entry_id, account_uuid, idempotency_key, reason, delta,"
                + " balance_after, created_utc FROM rb_economy_ledger"
                + " WHERE account_uuid = ? ORDER BY created_utc DESC LIMIT 1",
                rs -> new LedgerRow(UUID.fromString(rs.getString(1)),
                        UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)),
                account)).orElseThrow();
        assertEquals(375, last.balanceAfter());
        assertEquals("prison.release-fee", last.reason());
    }

    @Test
    void providerIdIsStableForAuditRows() {
        assertEquals("rebellion-economy", service.providerId());
    }

    // ------------------------------------------------------------------
    // First-mutation race + restart round-trip
    // ------------------------------------------------------------------

    @Test
    void concurrentFirstCreditsBothLands() {
        // Two DIFFERENT keys racing on the same fresh account: both must
        // land (insert-then-accumulate classification) with no lost money.
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> service.credit(account, 100,
                        "grant:race-a", "admin.grant")),
                CompletableFuture.runAsync(() -> service.credit(account, 100,
                        "grant:race-b", "admin.grant")))
                .join();
        assertEquals(200, service.balance(account));
    }

    @Test
    void stateSurvivesARestart() throws Exception {
        service.credit(account, 700, "grant:welcome", "admin.grant");
        service.debit(account, 300, "prison-fee:one", "prison.release-fee");
        db.shutdown();

        db = newService();
        service = new EconomyService(db, config, clock::get);
        assertEquals(400, service.balance(account));
        // The replay still resolves as a no-op after the restart.
        EconomyResult replay = service.debit(account, 300, "prison-fee:one",
                "prison.release-fee");
        assertEquals(EconomyOutcome.NOOP_DUPLICATE, replay.outcome());
        assertEquals(400, service.balance(account));
    }
}
