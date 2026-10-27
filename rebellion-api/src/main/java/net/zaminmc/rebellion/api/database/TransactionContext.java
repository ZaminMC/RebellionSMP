package net.zaminmc.rebellion.api.database;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Scoped view of a single open transaction. The runner owns begin/commit/rollback
 * and connection lifecycle; work must not commit, close, or return the connection.
 *
 * <p>All statements executed through this context run on the transaction's
 * connection with the same parameter binding rules as
 * {@link RebellionDatabase#update(String, Object...)}.</p>
 */
public interface TransactionContext {

    /** Executes a DML/DDL statement, returning the affected row count. */
    int update(String sql, Object... params) throws SQLException;

    /** Executes a query and maps every row. */
    <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) throws SQLException;

    /** Executes a query and maps the first row, if any. */
    <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) throws SQLException;
}
