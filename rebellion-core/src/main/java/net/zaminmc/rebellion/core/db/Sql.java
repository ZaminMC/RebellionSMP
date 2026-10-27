package net.zaminmc.rebellion.core.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Bind-parameter normalization shared by every statement this product executes.
 *
 * <p>The supported set is intentionally small and portable across SQLite and
 * MySQL: {@code null}, {@code String}, {@code UUID} (stored as string),
 * {@code Boolean} (stored as 0/1), {@code Integer}, {@code Long}, {@code Double},
 * {@code Float}, {@code byte[]}. Anything else is rejected before the database
 * sees it, so no value can smuggle an unexpected SQL type into a statement.</p>
 */
final class Sql {

    private Sql() {
    }

    /** Binds normalized parameters to a prepared statement. */
    static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, normalize(params[i]));
        }
    }

    /** Normalizes one parameter, failing fast on unsupported types. */
    static Object normalize(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Double
                || value instanceof Float
                || value instanceof byte[]) {
            return value;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException(
                "Unsupported bind parameter type: " + value.getClass().getName()
                        + " (supported: null, String, UUID, Boolean, Integer, Long, Double, Float, byte[])");
    }

    /** Sets a nullable string (some drivers need explicit typing). */
    static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
