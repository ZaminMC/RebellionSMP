package net.zaminmc.rebellion.api.database;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async, bounded access to the shared Rebellion database. Implemented by
 * RebellionCore and registered with Bukkit's ServicesManager; feature modules
 * consume it through {@code RebellionApi.require(RebellionDatabase.class)}.
 *
 * <h2>Threading contract</h2>
 * <ul>
 *   <li>All operations run on the core persistence executor — never the main
 *       server thread and never the caller's thread.</li>
 *   <li>Futures complete on a persistence executor thread. Any follow-up world,
 *       player or entity interaction must be marshalled back to the main server
 *       scheduler by the caller.</li>
 *   <li>The work queue is bounded; when saturated, the returned future completes
 *       exceptionally with {@link net.zaminmc.rebellion.api.result.FailureCode#PERSISTENCE_OVERLOADED}
 *       and <em>no side effects occur</em>. Unique asset creation must treat this
 *       as fail-closed.</li>
 * </ul>
 *
 * <h2>Parameter binding rules</h2>
 * <p>Bind parameters are applied with {@code setObject} after normalizing:
 * {@code UUID} is stored as its string form, {@code Boolean} as 0/1. Supported
 * types: {@code null}, {@code String}, {@code UUID}, {@code Boolean},
 * {@code Integer}, {@code Long}, {@code Double}, {@code Float}, {@code byte[]}.
 * Other types throw {@link IllegalArgumentException} before reaching the
 * database, keeping statements predictable across SQLite and MySQL.</p>
 *
 * <h2>SQL rules</h2>
 * <p>Statements must be constants or safely composed fragments — never string
 * concatenation with player-provided values; values always travel through bind
 * parameters.</p>
 */
public interface RebellionDatabase {

    /** @return the configured backend. */
    DatabaseBackend backend();

    /** @return true when the pool is open and the last connectivity check passed. */
    boolean healthy();

    /** Async DML/DDL; completes with the affected row count. */
    CompletableFuture<Integer> update(String sql, Object... params);

    /** Async query mapping every row. Completes with an immutable list. */
    <T> CompletableFuture<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);

    /** Async query mapping the first row when present. */
    <T> CompletableFuture<Optional<T>> queryOne(String sql, RowMapper<T> mapper, Object... params);

    /**
     * Async full-control read over the raw result set. Use sparingly —
     * {@link #queryList} and {@link #queryOne} cover normal access.
     */
    <T> CompletableFuture<T> queryRaw(String sql, ResultSetWork<T> work, Object... params);

    /**
     * Runs the work inside a transaction: commit on normal return, rollback on
     * any exception. Use for every state change that spans rows.
     */
    <T> CompletableFuture<T> transaction(TransactionWork<T> work);

    /**
     * Late, namespaced module migrations (ADR-0005). Feature modules call
     * this exactly once at their enable, before their first database access.
     *
     * <p>Waits for the Core database bootstrap (fail-closed if it failed),
     * then — on the bounded persistence executor — registers the module's
     * set, verifies checksums of already-applied versions (when strict
     * checksums are enabled), refuses downgrades and applies pending
     * versions in order. Idempotent per module key. On saturation the future
     * fails with {@code PERSISTENCE_OVERLOADED} and nothing is applied.</p>
     *
     * @param moduleKey stable schema key, e.g. {@code "teams"}
     * @param migrations gap-free set starting at version 1
     */
    CompletableFuture<Void> applyModuleMigrations(String moduleKey, List<MigrationSpec> migrations);
}
