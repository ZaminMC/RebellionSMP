package net.zaminmc.rebellion.core.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MigrationRunner behavior against a real SQLite database: idempotency,
 * checksum verification, downgrade detection, gap validation and rollback.
 */
final class MigrationRunnerTest {

    private Connection connect(Path dbFile) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    @Test
    void appliesMigrationsAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("migrations.sqlite");
        MigrationRunner runner = new MigrationRunner();
        runner.register("teams", List.of(
                new Migration(1, "create teams table", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_teams_teams ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "name TEXT NOT NULL UNIQUE)")),
                new Migration(2, "add members table", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_teams_members ("
                                + "team_id INTEGER NOT NULL, "
                                + "player_uuid TEXT NOT NULL, "
                                + "UNIQUE (team_id, player_uuid))"))));

        try (Connection con = connect(db)) {
            runner.applyAll(con, true);
            // Second run must be a no-op, not an error.
            runner.applyAll(con, true);

            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO rb_teams_teams (name) VALUES ('alpha')");
                st.execute("INSERT INTO rb_teams_members (team_id, player_uuid) VALUES (1, 'u1')");
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT version, description FROM rb_schema_migrations "
                                 + "WHERE module_key = 'teams' ORDER BY version")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("create teams table", rs.getString(2));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertFalse(rs.next());
            }
            assertEquals(2, runner.appliedVersions(con).get("teams"));
        }
    }

    @Test
    void checksumMismatchFailsClosed(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("checksum.sqlite");
        MigrationRunner original = new MigrationRunner();
        original.register("core", List.of(
                new Migration(1, "initial", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_core_t (k TEXT PRIMARY KEY)"))));
        try (Connection con = connect(db)) {
            original.applyAll(con, true);
        }

        MigrationRunner tampered = new MigrationRunner();
        tampered.register("core", List.of(
                new Migration(1, "initial", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_core_t (k TEXT PRIMARY KEY, v TEXT)"))));
        try (Connection con = connect(db)) {
            MigrationException e = assertThrows(MigrationException.class,
                    () -> tampered.applyAll(con, true));
            assertTrue(e.issues().get(0).contains("checksum"));
        }
    }

    @Test
    void downgradeIsRefused(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("downgrade.sqlite");
        MigrationRunner newer = new MigrationRunner();
        newer.register("dragons", List.of(
                new Migration(1, "v1", List.of("CREATE TABLE IF NOT EXISTS rb_dragons_a (k TEXT)")),
                new Migration(2, "v2", List.of("CREATE TABLE IF NOT EXISTS rb_dragons_b (k TEXT)"))));
        try (Connection con = connect(db)) {
            newer.applyAll(con, true);
        }

        MigrationRunner older = new MigrationRunner();
        older.register("dragons", List.of(
                new Migration(1, "v1", List.of("CREATE TABLE IF NOT EXISTS rb_dragons_a (k TEXT)"))));
        try (Connection con = connect(db)) {
            MigrationException e = assertThrows(MigrationException.class,
                    () -> older.applyAll(con, true));
            assertTrue(e.issues().get(0).contains("refusing to run an older build"));
        }
    }

    @Test
    void gapInVersionNumbersIsRejectedAtRegistration() {
        MigrationRunner runner = new MigrationRunner();
        runner.register("items", List.of(
                new Migration(1, "v1", List.of("CREATE TABLE IF NOT EXISTS rb_items_a (k TEXT)"))));
        assertThrows(IllegalArgumentException.class, () -> runner.register("items", List.of(
                new Migration(3, "v3", List.of("CREATE TABLE IF NOT EXISTS rb_items_b (k TEXT)")))));
    }

    @Test
    void failedMigrationRollsBackAndFailsClosed(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("rollback.sqlite");
        MigrationRunner runner = new MigrationRunner();
        // Valid statement, then an invalid one on purpose: the whole migration must
        // roll back on SQLite (transactional DDL), leaving v1 unrecorded and the
        // table absent.
        runner.register("wards", List.of(
                new Migration(1, "broken", List.of(
                        "CREATE TABLE IF NOT EXISTS rb_wards_t (k TEXT PRIMARY KEY)",
                        "CREATE TABLE definitely-broken-sql("))));
        try (Connection con = connect(db)) {
            assertThrows(MigrationException.class, () -> runner.applyAll(con, true));
            try (Statement st = con.createStatement()) {
                SQLException followUp = assertThrows(SQLException.class,
                        () -> st.executeQuery("SELECT * FROM rb_wards_t"));
                assertNotNull(followUp); // table was never committed
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM rb_schema_migrations WHERE module_key='wards'")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void multipleModulesMigrateIndependently(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("modules.sqlite");
        MigrationRunner runner = new MigrationRunner();
        runner.register("a", List.of(
                new Migration(1, "a1", List.of("CREATE TABLE IF NOT EXISTS rb_a_t (k TEXT)"))));
        runner.register("b", List.of(
                new Migration(1, "b1", List.of("CREATE TABLE IF NOT EXISTS rb_b_t (k TEXT)")),
                new Migration(2, "b2", List.of("CREATE TABLE IF NOT EXISTS rb_b_t2 (k TEXT)"))));
        try (Connection con = connect(db)) {
            runner.applyAll(con, true);
            var versions = runner.appliedVersions(con);
            assertEquals(1, versions.get("a"));
            assertEquals(2, versions.get("b"));
            assertEquals(List.of("a", "b"), runner.moduleKeys());
        }
    }

    @Test
    void emptyFileDatabaseIsCreatedOnDemand(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("fresh.sqlite");
        assertTrue(Files.notExists(db));
        MigrationRunner runner = new MigrationRunner();
        runner.register("core", List.of(
                new Migration(1, "v1", List.of("CREATE TABLE IF NOT EXISTS rb_core_t (k TEXT)"))));
        try (Connection con = connect(db)) {
            runner.applyAll(con, true);
        }
        assertTrue(Files.exists(db));
    }
}
