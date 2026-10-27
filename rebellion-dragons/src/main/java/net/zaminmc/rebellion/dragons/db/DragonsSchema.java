package net.zaminmc.rebellion.dragons.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Dragons schema, version 1 (ADR-0009). One namespaced set under module key
 * {@code dragons}; portable between SQLite and MySQL (server-issued UUID
 * keys, no auto-increment, no backend-specific types).
 *
 * <p>Uniqueness constraints are the rare-asset backstops:</p>
 * <ul>
 *   <li>{@code UNIQUE slot} — allocation ordinals never collide, even when
 *       two mints race on {@code MAX(slot)+1}; the loser retries or fails,
 *       never shadows the winner.</li>
 *   <li>{@code UNIQUE (origin_world, gateway_x, gateway_y, gateway_z)} — the
 *       gateway-validity guard: one observed vanilla gateway position can
 *       mint exactly one lineage, ever. Duplicate listeners, event retries
 *       and re-observations all settle here (master prompt §5.6).</li>
 *   <li>{@code UNIQUE entity_uuid} — a living pet entity can be bound to at
 *       most one lineage row at a time.</li>
 * </ul>
 *
 * <p>The no-mint contract is structural: the registry table has no column
 * that a transfer or hatch could use to create a second row — those are
 * pure UPDATEs by shape, and only {@code LineageService.issueFromGateway}
 * ever inserts.</p>
 */
public final class DragonsSchema {

    private DragonsSchema() {
    }

    /** @return the v1 migration (identical for every backend) */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "dragons v1: gateway-backed lineage registry + audit trail",
                List.of(
                        compact("""
                        CREATE TABLE rb_dragons_lineages (
                          lineage_id CHAR(36) NOT NULL,
                          slot INT NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          owner CHAR(36) NULL,
                          egg_instance CHAR(36) NOT NULL,
                          entity_uuid CHAR(36) NULL,
                          origin_world VARCHAR(64) NOT NULL,
                          gateway_x INT NOT NULL,
                          gateway_y INT NOT NULL,
                          gateway_z INT NOT NULL,
                          health DOUBLE NULL,
                          last_pearl_heal_utc BIGINT NOT NULL DEFAULT 0,
                          created_at_utc BIGINT NOT NULL,
                          updated_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (lineage_id),
                          CONSTRAINT rb_dragons_slot_unique UNIQUE (slot),
                          CONSTRAINT rb_dragons_gateway_unique UNIQUE (origin_world, gateway_x, gateway_y, gateway_z),
                          CONSTRAINT rb_dragons_entity_unique UNIQUE (entity_uuid)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_dragons_audit (
                          id CHAR(36) NOT NULL,
                          lineage_id CHAR(36) NOT NULL,
                          action VARCHAR(32) NOT NULL,
                          actor CHAR(36) NULL,
                          detail VARCHAR(255) NULL,
                          at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id)
                        )
                        """),
                        "CREATE INDEX rb_dragons_lineages_owner_idx ON rb_dragons_lineages (owner)",
                        "CREATE INDEX rb_dragons_lineages_state_idx ON rb_dragons_lineages (state)",
                        "CREATE INDEX rb_dragons_audit_lineage_idx ON rb_dragons_audit (lineage_id)",
                        "CREATE INDEX rb_dragons_audit_at_idx ON rb_dragons_audit (at_utc)"));
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
