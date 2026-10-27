package net.zaminmc.rebellion.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.database.DatabaseBackend;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.ResultSetWork;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.api.database.TransactionWork;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;

/**
 * Central persistence service: one HikariCP pool plus one bounded async executor
 * shared by every module. Implements the {@link RebellionDatabase} contract.
 *
 * <h2>Startup gate</h2>
 * Construction is cheap; {@link #bootstrap()} runs pool initialization, SQLite
 * pragmas and schema migrations on the persistence executor and completes
 * {@link #ready()}. Feature modules bound to this service wait on
 * {@link #awaitReady(int)} during their enable phase.
 *
 * <h2>Backpressure</h2>
 * The executor queue is bounded. When saturated — or after shutdown — submitted
 * work is rejected with <em>no side effects</em> and the returned future fails
 * with {@link FailureCode#PERSISTENCE_OVERLOADED} / {@code SERVICE_UNAVAILABLE}.
 * Callers creating unique assets must treat rejection as fail-closed.
 */
public final class DatabaseService implements RebellionDatabase {

    private final DatabaseConfig config;
    private final Logger logger;
    private final DiagnosticsService diagnostics;
    private final MigrationRunner migrationRunner = new MigrationRunner();
    private volatile Map<String, Integer> appliedVersionsSnapshot = Map.of();

    private final ThreadPoolExecutor executor;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private volatile HikariDataSource dataSource;
    private volatile boolean healthy;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    public DatabaseService(DatabaseConfig config, Path dataDirectory, Logger logger,
                           DiagnosticsService diagnostics) {
        this.config = config;
        this.logger = logger;
        this.diagnostics = diagnostics;

        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                config.executorPoolSize(), config.executorPoolSize(),
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.executorQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "Rebellion-Persistence");
                    t.setDaemon(false);
                    return t;
                });
        ex.allowCoreThreadTimeOut(true);
        this.executor = ex;

        // Resolve the SQLite file inside the module data directory at construction
        // so jdbcUrl() always points at an absolute, validated path.
        if (config.backend() == DatabaseConfig.Backend.SQLITE) {
            this.sqliteFile = dataDirectory.resolve(config.sqliteFile()).toAbsolutePath();
        } else {
            this.sqliteFile = null;
        }
    }

    private final Path sqliteFile;

    // ------------------------------------------------------------------
    // Migration registration
    // ------------------------------------------------------------------

    /** Registers a module's migrations. Must run before {@link #bootstrap()}. */
    public void registerMigrations(String moduleKey, List<Migration> moduleMigrations) {
        migrationRunner.register(moduleKey, moduleMigrations);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Completes when pool init, pragmas and migrations succeeded. */
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** Blocks (bounded) until bootstrap finished; used by feature modules at enable. */
    public void awaitReady(int timeoutSeconds) {
        try {
            ready.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RebellionException(FailureCode.SERVICE_UNAVAILABLE,
                    "database bootstrap did not finish within " + timeoutSeconds + "s; refusing to enable");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RebellionException(FailureCode.PERSISTENCE_FAILURE,
                    "database bootstrap failed: " + rootMessage(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RebellionException(FailureCode.SERVICE_UNAVAILABLE,
                    "interrupted while waiting for the database bootstrap");
        }
    }

    /** Initializes the pool, applies pragmas and runs migrations asynchronously. */
    public CompletableFuture<Void> bootstrap() {
        submit("bootstrap", con -> {
            // SQLite: enable WAL once; it persists in the database file.
            if (config.backend() == DatabaseConfig.Backend.SQLITE) {
                try (Statement st = con.createStatement()) {
                    try (ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
                        if (rs.next()) {
                            String mode = rs.getString(1);
                            if (!"wal".equalsIgnoreCase(mode)) {
                                logger.warning("SQLite journal mode is '" + mode + "' instead of WAL.");
                            }
                        }
                    }
                }
            }
            migrationRunner.applyAll(con, config.strictChecksums());
            Map<String, Integer> applied = migrationRunner.appliedVersions(con);
            appliedVersionsSnapshot = Map.copyOf(applied);
            applied.forEach((module, version) ->
                    logger.info("Schema '" + module + "' at migration version " + version + "."));
            return null;
        }).whenComplete((v, err) -> {
            if (err != null) {
                healthy = false;
                ready.completeExceptionally(err);
            } else {
                healthy = true;
                ready.complete(null);
            }
        });
        return ready;
    }

    /**
     * Orderly shutdown: stop accepting work, flush pending tasks within the
     * bound, then close the pool. Anything that did not flush is logged.
     */
    public void shutdown() {
        healthy = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                List<Runnable> dropped = executor.shutdownNow();
                if (!dropped.isEmpty()) {
                    String incident = diagnostics.incident();
                    logger.severe("Persistence executor did not flush within 10s; dropped "
                            + dropped.size() + " queued operations. Incident " + incident);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        HikariDataSource ds = dataSource;
        if (ds != null) {
            ds.close();
            dataSource = null;
        }
    }

    // ------------------------------------------------------------------
    // RebellionDatabase
    // ------------------------------------------------------------------

    @Override
    public DatabaseBackend backend() {
        return config.backend() == DatabaseConfig.Backend.MYSQL
                ? DatabaseBackend.MYSQL : DatabaseBackend.SQLITE;
    }

    @Override
    public boolean healthy() {
        return healthy && dataSource != null && !dataSource.isClosed();
    }

    @Override
    public CompletableFuture<Integer> update(String sql, Object... params) {
        return submit(sql, con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                Sql.bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public <T> CompletableFuture<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return submit(sql, con -> executeQuery(con, sql, params, rs -> {
            List<T> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(mapper.mapRow(rs));
            }
            return List.copyOf(out);
        }));
    }

    @Override
    public <T> CompletableFuture<Optional<T>> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return submit(sql, con -> executeQuery(con, sql, params, rs -> {
            if (rs.next()) {
                return Optional.of(mapper.mapRow(rs));
            }
            return Optional.<T>empty();
        }));
    }

    @Override
    public <T> CompletableFuture<T> queryRaw(String sql, ResultSetWork<T> work, Object... params) {
        return submit(sql, con -> executeQuery(con, sql, params, work::apply));
    }

    @Override
    public <T> CompletableFuture<T> transaction(TransactionWork<T> work) {
        return submit("transaction", con -> {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                T result = work.apply(new Context(con));
                con.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    con.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
        });
    }

    @Override
    public CompletableFuture<Void> applyModuleMigrations(
            String moduleKey, List<net.zaminmc.rebellion.api.database.MigrationSpec> migrations) {
        if (moduleKey == null || moduleKey.isBlank() || !moduleKey.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException(
                    "module key must match [a-z][a-z0-9_]{0,31}: " + moduleKey);
        }
        List<Migration> converted = migrations.stream()
                .map(spec -> new Migration(spec.version(), spec.description(), spec.statements()))
                .toList();
        // Fail-closed on a failed/failed-over bootstrap; bounded wait so a
        // caller that never bootstrapped cannot hang forever.
        CompletableFuture<Void> bootstrapGate = ready().orTimeout(60, TimeUnit.SECONDS);
        return bootstrapGate.thenCompose(v -> submit("migrations:" + moduleKey, con -> {
            migrationRunner.applyModule(con, moduleKey, converted, config.strictChecksums());
            Map<String, Integer> applied = migrationRunner.appliedVersions(con);
            appliedVersionsSnapshot = Map.copyOf(applied);
            int version = applied.getOrDefault(moduleKey, 0);
            logger.info("Schema '" + moduleKey + "' at migration version " + version + ".");
            return null;
        }));
    }

    // ------------------------------------------------------------------
    // Metrics (used by /rebellion status and diagnostics)
    // ------------------------------------------------------------------

    public int queuedCount() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public long completedCount() {
        return executor.getCompletedTaskCount();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Snapshot of applied migration versions per module; refreshed at bootstrap. */
    public Map<String, Integer> appliedMigrationVersions() {
        return appliedVersionsSnapshot;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private HikariDataSource pool() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = dataSource;
                if (ds == null) {
                    ds = buildPool();
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    private HikariDataSource buildPool() {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("Rebellion-DB");
        int maxPool = config.effectiveMaxPoolSize();
        int minIdle = config.effectiveMinIdle();
        hc.setMaximumPoolSize(maxPool);
        hc.setMinimumIdle(minIdle);
        hc.setConnectionTimeout(config.connectionTimeoutMs());
        hc.setMaxLifetime(config.maxLifetimeMs());
        hc.setKeepaliveTime(0);

        if (config.backend() == DatabaseConfig.Backend.SQLITE) {
            File f = sqliteFile.toFile();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new RebellionException(FailureCode.PERSISTENCE_FAILURE,
                        "cannot create database directory " + parent);
            }
            hc.setJdbcUrl("jdbc:sqlite:" + f.getAbsolutePath());
            // Per-connection pragmas (single statement only; see docs/DATA_MODEL.md).
            hc.setConnectionInitSql("PRAGMA foreign_keys = ON");
            hc.setConnectionTestQuery(null); // JDBC4 isValid() is supported by sqlite-jdbc
        } else {
            hc.setJdbcUrl(config.jdbcUrl());
            hc.setUsername(config.mysqlUsername());
            hc.setPassword(config.mysqlPassword());
        }
        return new HikariDataSource(hc);
    }

    private <T> CompletableFuture<T> submit(String opName, SqlWork<T> work) {
        submitted.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            if (Thread.currentThread().isInterrupted()) {
                future.completeExceptionally(new RebellionException(
                        FailureCode.SERVICE_UNAVAILABLE, "persistence worker interrupted"));
                return;
            }
            try (Connection con = pool().getConnection()) {
                T result = work.apply(con);
                future.complete(result);
            } catch (SQLException e) {
                future.completeExceptionally(wrap(opName, e));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            String incident = diagnostics == null ? null : diagnostics.incident();
            future.completeExceptionally(new RebellionException(
                    FailureCode.PERSISTENCE_OVERLOADED,
                    "persistence queue saturated (" + config.executorQueueCapacity()
                            + " pending); operation '" + safeOp(opName) + "' rejected with no side effects",
                    incident));
            if (diagnostics != null) {
                diagnostics.warn(incident, "persistence queue rejected operation '" + safeOp(opName) + "'");
            }
        }
        return future;
    }

    private String safeOp(String opName) {
        // Statement text can contain long SQL; keep incident summaries short.
        return opName.length() > 64 ? opName.substring(0, 61) + "…" : opName;
    }

    private <T> T executeQuery(Connection con, String sql, Object[] params, ResultSetWork<T> work)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Sql.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return work.apply(rs);
            }
        }
    }

    private RuntimeException wrap(String opName, SQLException e) {
        String incident = diagnostics == null ? null : diagnostics.incident();
        if (diagnostics != null) {
            diagnostics.error(incident, "database operation failed: " + safeOp(opName), e);
        }
        return new RebellionException(FailureCode.PERSISTENCE_FAILURE,
                "database operation failed (" + safeOp(opName) + "): " + e.getMessage(), incident, e);
    }

    private static String rootMessage(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** TransactionContext over the transaction's connection. */
    private record Context(Connection connection) implements TransactionContext {

        @Override
        public int update(String sql, Object... params) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Sql.bind(ps, params);
                return ps.executeUpdate();
            }
        }

        @Override
        public <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Sql.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new java.util.ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.mapRow(rs));
                    }
                    return List.copyOf(out);
                }
            }
        }

        @Override
        public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Sql.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapper.mapRow(rs)) : Optional.empty();
                }
            }
        }
    }

}
