package net.zaminmc.rebellion.api.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row of a {@link ResultSet} to a value.
 *
 * <p>Implementations must not advance the ResultSet themselves and must not retain
 * the ResultSet or Connection after returning; helpers iterate and close resources.</p>
 */
@FunctionalInterface
public interface RowMapper<T> {

    /** Reads columns of the current row. Must not call {@code next()}. */
    T mapRow(ResultSet rs) throws SQLException;
}
