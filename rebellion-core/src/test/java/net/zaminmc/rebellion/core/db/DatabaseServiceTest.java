package net.zaminmc.rebellion.core.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link DatabaseService} against a real SQLite file:
 * bootstrap, CRUD, transactions with rollback, UUID/Boolean binding, bounded
 * queue backpressure and shutdown flush semantics.
 */
final class DatabaseServiceTest {

    private DiagnosticsService diagnostics(@TempDir Path tmp) {
        CoreConfig config = new CoreConfig(1, false, 30, "diagnostics", 5, 50);
        return new DiagnosticsService(config, tmp, java.util.logging.Logger.getLogger("test"));
    }

    private DatabaseConfig sqliteConfig(Path dataDir) {
        return new DatabaseConfig(
                DatabaseConfig.Backend.SQLITE, "rebellion.sqlite",
                "localhost", 3306, "rebellion", "user", "",
                Map.of(), 10, 2, 5_000, 1_800_000,
                2, 2000, true);
    }

    private static void await(CompletableFuture<?> f) {
        try {
            f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed: " + e, e);
        }
    }

    @Test
    void bootstrapCreatesSchemaAndRunsRegisteredMigrations(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("core", List.of(
                new Migration(1, "core baseline", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_core_meta ("
                                + "k TEXT PRIMARY KEY, v TEXT NOT NULL)"))));
        await(db.bootstrap());
        assertTrue(db.healthy());
        assertEquals(Map.of("core", 1), db.appliedMigrationVersions());

        await(db.update("INSERT INTO rb_core_meta (k, v) VALUES (?, ?)", "schema", "v1"));
        var value = db.queryOne("SELECT v FROM rb_core_meta WHERE k = ?",
                rs -> rs.getString(1), "schema");
        await(value);
        assertEquals(java.util.Optional.of("v1"), value.join());

        db.shutdown();
        assertFalse(db.healthy());
    }

    @Test
    void bootstrapFailureSurfacesThroughReadyFuture(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("broken", List.of(
                new Migration(1, "invalid", List.of("CREATE TABLE not valid sql ("))));
        CompletableFuture<Void> ready = db.bootstrap();
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> ready.get(20, TimeUnit.SECONDS));
        assertNotNull(e.getCause());
        assertTrue(e.getCause() instanceof MigrationException);
        db.shutdown();
    }

    @Test
    void transactionCommitsAndRollsBackAtomically(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("t", List.of(
                new Migration(1, "accounts", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_t_accounts ("
                                + "id TEXT PRIMARY KEY, balance INTEGER NOT NULL)"))));
        await(db.bootstrap());

        // Commit path: reserve + spend in one transaction.
        var committed = db.transaction(tx -> {
            tx.update("INSERT INTO rb_t_accounts (id, balance) VALUES (?, ?)", "a1", 100);
            tx.update("UPDATE rb_t_accounts SET balance = balance - ? WHERE id = ?", 40, "a1");
            return tx.one("SELECT balance FROM rb_t_accounts WHERE id = ?", rs -> rs.getInt(1), "a1");
        });
        await(committed);
        assertEquals(java.util.Optional.of(60), committed.join());

        // Rollback path: exception undoes both statements.
        var rolled = db.transaction(tx -> {
            tx.update("UPDATE rb_t_accounts SET balance = balance - ? WHERE id = ?", 60, "a1");
            throw new IllegalStateException("simulated failure after mutation");
        });
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> rolled.get(20, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IllegalStateException);
        var after = db.queryOne("SELECT balance FROM rb_t_accounts WHERE id = ?",
                rs -> rs.getInt(1), "a1");
        await(after);
        assertEquals(java.util.Optional.of(60), after.join()); // unchanged

        db.shutdown();
    }

    @Test
    void uuidAndBooleanParametersBindCorrectly(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        UUID id = UUID.randomUUID();
        db.registerMigrations("bind", List.of(
                new Migration(1, "flags", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_bind_flags ("
                                + "uuid TEXT PRIMARY KEY, flag INTEGER NOT NULL)"))));
        await(db.bootstrap());

        await(db.update("INSERT INTO rb_bind_flags (uuid, flag) VALUES (?, ?)", id, Boolean.TRUE));
        var found = db.queryOne("SELECT uuid, flag FROM rb_bind_flags WHERE uuid = ?",
                rs -> rs.getString(1) + ":" + rs.getInt(2), id);
        await(found);
        assertEquals(java.util.Optional.of(id + ":1"), found.join());

        // Unsupported parameter types are rejected before touching the database.
        var bad = db.update("INSERT INTO rb_bind_flags (uuid, flag) VALUES (?, ?)",
                id, java.math.BigDecimal.ONE);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> bad.get(20, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IllegalArgumentException);
        db.shutdown();
    }

    @Test
    void boundedQueueRejectsWithoutSideEffects(@TempDir Path tmp) throws Exception {
        DatabaseConfig tight = new DatabaseConfig(
                DatabaseConfig.Backend.SQLITE, "rebellion.sqlite",
                "localhost", 3306, "rebellion", "user", "",
                Map.of(), 1, 1, 5_000, 1_800_000,
                1, 1, true); // pool 1 thread, queue capacity 1
        DatabaseService db = new DatabaseService(tight, tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("q", List.of(
                new Migration(1, "q", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_q_t (k TEXT PRIMARY KEY)"))));
        await(db.bootstrap());

        CountDownLatch release = new CountDownLatch(1);
        // Deterministic start signal: the test must not submit follow-up work until
        // the single worker is provably executing the blocker, otherwise the
        // submissions race with task pickup and the rejection count is flaky.
        CountDownLatch started = new CountDownLatch(1);
        // Task 1 occupies the single worker; it must hold until released.
        CompletableFuture<Void> blocker = db.transaction(tx -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while blocked", ie);
            }
            return null;
        });
        assertTrue(started.await(20, TimeUnit.SECONDS), "blocker must start on the worker");
        // Task 2 fills the queue; task 3 must be rejected.
        CompletableFuture<Integer> queued = db.update(
                "INSERT INTO rb_q_t (k) VALUES ('queued')");
        CompletableFuture<Integer> overflow = db.update(
                "INSERT INTO rb_q_t (k) VALUES ('overflow')");

        await(overflow.handle((v, err) -> {
            assertNotNull(err, "third submission must fail");
            Throwable cause = err instanceof java.util.concurrent.CompletionException
                    && err.getCause() != null ? err.getCause() : err;
            assertTrue(cause instanceof RebellionException, "got " + cause);
            assertEquals(FailureCode.PERSISTENCE_OVERLOADED,
                    ((RebellionException) cause).code());
            assertNotNull(((RebellionException) cause).incidentId());
            return null;
        }));
        assertEquals(1, db.rejectedCount());

        release.countDown();
        await(blocker);
        await(queued); // the queued task still runs — it was accepted
        db.shutdown();
    }

    @Test
    void shutdownFlushesPendingWork(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("flush", List.of(
                new Migration(1, "flush", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_flush_t ("
                                + "k TEXT PRIMARY KEY, v TEXT NOT NULL)"))));
        await(db.bootstrap());

        for (int i = 0; i < 20; i++) {
            db.update("INSERT INTO rb_flush_t (k, v) VALUES (?, ?)", "k" + i, "v" + i);
        }
        db.shutdown();

        // Reopen the same file: every accepted write must be durable.
        DatabaseService reopened = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        reopened.registerMigrations("flush", List.of(
                new Migration(1, "flush", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_flush_t ("
                                + "k TEXT PRIMARY KEY, v TEXT NOT NULL)"))));
        await(reopened.bootstrap());
        var count = reopened.queryRaw("SELECT COUNT(*) FROM rb_flush_t", rs -> {
            rs.next();
            return rs.getInt(1);
        });
        await(count);
        assertEquals(20, count.join());
        reopened.shutdown();
    }

    @Test
    void workSubmittedAfterShutdownFailsCleanly(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("s", List.of(
                new Migration(1, "s", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_s_t (k TEXT PRIMARY KEY)"))));
        await(db.bootstrap());
        db.shutdown();

        CompletableFuture<Integer> late = db.update(
                "INSERT INTO rb_s_t (k) VALUES ('late')");
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> late.get(20, TimeUnit.SECONDS));
        Throwable cause = e.getCause();
        assertTrue(cause instanceof RebellionException, "got " + cause);
        assertEquals(FailureCode.PERSISTENCE_OVERLOADED, ((RebellionException) cause).code());
    }

    @Test
    void concurrentUniqueInsertsNeverDuplicate(@TempDir Path tmp) {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        db.registerMigrations("race", List.of(
                new Migration(1, "race", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_race_claims ("
                                + "claim_key TEXT PRIMARY KEY, owner TEXT NOT NULL)"))));
        await(db.bootstrap());

        // Simultaneous double-claim of the same key: exactly one insert wins,
        // the loser receives a persistence failure it can treat as fail-closed.
        String key = "artifact-overworld";
        CompletableFuture<Integer> first = db.transaction(tx ->
                tx.update("INSERT INTO rb_race_claims (claim_key, owner) VALUES (?, ?)", key, "alice"));
        CompletableFuture<Integer> second = db.transaction(tx ->
                tx.update("INSERT INTO rb_race_claims (claim_key, owner) VALUES (?, ?)", key, "bob"));

        CompletableFuture.allOf(first, second).handle((v, err) -> null).join();
        int winners = 0;
        for (CompletableFuture<Integer> f : List.of(first, second)) {
            if (!f.isCompletedExceptionally()) {
                winners += f.join();
            } else {
                assertTrue(f.handle((v, e) -> e != null).join());
            }
        }
        assertEquals(1, winners, "exactly one claim must succeed");

        var count = db.queryRaw("SELECT COUNT(*) FROM rb_race_claims", rs -> {
            rs.next();
            return rs.getInt(1);
        });
        await(count);
        assertEquals(1, count.join());
        db.shutdown();
    }

    @Test
    void awaitReadyTimesOutBounded(@TempDir Path tmp) throws Exception {
        DatabaseService db = new DatabaseService(sqliteConfig(tmp), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics(tmp));
        CountDownLatch release = new CountDownLatch(1);
        // Never bootstrap: simulate an extremely slow pool by blocking inside a
        // transaction on the bootstrap path is complex; instead verify the
        // timeout contract directly against an unfinished future.
        long start = System.nanoTime();
        // Note: ready() is uncompleted here on purpose.
        RebellionException e = assertThrows(RebellionException.class,
                () -> db.awaitReady(1));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 900);
        assertEquals(FailureCode.SERVICE_UNAVAILABLE, e.code());
        release.countDown();
        db.shutdown();
    }
}
