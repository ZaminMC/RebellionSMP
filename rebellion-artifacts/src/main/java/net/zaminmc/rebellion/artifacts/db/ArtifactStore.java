package net.zaminmc.rebellion.artifacts.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactState;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Artifact SQL surface (schema v1, ADR-0013). Statements are constants; every
 * mutation runs inside a caller-supplied transaction. State transitions are
 * <strong>compare-and-set</strong> statements: the {@code WHERE} clause pins
 * the expected prior state plus the expected holder/live instance stamp, so
 * races, cloned items and duplicate event delivery resolve through the
 * affected-row count — one winner, typed no-ops/refusals for everyone else.
 * Only {@link #seedInsert} can create a registry row (the idempotent
 * load-time seed); everything else is an UPDATE.
 */
public final class ArtifactStore {

    private ArtifactStore() {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    /** Full registry row; nullable columns surface as null/Optional. */
    public record ArtifactRow(ArtifactKey artifactKey, UUID artifactId,
            ArtifactState state, UUID holder, UUID instance, String originWorld,
            int originX, int originY, int originZ, long holderSinceUtc,
            long stateSinceUtc, long createdAtUtc, long updatedAtUtc) {

        public Artifact toDomain() {
            return new Artifact(artifactKey, artifactId, state, holder, instance,
                    originWorld, originX, originY, originZ, holderSinceUtc,
                    stateSinceUtc, createdAtUtc, updatedAtUtc);
        }
    }

    public static final RowMapper<ArtifactRow> ARTIFACT = rs -> new ArtifactRow(
            ArtifactKey.parse(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            ArtifactState.parse(rs.getString(3)),
            rs.getString(4) == null ? null : UUID.fromString(rs.getString(4)),
            UUID.fromString(rs.getString(5)),
            rs.getString(6),
            rs.getInt(7),
            rs.getInt(8),
            rs.getInt(9),
            rs.getLong(10),
            rs.getLong(11),
            rs.getLong(12),
            rs.getLong(13));

    /** Audit row (admin forensics; written inside mutation transactions). */
    public record AuditRow(UUID id, ArtifactKey artifactKey, String action,
            UUID actor, String detail, long atUtc) {
    }

    public static final RowMapper<AuditRow> AUDIT = rs -> new AuditRow(
            UUID.fromString(rs.getString(1)),
            ArtifactKey.parse(rs.getString(2)),
            rs.getString(3),
            rs.getString(4) == null ? null : UUID.fromString(rs.getString(4)),
            rs.getString(5),
            rs.getLong(6));

    public static final RowMapper<RebellionEvent> EVENT = rs -> new RebellionEvent(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            rs.getString(3),
            RebellionEvent.State.parse(rs.getString(4)),
            rs.getLong(5),
            rs.getObject(6) == null ? null : rs.getLong(6));

    static final RowMapper<Long> COUNT = rs -> rs.getLong(1);

    /** Guard row: the active event id (null = no active occurrence). */
    public record GuardRow(@Nullable UUID activeEventId) {
    }

    public static final RowMapper<GuardRow> GUARD = rs -> new GuardRow(
            rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)));

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    static final String ARTIFACT_COLUMNS = "artifact_key, artifact_id, state, holder, "
            + "instance, origin_world, origin_x, origin_y, origin_z, holder_since_utc, "
            + "state_since_utc, created_at_utc, updated_at_utc";

    /** Seed-only insert; the PK backstop makes re-seeding a caught no-op. */
    public static final String SEED_INSERT =
            "INSERT INTO rb_artifacts_registry (artifact_key, artifact_id, state, "
                    + "holder, instance, origin_world, origin_x, origin_y, origin_z, "
                    + "holder_since_utc, state_since_utc, created_at_utc, updated_at_utc) "
                    + "VALUES (?, ?, 'ORIGIN', NULL, ?, ?, ?, ?, ?, 0, ?, ?, ?)";

    /** Config-owned anchor sync — touches no gameplay columns. */
    public static final String SYNC_ORIGIN =
            "UPDATE rb_artifacts_registry SET origin_world = ?, origin_x = ?, "
                    + "origin_y = ?, origin_z = ? WHERE artifact_key = ?";

    public static final String SELECT_BY_KEY = "SELECT " + ARTIFACT_COLUMNS
            + " FROM rb_artifacts_registry WHERE artifact_key = ?";

    /** Live-instance resolution: only presentable states resolve (ADR-0013
     *  §2 — superseded copies in RETURNING/SPENT are inert by design). */
    public static final String SELECT_BY_INSTANCE = "SELECT " + ARTIFACT_COLUMNS
            + " FROM rb_artifacts_registry WHERE instance = ? "
            + "AND state IN ('ORIGIN', 'HELD', 'LOST')";

    public static final String SELECT_ALL = "SELECT " + ARTIFACT_COLUMNS
            + " FROM rb_artifacts_registry ORDER BY artifact_key";

    static final String COUNT_ALL = "SELECT COUNT(*) FROM rb_artifacts_registry";

    /** Capture CAS: only the live origin instance can leave the realm. */
    public static final String CAS_CAPTURE =
            "UPDATE rb_artifacts_registry SET state = 'HELD', holder = ?, instance = ?, "
                    + "holder_since_utc = ?, state_since_utc = ?, updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'ORIGIN' AND instance = ?";

    /** Transfer CAS: possession moves only from the recorded holder with the
     *  live stamp (state stays HELD; state_since is untouched). */
    public static final String CAS_TRANSFER =
            "UPDATE rb_artifacts_registry SET holder = ?, instance = ?, "
                    + "holder_since_utc = ?, updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'HELD' AND holder = ? "
                    + "AND instance = ?";

    /** Loss CAS: a held artifact parks in the recoverable state, keeping the
     *  last rightful holder on the row. */
    public static final String CAS_LOST =
            "UPDATE rb_artifacts_registry SET state = 'LOST', state_since_utc = ?, "
                    + "updated_at_utc = ? WHERE artifact_key = ? AND state = 'HELD'";

    /** Find CAS: a lost artifact is restored by presenting its live stamp. */
    public static final String CAS_FOUND =
            "UPDATE rb_artifacts_registry SET state = 'HELD', holder = ?, instance = ?, "
                    + "holder_since_utc = ?, state_since_utc = ?, updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'LOST' AND instance = ?";

    /** Voluntary-return CAS: the holder surrenders the live instance
     *  (holder stamps cleared). */
    public static final String CAS_VOLUNTARY_RETURN =
            "UPDATE rb_artifacts_registry SET state = 'RETURNING', holder = NULL, "
                    + "holder_since_utc = 0, instance = ?, state_since_utc = ?, "
                    + "updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'HELD' AND holder = ? "
                    + "AND instance = ?";

    /** Return-sweep CAS: LOST/SPENT rows only, delay elapsed (state_since is
     *  pinned against the caller-computed eligibility floor; holder stamps
     *  cleared). */
    public static final String CAS_BEGIN_RETURN =
            "UPDATE rb_artifacts_registry SET state = 'RETURNING', holder = NULL, "
                    + "holder_since_utc = 0, instance = ?, state_since_utc = ?, "
                    + "updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state IN ('LOST', 'SPENT') "
                    + "AND state_since_utc <= ?";

    /** Arrival CAS: only an in-transit row can land at its origin. */
    public static final String CAS_COMPLETE_RETURN =
            "UPDATE rb_artifacts_registry SET state = 'ORIGIN', instance = ?, "
                    + "state_since_utc = ?, updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'RETURNING'";

    /** Admin route CAS: only the stuck states; an ORIGIN artifact must be
     *  conquered through its encounter, an HELD one is not the admin's to move. */
    public static final String CAS_ROUTE =
            "UPDATE rb_artifacts_registry SET state = 'HELD', holder = ?, instance = ?, "
                    + "holder_since_utc = ?, state_since_utc = ?, updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state IN ('LOST', 'RETURNING')";

    /** Spend CAS (one of three, same transaction): only the trigger holder's
     *  live-held artifact can be spent by the overpowering action (holder
     *  stamps cleared). */
    public static final String CAS_SPEND =
            "UPDATE rb_artifacts_registry SET state = 'SPENT', holder = NULL, "
                    + "holder_since_utc = 0, instance = ?, state_since_utc = ?, "
                    + "updated_at_utc = ? "
                    + "WHERE artifact_key = ? AND state = 'HELD' AND holder = ?";

    public static final String INSERT_AUDIT = "INSERT INTO rb_artifacts_audit "
            + "(id, artifact_key, action, actor, detail, at_utc) VALUES (?, ?, ?, ?, ?, ?)";

    public static final String SELECT_AUDIT_BY_KEY = "SELECT id, artifact_key, action, "
            + "actor, detail, at_utc FROM rb_artifacts_audit WHERE artifact_key = ? "
            + "ORDER BY at_utc";

    public static final String INSERT_EVENT = "INSERT INTO rb_artifacts_events "
            + "(event_id, trigger_holder, reason, state, initiated_at_utc, completed_at_utc) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, NULL)";

    public static final String SELECT_EVENT_BY_ID = "SELECT event_id, trigger_holder, "
            + "reason, state, initiated_at_utc, completed_at_utc "
            + "FROM rb_artifacts_events WHERE event_id = ?";

    public static final String SELECT_EVENTS_ALL = "SELECT event_id, trigger_holder, "
            + "reason, state, initiated_at_utc, completed_at_utc "
            + "FROM rb_artifacts_events ORDER BY initiated_at_utc";

    public static final String SELECT_ACTIVE_EVENTS = "SELECT event_id, trigger_holder, "
            + "reason, state, initiated_at_utc, completed_at_utc "
            + "FROM rb_artifacts_events WHERE state = 'ACTIVE'";

    /** Event-completion CAS: only an ACTIVE occurrence can be completed. */
    public static final String CAS_EVENT_COMPLETE =
            "UPDATE rb_artifacts_events SET state = 'COMPLETED', completed_at_utc = ? "
                    + "WHERE event_id = ? AND state = 'ACTIVE'";

    public static final String GUARD_INSERT =
            "INSERT INTO rb_artifacts_event_guard (singleton, active_event_id) "
                    + "VALUES (1, NULL)";

    public static final String GUARD_SELECT =
            "SELECT active_event_id FROM rb_artifacts_event_guard WHERE singleton = 1";

    /** Trigger CAS: exactly one winner per occurrence — everyone else reads
     *  the settled guard and resolves to EVENT_ALREADY_ACTIVE. */
    public static final String GUARD_ACQUIRE =
            "UPDATE rb_artifacts_event_guard SET active_event_id = ? "
                    + "WHERE singleton = 1 AND active_event_id IS NULL";

    /** Completion CAS: releases the guard only from this occurrence. */
    public static final String GUARD_RELEASE =
            "UPDATE rb_artifacts_event_guard SET active_event_id = NULL "
                    + "WHERE singleton = 1 AND active_event_id = ?";

    // ------------------------------------------------------------------
    // Operations (all inside the caller's transaction)
    // ------------------------------------------------------------------

    public static void seedInsert(TransactionContext tx, ArtifactKey key, UUID artifactId,
            UUID instance, String world, int x, int y, int z, long nowUtc)
            throws SQLException {
        tx.update(SEED_INSERT, key.name(), artifactId, instance, world, x, y, z,
                nowUtc, nowUtc, nowUtc);
    }

    public static void syncOrigin(TransactionContext tx, ArtifactKey key, String world,
            int x, int y, int z) throws SQLException {
        tx.update(SYNC_ORIGIN, world, x, y, z, key.name());
    }

    public static Optional<ArtifactRow> byKey(TransactionContext tx, ArtifactKey key)
            throws SQLException {
        return tx.one(SELECT_BY_KEY, ARTIFACT, key.name());
    }

    public static Optional<ArtifactRow> byInstance(TransactionContext tx, UUID instance)
            throws SQLException {
        return tx.one(SELECT_BY_INSTANCE, ARTIFACT, instance);
    }

    public static List<ArtifactRow> all(TransactionContext tx) throws SQLException {
        return tx.list(SELECT_ALL, ARTIFACT);
    }

    public static long countAll(TransactionContext tx) throws SQLException {
        return tx.one(COUNT_ALL, COUNT).orElse(0L);
    }

    public static int capture(TransactionContext tx, ArtifactKey key, UUID discoverer,
            UUID presentedInstance, UUID freshInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_CAPTURE, discoverer, freshInstance, nowUtc, nowUtc,
                nowUtc, key.name(), presentedInstance);
    }

    public static int transfer(TransactionContext tx, ArtifactKey key, UUID fromHolder,
            UUID toHolder, UUID presentedInstance, UUID freshInstance, long nowUtc)
            throws SQLException {
        return tx.update(CAS_TRANSFER, toHolder, freshInstance, nowUtc, nowUtc,
                key.name(), fromHolder, presentedInstance);
    }

    public static int markLost(TransactionContext tx, ArtifactKey key, long nowUtc)
            throws SQLException {
        return tx.update(CAS_LOST, nowUtc, nowUtc, key.name());
    }

    public static int found(TransactionContext tx, ArtifactKey key, UUID finder,
            UUID presentedInstance, UUID freshInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_FOUND, finder, freshInstance, nowUtc, nowUtc, nowUtc,
                key.name(), presentedInstance);
    }

    public static int voluntaryReturn(TransactionContext tx, ArtifactKey key,
            UUID holder, UUID presentedInstance, UUID freshInstance, long nowUtc)
            throws SQLException {
        return tx.update(CAS_VOLUNTARY_RETURN, freshInstance, nowUtc, nowUtc,
                key.name(), holder, presentedInstance);
    }

    public static int beginReturn(TransactionContext tx, ArtifactKey key,
            UUID freshInstance, long eligibilityFloorUtc, long nowUtc)
            throws SQLException {
        return tx.update(CAS_BEGIN_RETURN, freshInstance, nowUtc, nowUtc, key.name(),
                eligibilityFloorUtc);
    }

    public static int completeReturn(TransactionContext tx, ArtifactKey key,
            UUID freshInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_COMPLETE_RETURN, freshInstance, nowUtc, nowUtc, key.name());
    }

    public static int route(TransactionContext tx, ArtifactKey key, UUID target,
            UUID freshInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_ROUTE, target, freshInstance, nowUtc, nowUtc, nowUtc,
                key.name());
    }

    /** Spend compare-and-set for one artifact of the three-artifact set. */
    public static int spend(TransactionContext tx, ArtifactKey key, UUID triggerHolder,
            UUID freshInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_SPEND, freshInstance, nowUtc, nowUtc, key.name(),
                triggerHolder);
    }

    public static void audit(TransactionContext tx, ArtifactKey key, String action,
            UUID actor, String detail, long atUtc) throws SQLException {
        tx.update(INSERT_AUDIT, UUID.randomUUID(), key.name(), action, actor,
                truncate(detail, 255), atUtc);
    }

    public static List<AuditRow> auditTrail(TransactionContext tx, ArtifactKey key)
            throws SQLException {
        return tx.list(SELECT_AUDIT_BY_KEY, AUDIT, key.name());
    }

    public static void insertEvent(TransactionContext tx, UUID eventId, UUID triggerHolder,
            String reason, long initiatedAtUtc) throws SQLException {
        tx.update(INSERT_EVENT, eventId, triggerHolder, truncate(reason, 255),
                initiatedAtUtc);
    }

    public static Optional<RebellionEvent> eventById(TransactionContext tx, UUID eventId)
            throws SQLException {
        return tx.one(SELECT_EVENT_BY_ID, EVENT, eventId);
    }

    public static List<RebellionEvent> eventsAll(TransactionContext tx)
            throws SQLException {
        return tx.list(SELECT_EVENTS_ALL, EVENT);
    }

    public static List<RebellionEvent> activeEvents(TransactionContext tx)
            throws SQLException {
        return tx.list(SELECT_ACTIVE_EVENTS, EVENT);
    }

    public static int completeEvent(TransactionContext tx, UUID eventId, long nowUtc)
            throws SQLException {
        return tx.update(CAS_EVENT_COMPLETE, nowUtc, eventId);
    }

    public static void guardInsert(TransactionContext tx) throws SQLException {
        tx.update(GUARD_INSERT);
    }

    public static Optional<GuardRow> guard(TransactionContext tx) throws SQLException {
        return tx.one(GUARD_SELECT, GUARD);
    }

    public static int guardAcquire(TransactionContext tx, UUID eventId)
            throws SQLException {
        return tx.update(GUARD_ACQUIRE, eventId);
    }

    public static int guardRelease(TransactionContext tx, UUID eventId)
            throws SQLException {
        return tx.update(GUARD_RELEASE, eventId);
    }

    // ------------------------------------------------------------------
    // Constraint classification (SQLite + MySQL dialects)
    // ------------------------------------------------------------------

    /** Detects a unique/PK constraint violation across both dialects. */
    public static boolean isUniqueViolation(SQLException e) {
        if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
            return true;
        }
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("UNIQUE constraint failed") || m.contains("Duplicate entry");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
