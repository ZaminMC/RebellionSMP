package net.zaminmc.rebellion.wards.db;

import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;

/**
 * Wards SQL surface (schema v1, ADR-0007). Statements are constants; every
 * mutation runs inside a caller-supplied transaction so cap checks and
 * inserts are atomic against the same connection. Constraint violations
 * surface as SQLException and are mapped by {@code WardService} — the
 * UNIQUE constraints are the race backstop.
 */
public final class WardStore {

    private WardStore() {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    /** Full row view; {@code teamId} empty when the ward is owner-bound. */
    public record WardRow(UUID id, String name, String nameNorm, UUID owner,
            Optional<UUID> teamId, String world, double x, double y, double z,
            double radius, WardType type, boolean active, long createdAtUtc) {

        public Ward toDomain() {
            return new Ward(id, name, owner, teamId, world, x, y, z, radius, type,
                    active, createdAtUtc);
        }
    }

    public static final RowMapper<WardRow> WARD = rs -> new WardRow(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            rs.getString(3),
            UUID.fromString(rs.getString(4)),
            rs.getString(5) == null ? Optional.empty() : Optional.of(UUID.fromString(rs.getString(5))),
            rs.getString(6),
            rs.getDouble(7),
            rs.getDouble(8),
            rs.getDouble(9),
            rs.getDouble(10),
            WardType.parse(rs.getString(11)),
            rs.getInt(12) != 0,
            rs.getLong(13));

    static final RowMapper<Long> COUNT = rs -> rs.getLong(1);

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    public static final String INSERT = "INSERT INTO rb_wards "
            + "(id, name, name_norm, owner, team_id, world, x, y, z, radius, type, active, created_at_utc) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public static final String DELETE_BY_ID = "DELETE FROM rb_wards WHERE id = ?";

    public static final String SET_ACTIVE = "UPDATE rb_wards SET active = ? WHERE id = ?";

    public static final String SELECT_BY_ID = "SELECT id, name, name_norm, owner, team_id, world, "
            + "x, y, z, radius, type, active, created_at_utc FROM rb_wards WHERE id = ?";

    public static final String SELECT_BY_ANCHOR = "SELECT id, name, name_norm, owner, team_id, world, "
            + "x, y, z, radius, type, active, created_at_utc FROM rb_wards "
            + "WHERE world = ? AND x = ? AND y = ? AND z = ?";

    public static final String SELECT_ALL = "SELECT id, name, name_norm, owner, team_id, world, "
            + "x, y, z, radius, type, active, created_at_utc FROM rb_wards";

    static final String COUNT_BY_OWNER = "SELECT COUNT(*) FROM rb_wards WHERE owner = ?";

    static final String COUNT_BY_TEAM = "SELECT COUNT(*) FROM rb_wards WHERE team_id = ?";

    static final String COUNT_ALL = "SELECT COUNT(*) FROM rb_wards";

    // ------------------------------------------------------------------
    // Operations (all inside the caller's transaction)
    // ------------------------------------------------------------------

    public static void insert(TransactionContext tx, Ward ward) throws java.sql.SQLException {
        tx.update(INSERT, ward.id(), ward.name(), WardNameNorm.of(ward.name()),
                ward.owner(), ward.teamId().orElse(null), ward.world(),
                ward.x(), ward.y(), ward.z(), ward.radius(),
                ward.type().name(), ward.active(), ward.createdAtUtc());
    }

    public static int deleteById(TransactionContext tx, UUID id) throws java.sql.SQLException {
        return tx.update(DELETE_BY_ID, id);
    }

    public static int setActive(TransactionContext tx, UUID id, boolean active) throws java.sql.SQLException {
        return tx.update(SET_ACTIVE, active, id);
    }

    public static Optional<WardRow> byId(TransactionContext tx, UUID id) throws java.sql.SQLException {
        return tx.one(SELECT_BY_ID, WARD, id);
    }

    public static Optional<WardRow> byAnchor(TransactionContext tx, String world,
            double x, double y, double z) throws java.sql.SQLException {
        return tx.one(SELECT_BY_ANCHOR, WARD, world, x, y, z);
    }

    public static java.util.List<WardRow> all(TransactionContext tx) throws java.sql.SQLException {
        return tx.list(SELECT_ALL, WARD);
    }

    public static long countByOwner(TransactionContext tx, UUID owner) throws java.sql.SQLException {
        return tx.one(COUNT_BY_OWNER, COUNT, owner).orElse(0L);
    }

    public static long countByTeam(TransactionContext tx, UUID teamId) throws java.sql.SQLException {
        return tx.one(COUNT_BY_TEAM, COUNT, teamId).orElse(0L);
    }

    public static long countAll(TransactionContext tx) throws java.sql.SQLException {
        return tx.one(COUNT_ALL, COUNT).orElse(0L);
    }

    /** Detects a unique-constraint violation across SQLite and MySQL dialects. */
    public static boolean isUniqueViolation(java.sql.SQLException e) {
        if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
            return true;
        }
        String m = e.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("UNIQUE constraint failed") || m.contains("Duplicate entry");
    }

    /**
     * Which wards constraint fired: {@code owner_name_unique} (name),
     * {@code anchor_unique} (anchor) or null when unknown.
     */
    public static String uniqueConstraint(java.sql.SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("name_norm") || m.contains("owner_name_unique")) {
            return "owner_name_unique";
        }
        if (m.contains("anchor_unique") || (m.contains("UNIQUE constraint failed")
                && m.contains("world") && m.contains("x"))) {
            return "anchor_unique";
        }
        return null;
    }

    /** Stored normalization: trimmed lowercase (mirrors {@code WardName}). */
    static final class WardNameNorm {
        private WardNameNorm() {
        }

        static String of(String name) {
            return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
