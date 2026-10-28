package net.zaminmc.rebellion.alchemy.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Alchemy schema, version 1 (ADR-0019). One namespaced set under module key
 * {@code alchemy}; portable between SQLite and MySQL (enum-string states,
 * server-issued UUID keys, no auto-increment, no backend-specific types).
 *
 * <p>Structural guarantees:</p>
 * <ul>
 *   <li>{@code rb_alchemy_progress} — {@code PRIMARY KEY (player_uuid)}:
 *       the single atomic accumulate of total XP. The <em>level is
 *       derived</em> from it through the pure {@code Leveling} walk —
 *       never stored, never swept, never drifting.</li>
 *   <li>{@code rb_alchemy_unlocks} — {@code PRIMARY KEY (player_uuid,
 *       unlock_key)}: idempotent grant by structure; the INSERT loser
 *       resolves to a typed outcome. No UPDATE path exists.</li>
 *   <li>{@code rb_alchemy_rules} — {@code PRIMARY KEY (rule_id)}:
 *       player-defined auto-consumption rules. {@code last_fired_utc} is
 *       the cooldown authority (stamped only by committed executions);
 *       removals stamp {@code removed_utc} instead of deleting.</li>
 *   <li>{@code rb_alchemy_claims} — {@code PRIMARY KEY (claim_id)}: the
 *       two-phase transactional anchor for both inventory-spanning
 *       operations (unlock purchases, auto-consume executions); every
 *       transition is a CAS on {@code state}.</li>
 *   <li>{@code rb_alchemy_events} — append-only audit keyed by deterministic
 *       server-composed fragments ({@code xp:<player>:<source>},
 *       {@code claim:<uuid>:commit} etc.); idempotent by construction.</li>
 * </ul>
 */
public final class AlchemySchema {

    private AlchemySchema() {
    }

    /** @return the v1 migration (identical for every backend). */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "alchemy v1: progress + unlocks + auto rules + claims + events",
                List.of(
                        compact("""
                        CREATE TABLE rb_alchemy_progress (
                          player_uuid CHAR(36) NOT NULL,
                          total_xp BIGINT NOT NULL,
                          updated_utc BIGINT NOT NULL,
                          PRIMARY KEY (player_uuid)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_alchemy_unlocks (
                          player_uuid CHAR(36) NOT NULL,
                          unlock_key VARCHAR(64) NOT NULL,
                          granted_utc BIGINT NOT NULL,
                          claim_id CHAR(36) NULL,
                          PRIMARY KEY (player_uuid, unlock_key)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_alchemy_rules (
                          rule_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          trigger VARCHAR(16) NOT NULL,
                          potion_type VARCHAR(32) NOT NULL,
                          threshold INT NULL,
                          cooldown_seconds INT NOT NULL,
                          priority INT NOT NULL,
                          storage VARCHAR(16) NOT NULL,
                          enabled BOOLEAN NOT NULL,
                          created_utc BIGINT NOT NULL,
                          updated_utc BIGINT NOT NULL,
                          removed_utc BIGINT NULL,
                          last_fired_utc BIGINT NULL,
                          PRIMARY KEY (rule_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_alchemy_claims (
                          claim_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          purpose VARCHAR(16) NOT NULL,
                          subject_key VARCHAR(64) NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          digest VARCHAR(255) NOT NULL,
                          created_utc BIGINT NOT NULL,
                          resolved_utc BIGINT NULL,
                          PRIMARY KEY (claim_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_alchemy_events (
                          event_key VARCHAR(160) NOT NULL,
                          player_uuid CHAR(36) NULL,
                          type VARCHAR(32) NOT NULL,
                          detail VARCHAR(255) NULL,
                          outcome VARCHAR(16) NULL,
                          at_utc BIGINT NOT NULL,
                          PRIMARY KEY (event_key)
                        )
                        """),
                        "CREATE INDEX rb_alchemy_unlocks_player_idx "
                                + "ON rb_alchemy_unlocks (player_uuid)",
                        "CREATE INDEX rb_alchemy_rules_player_idx "
                                + "ON rb_alchemy_rules (player_uuid, enabled, "
                                + "removed_utc)",
                        "CREATE INDEX rb_alchemy_rules_priority_idx "
                                + "ON rb_alchemy_rules (player_uuid, priority, "
                                + "created_utc)",
                        "CREATE INDEX rb_alchemy_claims_guard_idx "
                                + "ON rb_alchemy_claims (player_uuid, purpose, "
                                + "subject_key, state)",
                        "CREATE INDEX rb_alchemy_claims_state_created_idx "
                                + "ON rb_alchemy_claims (state, created_utc)",
                        "CREATE INDEX rb_alchemy_events_player_idx "
                                + "ON rb_alchemy_events (player_uuid, at_utc)"));
    }

    /** @return every shipped migration, oldest first (Core refuses downgrades). */
    public static List<MigrationSpec> migrations() {
        return List.of(v1());
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
