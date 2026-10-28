package net.zaminmc.rebellion.prison.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.prison.FeeQuote;
import net.zaminmc.rebellion.prison.FeeRecord;
import net.zaminmc.rebellion.prison.PlayerProfile;
import net.zaminmc.rebellion.prison.ReleaseReason;
import net.zaminmc.rebellion.prison.Sentence;
import net.zaminmc.rebellion.prison.SentenceState;
import org.jetbrains.annotations.Nullable;

/**
 * Prison SQL surface (schema v1, ADR-0021). Statements are constants; every
 * mutation runs inside a caller-supplied transaction. State transitions
 * are compare-and-set statements — the {@code WHERE} clause pins the
 * expected prior state, so races and duplicate deliveries resolve through
 * the affected-row count. Scar accumulation and restoration are guarded
 * CASE clamps evaluated atomically inside single UPDATE statements (the
 * MIN/LEAST scalar functions are not portable across the two dialects).
 */
public final class PrisonStore {

    private PrisonStore() {
    }

    /** Append-only audit row (written inside mutation transactions). */
    public record EventRow(String eventKey, @Nullable UUID playerUuid, String type,
            String detail, String outcome, long atUtc) {
    }

    /** Immutable view of one processed death. */
    public record DeathRow(String deathKey, UUID player, long scoreDelta,
            int healthPenalty, long createdUtc) {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    private static final String PROFILE_COLS =
            "player_uuid, death_score, scar_points, restored_points, updated_utc";

    private static final RowMapper<PlayerProfile> PROFILE = rs -> new PlayerProfile(
            UUID.fromString(rs.getString(1)),
            rs.getLong(2),
            rs.getInt(3),
            rs.getInt(4),
            rs.getLong(5));

    private static final RowMapper<Sentence> SENTENCE = rs -> new Sentence(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            SentenceState.parse(rs.getString(3)),
            rs.getString(4),
            rs.getLong(5),
            rs.getLong(6),
            rs.getLong(7),
            rs.getInt(8),
            rs.getInt(9),
            rs.getLong(10),
            rs.getObject(11) == null ? null : rs.getLong(11),
            rs.getString(12) == null ? null : ReleaseReason.parse(rs.getString(12)));

    private static final RowMapper<FeeRecord> FEE = rs -> new FeeRecord(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            UUID.fromString(rs.getString(3)),
            net.zaminmc.rebellion.prison.FeeState.parse(rs.getString(4)),
            rs.getLong(5),
            rs.getString(6),
            rs.getInt(7),
            rs.getString(8),
            FeeQuote.FeeClamp.valueOf(rs.getString(9)),
            rs.getLong(10),
            rs.getString(11),
            rs.getString(12) == null ? null : UUID.fromString(rs.getString(12)),
            rs.getLong(13),
            rs.getObject(14) == null ? null : rs.getLong(14));

    private static final RowMapper<Integer> COUNT = rs -> rs.getInt(1);

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    private static final String SELECT_PROFILE =
            "SELECT " + PROFILE_COLS + " FROM rb_prison_profiles"
            + " WHERE player_uuid = ?";

    private static final String INSERT_PROFILE =
            "INSERT INTO rb_prison_profiles (" + PROFILE_COLS + ")"
            + " VALUES (?, ?, ?, ?, ?)";

    /** One-statement death application: score accumulate + scar clamp.
     *  The CASE keeps scar_points at the configured ceiling. */
    private static final String APPLY_DEATH =
            "UPDATE rb_prison_profiles SET death_score = death_score + ?,"
            + " scar_points = CASE WHEN scar_points + ? > ? THEN ?"
            + " ELSE scar_points + ? END, updated_utc = ?"
            + " WHERE player_uuid = ?";

    /** One-statement restoration: restored_points can never pass scars. */
    private static final String RESTORE_POINTS =
            "UPDATE rb_prison_profiles SET"
            + " restored_points = CASE WHEN restored_points + ? > scar_points"
            + " THEN scar_points ELSE restored_points + ? END,"
            + " updated_utc = ? WHERE player_uuid = ?";

    /** @return the player's profile, or empty when nothing is recorded. */
    public static Optional<PlayerProfile> profile(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one(SELECT_PROFILE, PROFILE, player);
    }

    /** First-death profile insert; 0 when the row already exists. */
    public static int insertProfile(TransactionContext tx, UUID player,
            long score, int scars, long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_PROFILE, player, score, scars, 0, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** Atomic score accumulate with the scar ceiling clamp. */
    public static int applyDeathToProfile(TransactionContext tx, UUID player,
            long scoreDelta, int penalty, int maxActivePenalty, long nowUtc)
            throws SQLException {
        return tx.update(APPLY_DEATH, scoreDelta, penalty, maxActivePenalty,
                maxActivePenalty, penalty, nowUtc, player);
    }

    /** Atomic restoration clamped to the scar ledger. */
    public static int restorePoints(TransactionContext tx, UUID player, int heal,
            long nowUtc) throws SQLException {
        return tx.update(RESTORE_POINTS, heal, heal, nowUtc, player);
    }

    // ------------------------------------------------------------------
    // Deaths
    // ------------------------------------------------------------------

    private static final String INSERT_DEATH =
            "INSERT INTO rb_prison_deaths (death_key, player_uuid, score_delta,"
            + " health_penalty, created_utc) VALUES (?, ?, ?, ?, ?)";

    /** Idempotent death insert; 0 when the key was already processed. */
    public static int insertDeath(TransactionContext tx, String deathKey,
            UUID player, long scoreDelta, int healthPenalty, long nowUtc)
            throws SQLException {
        try {
            return tx.update(INSERT_DEATH, deathKey, player, scoreDelta,
                    healthPenalty, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Sentences
    // ------------------------------------------------------------------

    private static final String SENTENCE_COLS =
            "sentence_id, player_uuid, state, jail_world, jail_x, jail_y, jail_z,"
            + " jail_yaw, jail_pitch, created_utc, released_utc, release_reason";

    private static final String SELECT_LIVE_SENTENCE =
            "SELECT " + SENTENCE_COLS + " FROM rb_prison_sentences"
            + " WHERE live_guard = ?";

    private static final String SELECT_PENDING =
            "SELECT " + SENTENCE_COLS + " FROM rb_prison_sentences"
            + " WHERE live_guard = ? AND state = 'PENDING'";

    private static final String SELECT_SENTENCE_BY_ID =
            "SELECT " + SENTENCE_COLS + " FROM rb_prison_sentences"
            + " WHERE sentence_id = ?";

    private static final String INSERT_SENTENCE =
            "INSERT INTO rb_prison_sentences (" + SENTENCE_COLS + ", live_guard)"
            + " VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)";

    private static final String CONFIRM_JAILED =
            "UPDATE rb_prison_sentences SET state = 'JAILED'"
            + " WHERE sentence_id = ? AND state = 'PENDING'";

    private static final String RELEASE_LIVE =
            "UPDATE rb_prison_sentences SET state = 'RELEASED', live_guard = NULL,"
            + " released_utc = ?, release_reason = ?"
            + " WHERE sentence_id = ? AND state IN ('PENDING', 'JAILED')";

    /** PENDING sentence insert carrying the live-guard slot; 0 when the
     *  player already holds a live sentence (unique violation classified). */
    public static int insertPendingSentence(TransactionContext tx, UUID sentenceId,
            UUID player, String world, long x, long y, long z, int yaw, int pitch,
            long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_SENTENCE, sentenceId, player, world, x, y, z,
                    yaw, pitch, nowUtc, player);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** @return the player's live sentence, when one exists. */
    public static Optional<Sentence> liveSentence(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one(SELECT_LIVE_SENTENCE, SENTENCE, player);
    }

    /** @return the player's live PENDING sentence, when one exists. */
    public static Optional<Sentence> pendingSentence(TransactionContext tx,
            UUID player) throws SQLException {
        return tx.one(SELECT_PENDING, SENTENCE, player);
    }

    /** @return the sentence row by id (any state). */
    public static Optional<Sentence> sentenceById(TransactionContext tx,
            UUID sentenceId) throws SQLException {
        return tx.one(SELECT_SENTENCE_BY_ID, SENTENCE, sentenceId);
    }

    /** CAS PENDING → JAILED; 0 when the row was not PENDING. */
    public static int confirmJailed(TransactionContext tx, UUID sentenceId)
            throws SQLException {
        return tx.update(CONFIRM_JAILED, sentenceId);
    }

    /** CAS live → RELEASED with the reason; 0 when the row was not live. */
    public static int releaseLive(TransactionContext tx, UUID sentenceId,
            ReleaseReason reason, long nowUtc) throws SQLException {
        return tx.update(RELEASE_LIVE, nowUtc, reason.name(), sentenceId);
    }

    /** @return every live PENDING sentence (the join/respawn retry sweep). */
    public static List<Sentence> allPending(TransactionContext tx) throws SQLException {
        return tx.list("SELECT " + SENTENCE_COLS
                + " FROM rb_prison_sentences WHERE state = 'PENDING'"
                + " ORDER BY created_utc", SENTENCE);
    }

    // ------------------------------------------------------------------
    // Fees
    // ------------------------------------------------------------------

    private static final String FEE_COLS =
            "fee_id, sentence_id, player_uuid, state, death_score, inflation_factor,"
            + " multiplier, rounding, clamp, amount_minor, provider, ledger_entry,"
            + " quoted_utc, resolved_utc";

    private static final String SELECT_FEE_BY_SENTENCE =
            "SELECT " + FEE_COLS + " FROM rb_prison_fees WHERE sentence_id = ?";

    private static final String INSERT_FEE =
            "INSERT INTO rb_prison_fees (" + FEE_COLS + ")"
            + " VALUES (?, ?, ?, 'QUOTED', ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)";

    private static final String MARK_FEE_PAID =
            "UPDATE rb_prison_fees SET state = 'PAID', provider = ?,"
            + " ledger_entry = ?, resolved_utc = ?"
            + " WHERE fee_id = ? AND state = 'QUOTED'";

    private static final String MARK_FEE_VOID =
            "UPDATE rb_prison_fees SET state = 'VOID', resolved_utc = ?"
            + " WHERE fee_id = ? AND state = 'QUOTED'";

    /** @return the sentence's fee row, when one exists. */
    public static Optional<FeeRecord> feeBySentence(TransactionContext tx,
            UUID sentenceId) throws SQLException {
        return tx.one(SELECT_FEE_BY_SENTENCE, FEE, sentenceId);
    }

    /** QUOTED fee insert (the frozen §5.9 audit + claim). */
    public static int insertFee(TransactionContext tx, UUID feeId, UUID sentenceId,
            UUID player, FeeQuote quote, long nowUtc) throws SQLException {
        return tx.update(INSERT_FEE, feeId, sentenceId, player, quote.deathScore(),
                quote.inflationFactor(), quote.multiplier(), quote.rounding(),
                quote.clamp().name(), quote.amountMinor(), nowUtc);
    }

    /** CAS QUOTED → PAID stamping the provider + ledger entry; 0 otherwise. */
    public static int markFeePaid(TransactionContext tx, UUID feeId, String provider,
            UUID ledgerEntry, long nowUtc) throws SQLException {
        return tx.update(MARK_FEE_PAID, provider, ledgerEntry, nowUtc, feeId);
    }

    /** CAS QUOTED → VOID; 0 otherwise. */
    public static int markFeeVoid(TransactionContext tx, UUID feeId, long nowUtc)
            throws SQLException {
        return tx.update(MARK_FEE_VOID, nowUtc, feeId);
    }

    // ------------------------------------------------------------------
    // Rehabilitation
    // ------------------------------------------------------------------

    private static final String COUNT_OCCURRENCES =
            "SELECT COUNT(*) FROM rb_prison_rehab WHERE player_uuid = ?"
            + " AND task_key = ?";

    private static final String INSERT_REHAB =
            "INSERT INTO rb_prison_rehab (player_uuid, task_key, completion_key,"
            + " occurrence, healed_points, created_utc) VALUES (?, ?, ?, ?, ?, ?)";

    /** @return how many occurrences of the task the player has completed. */
    public static int occurrencesOf(TransactionContext tx, UUID player, String taskKey)
            throws SQLException {
        return tx.one(COUNT_OCCURRENCES, COUNT, player, taskKey).orElse(0);
    }

    /** Structurally idempotent completion insert; 0 on a replayed key. */
    public static int insertRehab(TransactionContext tx, UUID player, String taskKey,
            String completionKey, int occurrence, int healed, long nowUtc)
            throws SQLException {
        try {
            return tx.update(INSERT_REHAB, player, taskKey, completionKey,
                    occurrence, healed, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private static final String INSERT_EVENT =
            "INSERT INTO rb_prison_events (event_key, player_uuid, type, detail,"
            + " outcome, at_utc) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_EVENT_OUTCOME =
            "UPDATE rb_prison_events SET outcome = ?, detail = ? WHERE event_key = ?";

    /** Append-only audit insert; 0 on a duplicate event key. */
    public static int insertEvent(TransactionContext tx, String eventKey,
            @Nullable UUID player, String type, String detail, @Nullable String outcome,
            long nowUtc) throws SQLException {
        try {
            tx.update(INSERT_EVENT, truncate(eventKey, 160), player, type,
                    truncate(detail, 255), outcome == null ? "PENDING" : outcome,
                    nowUtc);
            return 1;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** Completes the event's outcome inside the same transaction. */
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

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
