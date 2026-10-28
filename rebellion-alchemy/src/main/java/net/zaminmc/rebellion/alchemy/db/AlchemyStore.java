package net.zaminmc.rebellion.alchemy.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.alchemy.AlchemyClaim;
import net.zaminmc.rebellion.alchemy.AutoRule;
import net.zaminmc.rebellion.alchemy.AutoTrigger;
import net.zaminmc.rebellion.alchemy.ClaimPurpose;
import net.zaminmc.rebellion.alchemy.ClaimState;
import net.zaminmc.rebellion.alchemy.StorageScope;
import net.zaminmc.rebellion.alchemy.UnlockGrant;
import org.jetbrains.annotations.Nullable;

/**
 * Alchemy SQL surface (schema v1, ADR-0019). Statements are constants; every
 * mutation runs inside a caller-supplied transaction. State transitions are
 * <strong>compare-and-set</strong> statements — the {@code WHERE} clause
 * pins the expected prior state, so races and duplicate event delivery
 * resolve through the affected-row count. Inserts guarded by primary keys
 * (unlocks, rules, events) resolve idempotency through the constraint.
 */
public final class AlchemyStore {

    private AlchemyStore() {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    /** Append-only audit row (written inside mutation transactions). */
    public record EventRow(String eventKey, @Nullable UUID playerUuid, String type,
            String detail, String outcome, long atUtc) {
    }

    public static final RowMapper<Long> COUNT = rs -> rs.getLong(1);

    public static final RowMapper<long[]> PROGRESS = rs -> new long[] {
            rs.getLong(1), rs.getLong(2)};

    public static final RowMapper<UnlockGrant> UNLOCK = rs -> new UnlockGrant(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            rs.getLong(3),
            rs.getString(4) == null ? null : UUID.fromString(rs.getString(4)));

    public static final RowMapper<AutoRule> RULE = rs -> new AutoRule(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            AutoTrigger.parse(rs.getString(3)),
            rs.getString(4),
            rs.getObject(5) == null ? null : rs.getInt(5),
            rs.getInt(6),
            rs.getInt(7),
            StorageScope.parse(rs.getString(8)),
            rs.getBoolean(9),
            rs.getLong(10),
            rs.getLong(11),
            rs.getObject(12) == null ? null : rs.getLong(12),
            rs.getObject(13) == null ? null : rs.getLong(13));

    public static final RowMapper<AlchemyClaim> CLAIM = rs -> new AlchemyClaim(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            ClaimPurpose.parse(rs.getString(3)),
            rs.getString(4),
            ClaimState.parse(rs.getString(5)),
            rs.getString(6),
            rs.getLong(7),
            rs.getObject(8) == null ? null : rs.getLong(8));

    public static final RowMapper<EventRow> EVENT = rs -> new EventRow(
            rs.getString(1),
            rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)),
            rs.getString(3),
            rs.getString(4),
            rs.getString(5),
            rs.getLong(6));

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    static final String INSERT_PROGRESS = "INSERT INTO rb_alchemy_progress "
            + "(player_uuid, total_xp, updated_utc) VALUES (?, ?, ?)";

    /** Atomic accumulate: single-statement read-modify-write, no check-then-set race. */
    static final String ADD_XP = "UPDATE rb_alchemy_progress SET total_xp = "
            + "total_xp + ?, updated_utc = ? WHERE player_uuid = ?";

    static final String SELECT_PROGRESS = "SELECT total_xp, updated_utc FROM "
            + "rb_alchemy_progress WHERE player_uuid = ?";

    // ------------------------------------------------------------------
    // Unlocks
    // ------------------------------------------------------------------

    static final String INSERT_UNLOCK = "INSERT INTO rb_alchemy_unlocks "
            + "(player_uuid, unlock_key, granted_utc, claim_id) VALUES (?, ?, ?, ?)";

    static final String SELECT_UNLOCK = "SELECT player_uuid, unlock_key, "
            + "granted_utc, claim_id FROM rb_alchemy_unlocks WHERE player_uuid = ? "
            + "AND unlock_key = ?";

    static final String SELECT_UNLOCKS_FOR = "SELECT player_uuid, unlock_key, "
            + "granted_utc, claim_id FROM rb_alchemy_unlocks WHERE player_uuid = ? "
            + "ORDER BY granted_utc";

    // ------------------------------------------------------------------
    // Auto rules
    // ------------------------------------------------------------------

    static final String RULE_COLUMNS = "rule_id, player_uuid, trigger, "
            + "potion_type, threshold, cooldown_seconds, priority, storage, "
            + "enabled, created_utc, updated_utc, removed_utc, last_fired_utc";

    static final String INSERT_RULE = "INSERT INTO rb_alchemy_rules (rule_id, "
            + "player_uuid, trigger, potion_type, threshold, cooldown_seconds, "
            + "priority, storage, enabled, created_utc, updated_utc, removed_utc, "
            + "last_fired_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)";

    static final String SELECT_RULE = "SELECT " + RULE_COLUMNS
            + " FROM rb_alchemy_rules WHERE rule_id = ?";

    /** The resolution set: live rules in the deterministic conflict order. */
    static final String SELECT_LIVE_RULES_FOR = "SELECT " + RULE_COLUMNS
            + " FROM rb_alchemy_rules WHERE player_uuid = ? AND removed_utc IS NULL "
            + "AND enabled = 1 ORDER BY priority, created_utc, rule_id";

    /** Per-player cap probe: live rules of any enabled state. */
    static final String COUNT_LIVE_RULES_FOR = "SELECT COUNT(*) FROM "
            + "rb_alchemy_rules WHERE player_uuid = ? AND removed_utc IS NULL";

    /** Forensics read: every rule row, removed included. */
    static final String SELECT_ALL_RULES_FOR = "SELECT " + RULE_COLUMNS
            + " FROM rb_alchemy_rules WHERE player_uuid = ? "
            + "ORDER BY created_utc, rule_id";

    /** Toggle CAS: ownership + liveness pinned in the WHERE clause. */
    static final String CAS_SET_ENABLED = "UPDATE rb_alchemy_rules SET enabled = ?, "
            + "updated_utc = ? WHERE rule_id = ? AND player_uuid = ? "
            + "AND removed_utc IS NULL AND enabled != ?";

    /** Removal CAS: ownership + liveness pinned; rows are kept for forensics. */
    static final String CAS_REMOVE_RULE = "UPDATE rb_alchemy_rules SET removed_utc "
            + "= ?, updated_utc = ? WHERE rule_id = ? AND player_uuid = ? "
            + "AND removed_utc IS NULL";

    /** Cooldown-stamp CAS: only a live, enabled rule still accepts a stamp. */
    static final String CAS_STAMP_LAST_FIRED = "UPDATE rb_alchemy_rules SET "
            + "last_fired_utc = ?, updated_utc = ? WHERE rule_id = ? "
            + "AND removed_utc IS NULL AND enabled = 1";

    // ------------------------------------------------------------------
    // Claims
    // ------------------------------------------------------------------

    static final String CLAIM_COLUMNS = "claim_id, player_uuid, purpose, "
            + "subject_key, state, digest, created_utc, resolved_utc";

    static final String INSERT_CLAIM = "INSERT INTO rb_alchemy_claims (claim_id, "
            + "player_uuid, purpose, subject_key, state, digest, created_utc, "
            + "resolved_utc) VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, NULL)";

    static final String SELECT_CLAIM = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_alchemy_claims WHERE claim_id = ?";

    static final String SELECT_CLAIMS_FOR = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_alchemy_claims WHERE player_uuid = ? ORDER BY created_utc";

    /** Commit CAS: only a RESERVED claim can become COMMITTED. */
    static final String CAS_COMMIT_CLAIM = "UPDATE rb_alchemy_claims SET state = "
            + "'COMMITTED', resolved_utc = ? WHERE claim_id = ? AND state = 'RESERVED'";

    /** Release CAS: only a RESERVED claim can become RELEASED. */
    static final String CAS_RELEASE_CLAIM = "UPDATE rb_alchemy_claims SET state = "
            + "'RELEASED', resolved_utc = ? WHERE claim_id = ? AND state = 'RESERVED'";

    /** Stale-reservation sweep: RESERVED rows older than the floor. */
    static final String CAS_RELEASE_STALE = "UPDATE rb_alchemy_claims SET state = "
            + "'RELEASED', resolved_utc = ? WHERE state = 'RESERVED' AND created_utc <= ?";

    /** Stale-reservation probe (per-claim release events preserve digests). */
    static final String SELECT_STALE = "SELECT " + CLAIM_COLUMNS
            + " FROM rb_alchemy_claims WHERE state = 'RESERVED' AND created_utc <= ?"
            + " ORDER BY created_utc";

    /** Single-reservation guard: open reservations for player + subject. */
    static final String COUNT_RESERVED = "SELECT COUNT(*) FROM rb_alchemy_claims "
            + "WHERE player_uuid = ? AND purpose = ? AND subject_key = ? "
            + "AND state = 'RESERVED'";

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    static final String INSERT_EVENT = "INSERT INTO rb_alchemy_events (event_key, "
            + "player_uuid, type, detail, outcome, at_utc) VALUES (?, ?, ?, ?, ?, ?)";

    static final String SELECT_EVENT = "SELECT event_key, player_uuid, type, detail, "
            + "outcome, at_utc FROM rb_alchemy_events WHERE event_key = ?";

    static final String SELECT_EVENTS_FOR = "SELECT event_key, player_uuid, type, "
            + "detail, outcome, at_utc FROM rb_alchemy_events WHERE player_uuid = ? "
            + "ORDER BY at_utc";

    static final String SELECT_EVENTS_ALL = "SELECT event_key, player_uuid, type, "
            + "detail, outcome, at_utc FROM rb_alchemy_events ORDER BY at_utc";

    /** Finalize an audit row's outcome inside its own transaction (the XP
     *  award inserts its gate row first, then computes and writes the
     *  LEVEL_UP/XP outcome once the accumulate is visible). */
    static final String UPDATE_EVENT_OUTCOME = "UPDATE rb_alchemy_events SET "
            + "outcome = ?, detail = ? WHERE event_key = ?";

    // ------------------------------------------------------------------
    // Operations (all inside the caller's transaction)
    // ------------------------------------------------------------------

    // --- progress ------------------------------------------------------------

    /**
     * @return 1 when this call created the first progress row, 0 when the
     *  player already has one (the caller then accumulates atomically).
     *  The PK violation of the concurrent-first-award race is classified
     *  into 0 — never an error.
     */
    public static int insertProgress(TransactionContext tx, UUID player,
            long initialXp, long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_PROGRESS, player, initialXp, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** Atomic accumulate; 0 rows = no progress row yet. */
    public static int addXp(TransactionContext tx, UUID player, long amount,
            long nowUtc) throws SQLException {
        return tx.update(ADD_XP, amount, nowUtc, player);
    }

    /** @return raw [total_xp, updated_utc] when the row exists. */
    public static Optional<long[]> progress(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one(SELECT_PROGRESS, PROGRESS, player);
    }

    // --- unlocks ---------------------------------------------------------------

    /** @return 1 when this call created the grant, 0 when already owned. */
    public static int insertUnlock(TransactionContext tx, UUID player, String key,
            long nowUtc, UUID claimId) throws SQLException {
        try {
            return tx.update(INSERT_UNLOCK, player, key, nowUtc, claimId);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    public static Optional<UnlockGrant> unlock(TransactionContext tx, UUID player,
            String key) throws SQLException {
        return tx.one(SELECT_UNLOCK, UNLOCK, player, key);
    }

    public static List<UnlockGrant> unlocksFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_UNLOCKS_FOR, UNLOCK, player);
    }

    // --- rules -----------------------------------------------------------------

    public static void insertRule(TransactionContext tx, AutoRule rule)
            throws SQLException {
        tx.update(INSERT_RULE, rule.ruleId(), rule.playerUuid(),
                rule.trigger().name(), truncate(rule.potionType(), 32),
                rule.threshold(), rule.cooldownSeconds(), rule.priority(),
                rule.storage().name(), rule.enabled(), rule.createdUtc(),
                rule.updatedUtc());
    }

    /** @return 1 when this call created the row, 0 when the id already exists. */
    public static int insertRuleIfAbsent(TransactionContext tx, AutoRule rule)
            throws SQLException {
        try {
            insertRule(tx, rule);
            return 1;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    public static Optional<AutoRule> rule(TransactionContext tx, UUID ruleId)
            throws SQLException {
        return tx.one(SELECT_RULE, RULE, ruleId);
    }

    public static List<AutoRule> liveRulesFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_LIVE_RULES_FOR, RULE, player);
    }

    public static List<AutoRule> allRulesFor(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.list(SELECT_ALL_RULES_FOR, RULE, player);
    }

    public static long liveRuleCount(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one(COUNT_LIVE_RULES_FOR, COUNT, player).orElse(0L);
    }

    /** @return 1 when this call won the toggle CAS. */
    public static int setRuleEnabled(TransactionContext tx, UUID ruleId,
            UUID player, boolean enabled, long nowUtc) throws SQLException {
        return tx.update(CAS_SET_ENABLED, enabled, nowUtc, ruleId, player, enabled);
    }

    /** @return 1 when this call won the removal CAS. */
    public static int removeRule(TransactionContext tx, UUID ruleId, UUID player,
            long nowUtc) throws SQLException {
        return tx.update(CAS_REMOVE_RULE, nowUtc, nowUtc, ruleId, player);
    }

    /** @return 1 when this call stamped the rule's cooldown authority. */
    public static int stampLastFired(TransactionContext tx, UUID ruleId,
            long firedUtc, long nowUtc) throws SQLException {
        return tx.update(CAS_STAMP_LAST_FIRED, firedUtc, nowUtc, ruleId);
    }

    // --- claims -----------------------------------------------------------------

    public static void insertClaim(TransactionContext tx, UUID claimId, UUID player,
            ClaimPurpose purpose, String subjectKey, String digest, long nowUtc)
            throws SQLException {
        tx.update(INSERT_CLAIM, claimId, player, purpose.name(),
                truncate(subjectKey, 64), truncate(digest, 255), nowUtc);
    }

    public static Optional<AlchemyClaim> claim(TransactionContext tx, UUID claimId)
            throws SQLException {
        return tx.one(SELECT_CLAIM, CLAIM, claimId);
    }

    public static List<AlchemyClaim> claimsFor(TransactionContext tx, UUID player)
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
    public static List<AlchemyClaim> staleClaims(TransactionContext tx,
            long createdFloorUtc) throws SQLException {
        return tx.list(SELECT_STALE, CLAIM, createdFloorUtc);
    }

    public static long reservedCount(TransactionContext tx, UUID player,
            ClaimPurpose purpose, String subjectKey) throws SQLException {
        return tx.one(COUNT_RESERVED, COUNT, player, purpose.name(),
                subjectKey).orElse(0L);
    }

    // --- events -----------------------------------------------------------------

    /**
     * @return 1 when this call created the audit row, 0 when the key already
     *  existed (idempotent no-op — the XP award path uses this as its
     *  duplicate-source gate).
     */
    public static int insertEvent(TransactionContext tx, String eventKey,
            UUID player, String type, String detail, String outcome, long nowUtc)
            throws SQLException {
        try {
            tx.update(INSERT_EVENT, truncate(eventKey, 160), player, type,
                    truncate(detail, 255), outcome, nowUtc);
            return 1;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
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

    /** Finalizes the audit row's outcome (see {@link #UPDATE_EVENT_OUTCOME}). */
    public static int updateEventOutcome(TransactionContext tx, String eventKey,
            String outcome, String detail) throws SQLException {
        return tx.update(UPDATE_EVENT_OUTCOME, outcome, truncate(detail, 255),
                eventKey);
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
