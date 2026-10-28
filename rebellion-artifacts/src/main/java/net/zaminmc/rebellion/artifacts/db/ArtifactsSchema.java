package net.zaminmc.rebellion.artifacts.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Artifacts schema, version 1 (ADR-0013). One namespaced set under module key
 * {@code artifacts}; portable between SQLite and MySQL (dimension-key enum
 * strings, server-issued UUID keys, no auto-increment, no backend-specific
 * types).
 *
 * <p>Structural guarantees:</p>
 * <ul>
 *   <li>{@code PRIMARY KEY (artifact_key)} — exactly one row per dimension
 *       artifact, seeded only by the idempotent load-time seed. There is no
 *       gameplay INSERT path anywhere, so "exactly three" cannot drift.</li>
 *   <li>{@code UNIQUE (artifact_id)} — the PDC item-family identity can never
 *       be shared by two rows.</li>
 *   <li>{@code rb_artifacts_event_guard} — a singleton row (PK on a
 *       constant) holding the currently active event id. "At most one active
 *       Rebellion occurrence" is enforced by the single-row-ness plus the
 *       acquire/release compare-and-sets, not by event-layer dedup code.</li>
 * </ul>
 *
 * <p>The instance column is the anti-clone backstop: the physical item
 * carries the live stamp; every possession-changing transition supersedes it,
 * so cloned/stale copies stop resolving (same discipline as the dragons
 * {@code egg_instance}).</p>
 */
public final class ArtifactsSchema {

    private ArtifactsSchema() {
    }

    /** @return the v1 migration (identical for every backend) */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "artifacts v1: dimension-keyed artifact registry + audit + "
                        + "rebellion events/guard",
                List.of(
                        compact("""
                        CREATE TABLE rb_artifacts_registry (
                          artifact_key VARCHAR(16) NOT NULL,
                          artifact_id CHAR(36) NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          holder CHAR(36) NULL,
                          instance CHAR(36) NOT NULL,
                          origin_world VARCHAR(64) NOT NULL,
                          origin_x INT NOT NULL,
                          origin_y INT NOT NULL,
                          origin_z INT NOT NULL,
                          holder_since_utc BIGINT NOT NULL DEFAULT 0,
                          state_since_utc BIGINT NOT NULL,
                          created_at_utc BIGINT NOT NULL,
                          updated_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (artifact_key),
                          CONSTRAINT rb_artifacts_id_unique UNIQUE (artifact_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_artifacts_audit (
                          id CHAR(36) NOT NULL,
                          artifact_key VARCHAR(16) NOT NULL,
                          action VARCHAR(32) NOT NULL,
                          actor CHAR(36) NULL,
                          detail VARCHAR(255) NULL,
                          at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_artifacts_events (
                          event_id CHAR(36) NOT NULL,
                          trigger_holder CHAR(36) NOT NULL,
                          reason VARCHAR(255) NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          initiated_at_utc BIGINT NOT NULL,
                          completed_at_utc BIGINT NULL,
                          PRIMARY KEY (event_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_artifacts_event_guard (
                          singleton INT NOT NULL,
                          active_event_id CHAR(36) NULL,
                          PRIMARY KEY (singleton)
                        )
                        """),
                        "CREATE INDEX rb_artifacts_registry_state_idx "
                                + "ON rb_artifacts_registry (state)",
                        "CREATE INDEX rb_artifacts_registry_holder_idx "
                                + "ON rb_artifacts_registry (holder)",
                        "CREATE INDEX rb_artifacts_audit_key_idx "
                                + "ON rb_artifacts_audit (artifact_key)",
                        "CREATE INDEX rb_artifacts_audit_at_idx "
                                + "ON rb_artifacts_audit (at_utc)",
                        "CREATE INDEX rb_artifacts_events_state_idx "
                                + "ON rb_artifacts_events (state)"));
    }

    /**
     * @return the v2 migration (ADR-0015 §4): the additive purge stamp on
     *         the event row. The NULL → now compare-and-set makes the
     *         world-wide kill exactly-once per occurrence.
     */
    public static MigrationSpec v2() {
        return new MigrationSpec(2,
                "artifacts v2: once-per-occurrence purge execution stamp "
                        + "(ADR-0015)",
                List.of("ALTER TABLE rb_artifacts_events "
                        + "ADD COLUMN purge_executed_utc BIGINT NULL"));
    }

    /** @return every shipped migration, oldest first (Core refuses downgrades). */
    public static List<MigrationSpec> migrations() {
        return List.of(v1(), v2());
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
