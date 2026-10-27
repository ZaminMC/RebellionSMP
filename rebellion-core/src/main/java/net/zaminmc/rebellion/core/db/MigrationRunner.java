package net.zaminmc.rebellion.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Namespaced, versioned schema migrations with re-run protection, checksum
 * verification and downgrade detection.
 *
 * <p>Each module registers its own ordered migration set under a module key
 * (e.g. {@code core}, {@code teams}). Versions must start at 1 and be gap-free;
 * this keeps history readable and makes a missing migration obvious. Applied
 * versions and checksums are recorded in {@code rb_schema_migrations} (one
 * shared table, keyed by module) inside the same transaction as the migration
 * itself on SQLite. On MySQL, DDL statements implicitly commit per statement —
 * the runner still records the applied version only after every statement of a
 * migration succeeded, and refuses to re-run a partially applied version
 * (documented in docs/DATA_MODEL.md).</p>
 */
public final class MigrationRunner {

    /** moduleKey -> (version -> migration) */
    private final Map<String, TreeMap<Integer, Migration>> registered = new LinkedHashMap<>();

    /** Registers a module's migration set; validates ordering and gap-freedom. */
    public void register(String moduleKey, List<Migration> migrations) {
        TreeMap<Integer, Migration> set = registered.computeIfAbsent(moduleKey, k -> new TreeMap<>());
        int expected = set.isEmpty() ? 1 : set.lastKey() + 1;
        for (Migration m : migrations) {
            if (m.version() != expected) {
                throw new IllegalArgumentException(
                        "Migration set '" + moduleKey + "' is not gap-free: expected version "
                                + expected + " next, got " + m.version());
            }
            set.put(m.version(), m);
            expected++;
        }
    }

    /** @return highest registered version for the module (0 when none). */
    public int latestRegisteredVersion(String moduleKey) {
        TreeMap<Integer, Migration> set = registered.get(moduleKey);
        return set == null ? 0 : set.lastKey();
    }

    /** @return registered modules in registration order. */
    public List<String> moduleKeys() {
        return List.copyOf(registered.keySet());
    }

    /**
     * Ensures the registry table exists, verifies checksums and applies pending
     * migrations for every registered module.
     *
     * @throws MigrationException fail-closed: leave the database untouched for
     *         the failing module and report every issue found
     */
    public synchronized void applyAll(Connection connection, boolean strictChecksums) throws MigrationException {
        List<String> issues = new ArrayList<>();
        try {
            ensureRegistryTable(connection);
        } catch (SQLException e) {
            throw new MigrationException(List.of(
                    "cannot create rb_schema_migrations: " + e.getMessage()));
        }

        try {
            Map<String, Map<Integer, Applied>> applied = loadApplied(connection);

            for (Map.Entry<String, TreeMap<Integer, Migration>> entry : registered.entrySet()) {
                String moduleKey = entry.getKey();
                Map<Integer, Applied> moduleApplied =
                        applied.computeIfAbsent(moduleKey, k -> new HashMap<>());

                // Downgrade detection: database is ahead of the shipped code.
                int dbMax = moduleApplied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
                int codeMax = entry.getValue().lastKey();
                if (dbMax > codeMax) {
                    issues.add("module '" + moduleKey + "': database schema is at version " + dbMax
                            + " but this build only ships " + codeMax
                            + " — refusing to run an older build against a newer schema");
                    continue;
                }

                // Checksum verification of already-applied migrations.
                if (strictChecksums) {
                    for (Map.Entry<Integer, Applied> a : moduleApplied.entrySet()) {
                        Migration m = entry.getValue().get(a.getKey());
                        if (m != null && !m.checksum().equals(a.getValue().checksum())) {
                            issues.add("module '" + moduleKey + "' migration " + a.getKey()
                                    + " checksum changed after being applied — refusing to continue;"
                                    + " restore the original migration or extend it with a new version");
                        }
                    }
                    if (!issues.isEmpty()) {
                        continue;
                    }
                }

                // Apply pending in order.
                for (Migration m : entry.getValue().values()) {
                    if (moduleApplied.containsKey(m.version())) {
                        continue;
                    }
                    applyOne(connection, moduleKey, m);
                }
            }
        } catch (SQLException e) {
            issues.add("unexpected database error during migration: " + e.getMessage());
        }

        if (!issues.isEmpty()) {
            throw new MigrationException(issues);
        }
    }

    private void applyOne(Connection connection, String moduleKey, Migration m) throws SQLException, MigrationException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement st = connection.createStatement()) {
                for (String sql : m.statements()) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rb_schema_migrations (module_key, version, description, checksum, applied_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, moduleKey);
                ps.setInt(2, m.version());
                ps.setString(3, m.description());
                ps.setString(4, m.checksum());
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new MigrationException(List.of(
                    "module '" + moduleKey + "' migration " + m.version()
                            + " (" + m.description() + ") failed: " + e.getMessage()));
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void ensureRegistryTable(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS rb_schema_migrations ("
                    + "module_key VARCHAR(64) NOT NULL, "
                    + "version INT NOT NULL, "
                    + "description VARCHAR(255) NOT NULL, "
                    + "checksum CHAR(64) NOT NULL, "
                    + "applied_at_utc BIGINT NOT NULL, "
                    + "PRIMARY KEY (module_key, version))");
        }
    }

    private record Applied(String description, String checksum, long appliedAt) {
    }

    private Map<String, Map<Integer, Applied>> loadApplied(Connection connection) throws SQLException {
        Map<String, Map<Integer, Applied>> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT module_key, version, description, checksum, applied_at_utc FROM rb_schema_migrations");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                        .put(rs.getInt(2), new Applied(rs.getString(3), rs.getString(4), rs.getLong(5)));
            }
        }
        return out;
    }

    /**
     * @return applied version per module for diagnostics. Must be called after
     *         {@link #applyAll(Connection, boolean)} succeeded.
     */
    public Map<String, Integer> appliedVersions(Connection connection) throws SQLException {
        Map<String, Integer> out = new TreeMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT module_key, MAX(version) FROM rb_schema_migrations GROUP BY module_key");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }
}
