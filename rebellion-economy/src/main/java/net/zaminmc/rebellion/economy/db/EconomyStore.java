package net.zaminmc.rebellion.economy.db;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Economy SQL surface (schema v1, ADR-0021). Statements are constants; every
 * mutation runs inside a caller-supplied transaction so account + ledger rows
 * commit all-or-none. Idempotency resolves through the
 * {@code UNIQUE (account_uuid, idempotency_key)} constraint: the duplicate
 * INSERT loser is classified here into a 0-return and the caller answers
 * from the surviving row.
 */
public final class EconomyStore {

    private EconomyStore() {
    }

    /** Ledger row: the durable transaction record callers store ids of. */
    public record LedgerRow(UUID entryId, UUID account, String idempotencyKey,
            String reason, long delta, long balanceAfter, long createdUtc) {
    }

    private static final RowMapper<LedgerRow> LEDGER = rs -> new LedgerRow(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            rs.getString(3),
            rs.getString(4),
            rs.getLong(5),
            rs.getLong(6),
            rs.getLong(7));

    private static final RowMapper<Long> BALANCE = rs -> rs.getLong(1);

    private static final String SELECT_BALANCE =
            "SELECT balance FROM rb_economy_accounts WHERE account_uuid = ?";

    private static final String INSERT_ACCOUNT =
            "INSERT INTO rb_economy_accounts (account_uuid, balance, updated_utc)"
            + " VALUES (?, ?, ?)";

    private static final String ACCUMULATE =
            "UPDATE rb_economy_accounts SET balance = balance + ?,"
            + " updated_utc = ? WHERE account_uuid = ?";

    private static final String INSERT_LEDGER =
            "INSERT INTO rb_economy_ledger (entry_id, account_uuid,"
            + " idempotency_key, reason, delta, balance_after, created_utc)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_ENTRY =
            "SELECT entry_id, account_uuid, idempotency_key, reason, delta,"
            + " balance_after, created_utc FROM rb_economy_ledger"
            + " WHERE account_uuid = ? AND idempotency_key = ?";

    /** @return the account balance, or empty when no row exists (reads zero). */
    public static Optional<Long> balance(TransactionContext tx, UUID account)
            throws SQLException {
        return tx.one(SELECT_BALANCE, BALANCE, account);
    }

    /** First-mutation account insert; 0 when the row already exists. */
    public static int insertAccount(TransactionContext tx, UUID account,
            long balance, long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_ACCOUNT, account, balance, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** Atomic single-statement accumulate (no check-then-set race). */
    public static int accumulate(TransactionContext tx, UUID account, long delta,
            long nowUtc) throws SQLException {
        return tx.update(ACCUMULATE, delta, nowUtc, account);
    }

    /** Append-only ledger insert; 0 when the (account, key) pair already exists. */
    public static int insertLedger(TransactionContext tx, UUID entryId,
            UUID account, String idempotencyKey, String reason, long delta,
            long balanceAfter, long nowUtc) throws SQLException {
        try {
            return tx.update(INSERT_LEDGER, entryId, account, idempotencyKey,
                    reason, delta, balanceAfter, nowUtc);
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return 0;
            }
            throw e;
        }
    }

    /** @return the surviving entry for a replayed (account, key) pair. */
    public static Optional<LedgerRow> entryByKey(TransactionContext tx,
            UUID account, String idempotencyKey) throws SQLException {
        return tx.one(SELECT_ENTRY, LEDGER, account, idempotencyKey);
    }

    /**
     * Detects a unique/PK constraint violation across both dialects
     * (SQLite message text and the SQL-standard 23* states MySQL uses).
     */
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

    /** Null-safe truncation for bounded columns. */
    public static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
