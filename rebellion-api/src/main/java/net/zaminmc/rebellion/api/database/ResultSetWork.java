package net.zaminmc.rebellion.api.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Full-control read over a {@link ResultSet}, including its iteration and, if
 * required, multi-statement result navigation. Used by queries that need
 * streaming or aggregate access that does not fit {@link RowMapper}.
 *
 * <p>The helper owns closing the statement and result set; implementations must
 * not close them and must not retain references after returning.</p>
 */
@FunctionalInterface
public interface ResultSetWork<T> {

    T apply(ResultSet rs) throws SQLException;
}
