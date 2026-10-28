package net.zaminmc.rebellion.dragons.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageState;

/**
 * Lineage SQL surface (schema v1, ADR-0009). Statements are constants; every
 * mutation runs inside a caller-supplied transaction. State transitions are
 * <strong>compare-and-set</strong> statements: the {@code WHERE} clause pins
 * the expected prior state plus the expected live egg/entity identity, so
 * races and duplicate events resolve through the affected-row count — one
 * winner, typed no-ops for everyone else. Only {@link #insert} can create a
 * registry row (issuance); everything else is an UPDATE.
 */
public final class LineageStore {

    private LineageStore() {
    }

    // ------------------------------------------------------------------
    // Views + mapping
    // ------------------------------------------------------------------

    /** Full registry row; nullable columns surface as Optionals. */
    public record LineageRow(UUID lineageId, int slot, LineageState state, UUID owner,
            UUID eggInstance, UUID entityUuid, String originWorld, int gatewayX,
            int gatewayY, int gatewayZ, Double health, long lastPearlHealUtc,
            String locWorld, Double locX, Double locY, Double locZ,
            long lastPersistUtc, long createdAtUtc, long updatedAtUtc) {

        public Lineage toDomain() {
            return new Lineage(lineageId, slot, state, owner, eggInstance, entityUuid,
                    originWorld, gatewayX, gatewayY, gatewayZ, health, lastPearlHealUtc,
                    locWorld, locX, locY, locZ, lastPersistUtc, createdAtUtc, updatedAtUtc);
        }
    }

    public static final RowMapper<LineageRow> LINEAGE = rs -> new LineageRow(
            UUID.fromString(rs.getString(1)),
            rs.getInt(2),
            LineageState.parse(rs.getString(3)),
            rs.getString(4) == null ? null : UUID.fromString(rs.getString(4)),
            UUID.fromString(rs.getString(5)),
            rs.getString(6) == null ? null : UUID.fromString(rs.getString(6)),
            rs.getString(7),
            rs.getInt(8),
            rs.getInt(9),
            rs.getInt(10),
            rs.getObject(11) == null ? null : rs.getDouble(11),
            rs.getLong(12),
            rs.getString(13),
            rs.getObject(14) == null ? null : rs.getDouble(14),
            rs.getObject(15) == null ? null : rs.getDouble(15),
            rs.getObject(16) == null ? null : rs.getDouble(16),
            rs.getLong(17),
            rs.getLong(18),
            rs.getLong(19));

    /** Audit row (admin forensics; written inside mutation transactions). */
    public record AuditRow(UUID id, UUID lineageId, String action, UUID actor,
            String detail, long atUtc) {
    }

    public static final RowMapper<AuditRow> AUDIT = rs -> new AuditRow(
            UUID.fromString(rs.getString(1)),
            UUID.fromString(rs.getString(2)),
            rs.getString(3),
            rs.getString(4) == null ? null : UUID.fromString(rs.getString(4)),
            rs.getString(5),
            rs.getLong(6));

    static final RowMapper<Long> COUNT = rs -> rs.getLong(1);
    static final RowMapper<Integer> MAX_SLOT = rs -> rs.getInt(1) == 0 && rs.wasNull()
            ? 0 : rs.getInt(1);

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    static final String LINEAGE_COLUMNS = "lineage_id, slot, state, owner, egg_instance, "
            + "entity_uuid, origin_world, gateway_x, gateway_y, gateway_z, health, "
            + "last_pearl_heal_utc, loc_world, loc_x, loc_y, loc_z, last_persist_utc, "
            + "created_at_utc, updated_at_utc";

    /** Issuance-only insert; the UNIQUE backstops settle mint races. */
    public static final String INSERT = "INSERT INTO rb_dragons_lineages "
            + "(lineage_id, slot, state, owner, egg_instance, entity_uuid, origin_world, "
            + "gateway_x, gateway_y, gateway_z, health, last_pearl_heal_utc, loc_world, "
            + "loc_x, loc_y, loc_z, last_persist_utc, created_at_utc, updated_at_utc) "
            + "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, 0, NULL, NULL, NULL, NULL, "
            + "0, ?, ?)";

    public static final String SELECT_BY_ID = "SELECT " + LINEAGE_COLUMNS
            + " FROM rb_dragons_lineages WHERE lineage_id = ?";

    public static final String SELECT_BY_GATEWAY = "SELECT " + LINEAGE_COLUMNS
            + " FROM rb_dragons_lineages WHERE origin_world = ? AND gateway_x = ? "
            + "AND gateway_y = ? AND gateway_z = ?";

    public static final String SELECT_ALL = "SELECT " + LINEAGE_COLUMNS
            + " FROM rb_dragons_lineages ORDER BY slot";

    static final String COUNT_ALL = "SELECT COUNT(*) FROM rb_dragons_lineages";

    static final String MAX_SLOT_ALL = "SELECT MAX(slot) FROM rb_dragons_lineages";

    /** Hatch CAS: only the live egg instance, still in an egg state, wins. */
    public static final String CAS_HATCH =
            "UPDATE rb_dragons_lineages SET state = 'HATCHED', owner = ?, entity_uuid = ?, "
            + "health = ?, updated_at_utc = ? WHERE lineage_id = ? AND egg_instance = ? "
            + "AND state IN ('EGG_HELD', 'EGG_RECOVERY')";

    /** Death CAS: only the currently bound living entity can return to egg. */
    public static final String CAS_DEATH =
            "UPDATE rb_dragons_lineages SET state = ?, owner = ?, egg_instance = ?, "
            + "entity_uuid = NULL, health = NULL, updated_at_utc = ? "
            + "WHERE lineage_id = ? AND entity_uuid = ? AND state = 'HATCHED'";

    /** Undeliverable-delivery CAS: a held egg parks in the recovery mailbox. */
    public static final String CAS_UNDELIVERABLE =
            "UPDATE rb_dragons_lineages SET state = 'EGG_RECOVERY', updated_at_utc = ? "
            + "WHERE lineage_id = ? AND state = 'EGG_HELD'";

    /** Claim CAS: only the recorded claimant can claim a recovery egg. */
    public static final String CAS_CLAIM =
            "UPDATE rb_dragons_lineages SET state = 'EGG_HELD', egg_instance = ?, "
            + "updated_at_utc = ? WHERE lineage_id = ? AND state = 'EGG_RECOVERY' AND owner = ?";

    /** Admin route CAS: any egg state re-issues to a chosen holder. */
    public static final String CAS_ROUTE =
            "UPDATE rb_dragons_lineages SET state = 'EGG_HELD', owner = ?, egg_instance = ?, "
            + "updated_at_utc = ? WHERE lineage_id = ? AND state IN ('EGG_HELD', 'EGG_RECOVERY')";

    /** Pearl-heal CAS: only a HATCHED row bound to this entity, whose heal
     *  cooldown has elapsed, may record the heal (idempotency backstop for
     *  event retries — a second attempt inside the cooldown affects 0 rows). */
    public static final String CAS_PEARL_HEAL =
            "UPDATE rb_dragons_lineages SET health = ?, last_pearl_heal_utc = ?, "
            + "updated_at_utc = ? WHERE lineage_id = ? AND entity_uuid = ? "
            + "AND state = 'HATCHED' AND last_pearl_heal_utc <= ?";

    /** Snapshot CAS: the periodic controller persist pins the bound entity,
     *  so a snapshot of a superseded/dead binding can never overwrite. */
    public static final String CAS_SNAPSHOT =
            "UPDATE rb_dragons_lineages SET health = ?, loc_world = ?, loc_x = ?, "
            + "loc_y = ?, loc_z = ?, last_persist_utc = ?, updated_at_utc = ? "
            + "WHERE lineage_id = ? AND entity_uuid = ? AND state = 'HATCHED'";

    /** Retire CAS: once, from any non-retired state; clears all bindings. */
    public static final String CAS_RETIRE =
            "UPDATE rb_dragons_lineages SET state = 'RETIRED', owner = NULL, entity_uuid = NULL, "
            + "health = NULL, updated_at_utc = ? WHERE lineage_id = ? AND state <> 'RETIRED'";

    public static final String INSERT_AUDIT = "INSERT INTO rb_dragons_audit "
            + "(id, lineage_id, action, actor, detail, at_utc) VALUES (?, ?, ?, ?, ?, ?)";

    public static final String SELECT_AUDIT_BY_LINEAGE = "SELECT id, lineage_id, action, "
            + "actor, detail, at_utc FROM rb_dragons_audit WHERE lineage_id = ? ORDER BY at_utc";

    // ------------------------------------------------------------------
    // Operations (all inside the caller's transaction)
    // ------------------------------------------------------------------

    public static void insert(TransactionContext tx, UUID lineageId, int slot, UUID owner,
            UUID eggInstance, String originWorld, int gatewayX, int gatewayY, int gatewayZ,
            long nowUtc) throws SQLException {
        tx.update(INSERT, lineageId, slot, LineageState.EGG_HELD.name(), owner, eggInstance,
                originWorld, gatewayX, gatewayY, gatewayZ, nowUtc, nowUtc);
    }

    public static Optional<LineageRow> byId(TransactionContext tx, UUID lineageId)
            throws SQLException {
        return tx.one(SELECT_BY_ID, LINEAGE, lineageId);
    }

    public static Optional<LineageRow> byGateway(TransactionContext tx, String world,
            int x, int y, int z) throws SQLException {
        return tx.one(SELECT_BY_GATEWAY, LINEAGE, world, x, y, z);
    }

    public static List<LineageRow> all(TransactionContext tx) throws SQLException {
        return tx.list(SELECT_ALL, LINEAGE);
    }

    public static long countAll(TransactionContext tx) throws SQLException {
        return tx.one(COUNT_ALL, COUNT).orElse(0L);
    }

    public static int maxSlot(TransactionContext tx) throws SQLException {
        return tx.one(MAX_SLOT_ALL, MAX_SLOT).orElse(0);
    }

    public static int hatch(TransactionContext tx, UUID owner, UUID entityUuid,
            double health, long nowUtc, UUID lineageId, UUID eggInstance) throws SQLException {
        return tx.update(CAS_HATCH, owner, entityUuid, health, nowUtc, lineageId, eggInstance);
    }

    public static int death(TransactionContext tx, LineageState newState, UUID newOwner,
            UUID newEggInstance, long nowUtc, UUID lineageId, UUID entityUuid)
            throws SQLException {
        return tx.update(CAS_DEATH, newState.name(), newOwner, newEggInstance, nowUtc,
                lineageId, entityUuid);
    }

    public static int markUndeliverable(TransactionContext tx, UUID lineageId, long nowUtc)
            throws SQLException {
        return tx.update(CAS_UNDELIVERABLE, nowUtc, lineageId);
    }

    public static int claim(TransactionContext tx, UUID lineageId, UUID claimant,
            UUID newEggInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_CLAIM, newEggInstance, nowUtc, lineageId, claimant);
    }

    public static int route(TransactionContext tx, UUID lineageId, UUID newHolder,
            UUID newEggInstance, long nowUtc) throws SQLException {
        return tx.update(CAS_ROUTE, newHolder, newEggInstance, nowUtc, lineageId);
    }

    public static int retire(TransactionContext tx, UUID lineageId, long nowUtc)
            throws SQLException {
        return tx.update(CAS_RETIRE, nowUtc, lineageId);
    }

    /** Pearl-heal compare-and-set; 0 rows = cooldown active, stale binding or wrong state. */
    public static int pearlHeal(TransactionContext tx, UUID lineageId, UUID entityUuid,
            double health, long healedAtUtc, long cooldownFloorUtc, long nowUtc)
            throws SQLException {
        return tx.update(CAS_PEARL_HEAL, health, healedAtUtc, nowUtc, lineageId,
                entityUuid, cooldownFloorUtc);
    }

    /** Controller snapshot compare-and-set on the bound entity. */
    public static int snapshot(TransactionContext tx, UUID lineageId, UUID entityUuid,
            Double health, String world, Double x, Double y, Double z,
            long persistUtc, long nowUtc) throws SQLException {
        return tx.update(CAS_SNAPSHOT, health, world, x, y, z, persistUtc, nowUtc,
                lineageId, entityUuid);
    }

    public static void audit(TransactionContext tx, UUID lineageId, String action,
            UUID actor, String detail, long atUtc) throws SQLException {
        tx.update(INSERT_AUDIT, UUID.randomUUID(), lineageId, action, actor, detail, atUtc);
    }

    public static List<AuditRow> auditTrail(TransactionContext tx, UUID lineageId)
            throws SQLException {
        return tx.list(SELECT_AUDIT_BY_LINEAGE, AUDIT, lineageId);
    }

    // ------------------------------------------------------------------
    // Constraint classification (SQLite + MySQL dialects)
    // ------------------------------------------------------------------

    /** Detects a unique-constraint violation across SQLite and MySQL dialects. */
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

    /**
     * Which lineages constraint fired: {@code gateway_unique}, {@code slot_unique},
     * {@code entity_unique} or null when unknown.
     */
    public static String uniqueConstraint(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("gateway_unique") || (m.contains("UNIQUE constraint failed")
                && m.contains("origin_world"))) {
            return "gateway_unique";
        }
        if (m.contains("slot_unique") || (m.contains("UNIQUE constraint failed")
                && m.contains("slot"))) {
            return "slot_unique";
        }
        if (m.contains("entity_unique") || (m.contains("UNIQUE constraint failed")
                && m.contains("entity_uuid"))) {
            return "entity_unique";
        }
        return null;
    }
}
