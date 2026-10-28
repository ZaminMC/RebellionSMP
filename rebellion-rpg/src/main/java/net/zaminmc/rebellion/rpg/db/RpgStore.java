package net.zaminmc.rebellion.rpg.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.rpg.ClaimState;
import net.zaminmc.rebellion.rpg.Discovery;
import net.zaminmc.rebellion.rpg.Encounter;
import net.zaminmc.rebellion.rpg.EncounterState;
import net.zaminmc.rebellion.rpg.PlayerEffect;
import net.zaminmc.rebellion.rpg.Precision;
import net.zaminmc.rebellion.rpg.RitualClaim;
import net.zaminmc.rebellion.rpg.TrackingSession;
import org.jetbrains.annotations.Nullable;

/**
 * RPG SQL surface (schema v1, ADR-0016). Statements are constants; every
 * mutation runs inside a caller-supplied transaction. State transitions
 * are <strong>compare-and-set</strong> statements — the {@code WHERE}
 * clause pins the expected prior state, so races and duplicate event
 * delivery resolve through the affected-row count: one winner, typed
 * no-ops for everyone else. Inserts guarded by primary keys
 * (discoveries, events) resolve idempotency through the constraint.
 */
public final class RpgStore {

    private RpgStore() {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    /** Append-only audit row (written inside mutation transactions). */
    public record EventRow(String eventKey, @Nullable UUID playerUuid, String type,
            String detail, String outcome, long atUtc) {
    }

    public static final RowMapper<Discovery> DISCOVERY = rs -> new Discovery(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            rs.getString(3),
            rs.getLong(4));

    public static final RowMapper<RitualClaim> CLAIM = rs -> new RitualClaim(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            rs.getString(3),
            ClaimState.parse(rs.getString(4)),
            rs.getString(5),
            rs.getLong(6),
            rs.getObject(7) == null ? null : rs.getLong(7));

    public static final RowMapper<TrackingSession> SESSION = rs -> new TrackingSession(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            UUID.fromString(rs.getString(3)),
            UUID.fromString(rs.getString(4)),
            Precision.parse(rs.getString(5)),
            rs.getLong(6),
            rs.getLong(7),
            rs.getObject(8) == null ? null : rs.getLong(8),
            rs.getString(9));

    public static final RowMapper<PlayerEffect> EFFECT = rs -> new PlayerEffect(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            rs.getString(3),
            rs.getLong(4),
            rs.getLong(5),
            rs.getString(6) == null ? null : UUID.fromString(rs.getString(6)));

    public static final RowMapper<Encounter> ENCOUNTER = rs -> new Encounter(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            rs.getString(3),
            rs.getInt(4),
            rs.getInt(5),
            rs.getInt(6),
            EncounterState.parse(rs.getString(7)),
            rs.getString(8) == null ? null : UUID.fromString(rs.getString(8)),
            rs.getLong(9),
            rs.getObject(10) == null ? null : rs.getLong(10),
            rs.getString(11) == null ? null : UUID.fromString(rs.getString(11)));

    public static final RowMapper<EventRow> EVENT = rs -> new EventRow(
            rs.getString(1),
            rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)),
            rs.getString(3),
            rs.getString(4),
            rs.getString(5),
            rs.getLong(6));

    static final RowMapper<Long> COUNT = rs -> rs.getLong(1);

    // ------------------------------------------------------------------
    // Discoveries
    // ------------------------------------------------------------------

    static final String INSERT_DISCOVERY = "INSERT INTO rb_rpg_discoveries "
            + "(player_uuid, discovery_key, source, discovered_utc) VALUES (?, ?, ?, ?)";

    static final String SELECT_DISCOVERY = "SELECT player_uuid, discovery_key, source, "
            + "discovered_utc FROM rb_rpg_discoveries WHERE player_uuid = ? "
            + "AND discovery_key = ?";

    static final String SELECT_DISCOVERIES_FOR = "SELECT player_uuid, discovery_key, "
            + "source, discovered_utc FROM rb_rpg_discoveries WHERE player_uuid = ? "
            + "ORDER BY discovered_utc";

    // ------------------------------------------------------------------
    // Claims
    // ------------------------------------------------------------------

    static final String CLAIM_COLUMNS = "claim_id, player_uuid, ritual_key, state, "
            + "ingredients_digest, created_utc, resolved_utc";

    static final String INSERT_CLAIM = "INSERT INTO rb_rpg_claims (claim_id, "
            + "player_uuid, ritual_key, state, ingredients_digest, created_utc, "
            + "resolved_utc) VALUES (?, ?, ?, 'RESERVED', ?, ?, NULL)";

    static final String SELECT_CLAIM = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_rpg_claims WHERE claim_id = ?";

    static final String SELECT_CLAIMS_FOR = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_rpg_claims WHERE player_uuid = ? ORDER BY created_utc";

    /** Commit CAS: only a RESERVED claim can become COMMITTED. */
    static final String CAS_COMMIT_CLAIM = "UPDATE rb_rpg_claims SET state = "
            + "'COMMITTED', resolved_utc = ? WHERE claim_id = ? AND state = 'RESERVED'";

    /** Release CAS: only a RESERVED claim can become RELEASED. */
    static final String CAS_RELEASE_CLAIM = "UPDATE rb_rpg_claims SET state = "
            + "'RELEASED', resolved_utc = ? WHERE claim_id = ? AND state = 'RESERVED'";

    /** Stale-reservation sweep: RESERVED rows older than the floor. */
    static final String CAS_RELEASE_STALE = "UPDATE rb_rpg_claims SET state = "
            + "'RELEASED', resolved_utc = ? WHERE state = 'RESERVED' AND created_utc <= ?";

    /** Stale-reservation probe (per-claim release events preserve digests). */
    static final String SELECT_STALE = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_rpg_claims WHERE state = 'RESERVED' AND created_utc <= ?"
            + " ORDER BY created_utc";

    /** Rate-limit probe: attempts of ANY state inside the window. */
    static final String COUNT_ATTEMPTS_IN_WINDOW = "SELECT COUNT(*) FROM rb_rpg_claims "
            + "WHERE player_uuid = ? AND ritual_key = ? AND created_utc >= ?";

    /** Cooldown probe: latest COMMITTED attempt time (0 = none). */
    static final String LAST_COMMITTED = "SELECT MAX(created_utc) FROM rb_rpg_claims "
            + "WHERE player_uuid = ? AND ritual_key = ? AND state = 'COMMITTED'";

    /** Single-reservation guard: open reservations for player + ritual. */
    static final String COUNT_RESERVED = "SELECT COUNT(*) FROM rb_rpg_claims "
            + "WHERE player_uuid = ? AND ritual_key = ? AND state = 'RESERVED'";

    // ------------------------------------------------------------------
    // Tracking sessions
    // ------------------------------------------------------------------

    static final String SESSION_COLUMNS = "session_id, claim_id, caster_uuid, "
            + "target_uuid, precision, began_utc, expires_utc, revoked_utc, "
            + "revoke_reason";

    static final String INSERT_SESSION = "INSERT INTO rb_rpg_tracking_sessions "
            + "(session_id, claim_id, caster_uuid, target_uuid, precision, began_utc, "
            + "expires_utc, revoked_utc, revoke_reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)";

    static final String SELECT_SESSION = "SELECT " + SESSION_COLUMNS
            + " FROM rb_rpg_tracking_sessions WHERE session_id = ?";

    static final String SELECT_SESSION_BY_CLAIM = "SELECT " + SESSION_COLUMNS
            + " FROM rb_rpg_tracking_sessions WHERE claim_id = ?";

    /** Unrevoked, unexpired sessions by caster (the resolving set). */
    static final String SELECT_ACTIVE_SESSIONS_FOR = "SELECT " + SESSION_COLUMNS
            + " FROM rb_rpg_tracking_sessions WHERE caster_uuid = ? "
            + "AND revoked_utc IS NULL AND expires_utc > ? ORDER BY began_utc";

    /** Session-cap probe: unrevoked, unexpired count for the caster. */
    static final String COUNT_ACTIVE_SESSIONS_FOR = "SELECT COUNT(*) FROM "
            + "rb_rpg_tracking_sessions WHERE caster_uuid = ? AND revoked_utc IS NULL "
            + "AND expires_utc > ?";

    /** Admin/recovery revocation CAS. */
    static final String CAS_REVOKE_SESSION = "UPDATE rb_rpg_tracking_sessions SET "
            + "revoked_utc = ?, revoke_reason = ? WHERE session_id = ? "
            + "AND revoked_utc IS NULL AND expires_utc > ?";

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------

    static final String INSERT_EFFECT = "INSERT INTO rb_rpg_effects (effect_id, "
            + "player_uuid, effect_key, granted_utc, expires_utc, granted_by) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    /** Active mask probe: any unexpired row for player + key. */
    static final String COUNT_ACTIVE_EFFECTS_FOR = "SELECT COUNT(*) FROM rb_rpg_effects "
            + "WHERE player_uuid = ? AND effect_key = ? AND expires_utc > ?";

    static final String SELECT_EFFECTS_FOR = "SELECT effect_id, player_uuid, effect_key, "
            + "granted_utc, expires_utc, granted_by FROM rb_rpg_effects "
            + "WHERE player_uuid = ? ORDER BY granted_utc";

    // ------------------------------------------------------------------
    // Encounters
    // ------------------------------------------------------------------

    static final String ENCOUNTER_COLUMNS = "encounter_id, encounter_key, world, x, y, "
            + "z, state, soul_entity, began_utc, resolved_utc, completed_by";

    static final String INSERT_ENCOUNTER = "INSERT INTO rb_rpg_encounters "
            + "(encounter_id, encounter_key, world, x, y, z, state, soul_entity, "
            + "began_utc, resolved_utc, completed_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', NULL, ?, NULL, NULL)";

    static final String SELECT_ENCOUNTER = "SELECT " + ENCOUNTER_COLUMNS
            + " FROM rb_rpg_encounters WHERE encounter_id = ?";

    static final String SELECT_ENCOUNTERS_BY_KEY = "SELECT " + ENCOUNTER_COLUMNS
            + " FROM rb_rpg_encounters WHERE encounter_key = ? ORDER BY began_utc";

    static final String SELECT_ENCOUNTERS_BY_STATE = "SELECT " + ENCOUNTER_COLUMNS
            + " FROM rb_rpg_encounters WHERE state = ? ORDER BY began_utc";

    /** Concurrency probe: ACTIVE rows for the key. */
    static final String COUNT_ACTIVE_ENCOUNTERS_FOR = "SELECT COUNT(*) FROM "
            + "rb_rpg_encounters WHERE encounter_key = ? AND state = 'ACTIVE'";

    /** Cooldown probe: latest resolved time for the key (0 = none). */
    static final String LAST_RESOLVED_ENCOUNTER = "SELECT MAX(resolved_utc) FROM "
            + "rb_rpg_encounters WHERE encounter_key = ? "
            + "AND state IN ('COMPLETED', 'EXPIRED', 'DESPAWNED')";

    /** Completion CAS: exactly one helper wins; everyone else no-ops. */
    static final String CAS_COMPLETE_ENCOUNTER = "UPDATE rb_rpg_encounters SET state = "
            + "'COMPLETED', resolved_utc = ?, completed_by = ? WHERE encounter_id = ? "
            + "AND state = 'ACTIVE'";

    /** TTL sweep CAS: only ACTIVE rows past the floor. */
    static final String CAS_EXPIRE_ENCOUNTER = "UPDATE rb_rpg_encounters SET state = "
            + "'EXPIRED', resolved_utc = ? WHERE encounter_id = ? AND state = 'ACTIVE' "
            + "AND began_utc <= ?";

    /** Soul-attach CAS: only an ACTIVE, unattached row. */
    static final String CAS_ATTACH_SOUL = "UPDATE rb_rpg_encounters SET soul_entity = ? "
            + "WHERE encounter_id = ? AND state = 'ACTIVE' AND soul_entity IS NULL";

    /** Admin despawn CAS: only an ACTIVE row (unwedge tool). */
    static final String CAS_DESPAWN_ENCOUNTER = "UPDATE rb_rpg_encounters SET state = "
            + "'DESPAWNED', resolved_utc = ? WHERE encounter_id = ? AND state = 'ACTIVE'";

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    static final String INSERT_EVENT = "INSERT INTO rb_rpg_events (event_key, "
            + "player_uuid, type, detail, outcome, at_utc) VALUES (?, ?, ?, ?, ?, ?)";

    static final String SELECT_EVENT = "SELECT event_key, player_uuid, type, detail, "
            + "outcome, at_utc FROM rb_rpg_events WHERE event_key = ?";

    static final String SELECT_EVENTS_FOR = "SELECT event_key, player_uuid, type, "
            + "detail, outcome, at_utc FROM rb_rpg_events WHERE player_uuid = ? "
            + "ORDER BY at_utc";

    static final String SELECT_EVENTS_ALL = "SELECT event_key, player_uuid, type, "
            + "detail, outcome, at_utc FROM rb_rpg_events ORDER BY at_utc";

    // ------------------------------------------------------------------
    // Operations (all inside the caller's transaction)
    // ------------------------------------------------------------------

    // --- discoveries -----------------------------------------------------

    /** @return 1 when this call created the row, 0 when already known.
     *  The PK-guarded idempotent insert: a duplicate surfaces as a
     *  constraint violation (SQLite + MySQL alike), caught and classified
     *  here so callers treat it as the typed no-op, never an error. */
    public static int insertDiscovery(TransactionContext tx, UUID player, String key,
            String source, long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_DISCOVERY, player, key, source, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    public static Optional<Discovery> discovery(TransactionContext tx, UUID player,
            String key) throws SQLException {
        return tx.one(SELECT_DISCOVERY, DISCOVERY, player, key);
    }

    public static List<Discovery> discoveriesFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_DISCOVERIES_FOR, DISCOVERY, player);
    }

    // --- claims ------------------------------------------------------------

    public static void insertClaim(TransactionContext tx, UUID claimId, UUID player,
            String ritualKey, String digest, long nowUtc) throws SQLException {
        tx.update(INSERT_CLAIM, claimId, player, ritualKey, truncate(digest, 255),
                nowUtc);
    }

    public static Optional<RitualClaim> claim(TransactionContext tx, UUID claimId)
            throws SQLException {
        return tx.one(SELECT_CLAIM, CLAIM, claimId);
    }

    public static List<RitualClaim> claimsFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_CLAIMS_FOR, CLAIM, player);
    }

    /** @return 1 when this call won the commit CAS. */
    public static int commitClaim(TransactionContext tx, UUID claimId, long nowUtc)
            throws SQLException {
        return tx.update(CAS_COMMIT_CLAIM, nowUtc, claimId);
    }

    /** @return 1 when this call won the release CAS. */
    public static int releaseClaim(TransactionContext tx, UUID claimId, long nowUtc)
            throws SQLException {
        return tx.update(CAS_RELEASE_CLAIM, nowUtc, claimId);
    }

    /** @return how many stale reservations this sweep released. */
    public static int releaseStale(TransactionContext tx, long createdFloorUtc,
            long nowUtc) throws SQLException {
        return tx.update(CAS_RELEASE_STALE, nowUtc, createdFloorUtc);
    }

    /** @return the RESERVED claims at or older than the floor (sweep set). */
    public static List<RitualClaim> staleReservations(TransactionContext tx,
            long createdFloorUtc) throws SQLException {
        return tx.list(SELECT_STALE, CLAIM, createdFloorUtc);
    }

    public static long attemptsInWindow(TransactionContext tx, UUID player,
            String ritualKey, long windowFloorUtc) throws SQLException {
        return tx.one(COUNT_ATTEMPTS_IN_WINDOW, COUNT, player, ritualKey,
                windowFloorUtc).orElse(0L);
    }

    public static long lastCommitted(TransactionContext tx, UUID player,
            String ritualKey) throws SQLException {
        return tx.one(LAST_COMMITTED, COUNT, player, ritualKey).orElse(0L);
    }

    public static long reservedCount(TransactionContext tx, UUID player,
            String ritualKey) throws SQLException {
        return tx.one(COUNT_RESERVED, COUNT, player, ritualKey).orElse(0L);
    }

    // --- sessions ---------------------------------------------------------

    public static void insertSession(TransactionContext tx, UUID sessionId,
            UUID claimId, UUID caster, UUID target, Precision precision,
            long beganUtc, long expiresUtc) throws SQLException {
        tx.update(INSERT_SESSION, sessionId, claimId, caster, target, precision.name(),
                beganUtc, expiresUtc);
    }

    public static Optional<TrackingSession> session(TransactionContext tx,
            UUID sessionId) throws SQLException {
        return tx.one(SELECT_SESSION, SESSION, sessionId);
    }

    public static Optional<TrackingSession> sessionByClaim(TransactionContext tx,
            UUID claimId) throws SQLException {
        return tx.one(SELECT_SESSION_BY_CLAIM, SESSION, claimId);
    }

    public static List<TrackingSession> activeSessionsFor(TransactionContext tx,
            UUID caster, long nowUtc) throws SQLException {
        return tx.list(SELECT_ACTIVE_SESSIONS_FOR, SESSION, caster, nowUtc);
    }

    public static long activeSessionCount(TransactionContext tx, UUID caster,
            long nowUtc) throws SQLException {
        return tx.one(COUNT_ACTIVE_SESSIONS_FOR, COUNT, caster, nowUtc).orElse(0L);
    }

    /** @return 1 when this call won the revocation CAS. */
    public static int revokeSession(TransactionContext tx, UUID sessionId,
            String reason, long nowUtc) throws SQLException {
        return tx.update(CAS_REVOKE_SESSION, nowUtc, truncate(reason, 64), sessionId,
                nowUtc);
    }

    // --- effects ------------------------------------------------------------

    public static void insertEffect(TransactionContext tx, UUID effectId, UUID player,
            String effectKey, long grantedUtc, long expiresUtc, UUID grantedBy)
            throws SQLException {
        tx.update(INSERT_EFFECT, effectId, player, effectKey, grantedUtc, expiresUtc,
                grantedBy);
    }

    public static long activeEffectCount(TransactionContext tx, UUID player,
            String effectKey, long nowUtc) throws SQLException {
        return tx.one(COUNT_ACTIVE_EFFECTS_FOR, COUNT, player, effectKey, nowUtc)
                .orElse(0L);
    }

    public static List<PlayerEffect> effectsFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_EFFECTS_FOR, EFFECT, player);
    }

    // --- encounters ------------------------------------------------------------

    public static void insertEncounter(TransactionContext tx, UUID encounterId,
            String key, String world, int x, int y, int z, long nowUtc)
            throws SQLException {
        tx.update(INSERT_ENCOUNTER, encounterId, key, truncate(world, 64), x, y, z,
                nowUtc);
    }

    public static Optional<Encounter> encounter(TransactionContext tx,
            UUID encounterId) throws SQLException {
        return tx.one(SELECT_ENCOUNTER, ENCOUNTER, encounterId);
    }

    public static List<Encounter> encountersByKey(TransactionContext tx, String key)
            throws SQLException {
        return tx.list(SELECT_ENCOUNTERS_BY_KEY, ENCOUNTER, key);
    }

    public static List<Encounter> encountersByState(TransactionContext tx,
            EncounterState state) throws SQLException {
        return tx.list(SELECT_ENCOUNTERS_BY_STATE, ENCOUNTER, state.name());
    }

    public static long activeEncounterCount(TransactionContext tx, String key)
            throws SQLException {
        return tx.one(COUNT_ACTIVE_ENCOUNTERS_FOR, COUNT, key).orElse(0L);
    }

    public static long lastResolvedEncounter(TransactionContext tx, String key)
            throws SQLException {
        return tx.one(LAST_RESOLVED_ENCOUNTER, COUNT, key).orElse(0L);
    }

    /** @return 1 when this call won the completion CAS (reward path). */
    public static int completeEncounter(TransactionContext tx, UUID encounterId,
            UUID player, long nowUtc) throws SQLException {
        return tx.update(CAS_COMPLETE_ENCOUNTER, nowUtc, player, encounterId);
    }

    /** @return 1 when this sweep expired the row. */
    public static int expireEncounter(TransactionContext tx, UUID encounterId,
            long beganFloorUtc, long nowUtc) throws SQLException {
        return tx.update(CAS_EXPIRE_ENCOUNTER, nowUtc, encounterId, beganFloorUtc);
    }

    /** @return 1 when this call attached the soul entity. */
    public static int attachSoul(TransactionContext tx, UUID encounterId,
            UUID entityUuid) throws SQLException {
        return tx.update(CAS_ATTACH_SOUL, entityUuid, encounterId);
    }

    /** @return 1 when this call despawned the row (admin unwedge path). */
    public static int despawnEncounter(TransactionContext tx, UUID encounterId,
            long nowUtc) throws SQLException {
        return tx.update(CAS_DESPAWN_ENCOUNTER, nowUtc, encounterId);
    }

    // --- events ------------------------------------------------------------------

    public static void insertEvent(TransactionContext tx, String eventKey,
            UUID player, String type, String detail, String outcome, long nowUtc)
            throws SQLException {
        tx.update(INSERT_EVENT, eventKey, player, type, truncate(detail, 255), outcome,
                nowUtc);
    }

    public static Optional<EventRow> event(TransactionContext tx, String eventKey)
            throws SQLException {
        return tx.one(SELECT_EVENT, EVENT, eventKey);
    }

    public static List<EventRow> eventsFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_EVENTS_FOR, EVENT, player);
    }

    public static List<EventRow> eventsAll(TransactionContext tx) throws SQLException {
        return tx.list(SELECT_EVENTS_ALL, EVENT);
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
