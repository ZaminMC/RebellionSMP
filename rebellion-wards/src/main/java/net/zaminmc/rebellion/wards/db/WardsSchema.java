package net.zaminmc.rebellion.wards.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Wards schema, version 1 (ADR-0007). One namespaced set under module key
 * {@code wards}; portable between SQLite and MySQL (server-issued UUID
 * keys, no auto-increment, no backend-specific types).
 *
 * <p>Uniqueness constraints are the race backstops:</p>
 * <ul>
 *   <li>{@code UNIQUE (owner, name_norm)} — per-owner ward names are
 *       case-insensitively unique regardless of command interleaving.</li>
 *   <li>{@code UNIQUE (world, x, y, z)} — one ward per placed anchor block;
 *       two players targeting the same anchor cannot both bind it.</li>
 * </ul>
 *
 * <p>The no-claim contract is structural: the table has no protection flags,
 * no region columns, no member lists — nothing that could express claim
 * semantics even by misuse.</p>
 */
public final class WardsSchema {

    private WardsSchema() {
    }

    /** @return the v1 migration (identical for every backend) */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "wards v1: watch wards bound to anchor blocks",
                List.of(
                        compact("""
                        CREATE TABLE rb_wards (
                          id CHAR(36) NOT NULL,
                          name VARCHAR(24) NOT NULL,
                          name_norm VARCHAR(24) NOT NULL,
                          owner CHAR(36) NOT NULL,
                          team_id CHAR(36) NULL,
                          world VARCHAR(64) NOT NULL,
                          x DOUBLE NOT NULL,
                          y DOUBLE NOT NULL,
                          z DOUBLE NOT NULL,
                          radius DOUBLE NOT NULL,
                          type VARCHAR(8) NOT NULL,
                          active TINYINT NOT NULL DEFAULT 1,
                          created_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id),
                          CONSTRAINT rb_wards_owner_name_unique UNIQUE (owner, name_norm),
                          CONSTRAINT rb_wards_anchor_unique UNIQUE (world, x, y, z)
                        )
                        """),
                        "CREATE INDEX rb_wards_owner_idx ON rb_wards (owner)",
                        "CREATE INDEX rb_wards_team_idx ON rb_wards (team_id)",
                        "CREATE INDEX rb_wards_active_world_idx ON rb_wards (active, world)"));
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
