package net.zaminmc.rebellion.rpg.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * RPG schema, version 1 (ADR-0016). One namespaced set under module key
 * {@code rpg}; portable between SQLite and MySQL (enum-string states,
 * server-issued UUID keys, no auto-increment, no backend-specific types).
 *
 * <p>Structural guarantees:</p>
 * <ul>
 *   <li>{@code rb_rpg_discoveries} — {@code PRIMARY KEY (player_uuid,
 *       discovery_key)}: idempotent discovery by structure; the INSERT
 *       loser resolves to the typed no-op. No UPDATE path exists.</li>
 *   <li>{@code rb_rpg_claims} — {@code PRIMARY KEY (claim_id)}: the
 *       two-phase transactional anchor; every transition is a CAS on
 *       {@code state}.</li>
 *   <li>{@code rb_rpg_tracking_sessions} — {@code UNIQUE (claim_id)}: one
 *       session per committed claim, ever; expiry is derived
 *       ({@code expires_utc}), not a swept state column.</li>
 *   <li>{@code rb_rpg_effects} — time-bounded counter-ritual effects;
 *       active = {@code expires_utc > now}; rows are kept for forensics.</li>
 *   <li>{@code rb_rpg_encounters} — the ledger the concurrency cap,
 *       encounter cooldown, TTL cleanup and one-winner rewards are
 *       answered from; {@code state} transitions are CASes.</li>
 *   <li>{@code rb_rpg_events} — append-only audit keyed by deterministic
 *       server-composed fragments ({@code claim:<uuid>:commit} etc.);
 *       idempotent by construction.</li>
 * </ul>
 */
public final class RpgSchema {

    private RpgSchema() {
    }

    /** @return the v1 migration (identical for every backend). */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "rpg v1: discoveries + ritual claims + tracking sessions + "
                        + "effects + encounters + events",
                List.of(
                        compact("""
                        CREATE TABLE rb_rpg_discoveries (
                          player_uuid CHAR(36) NOT NULL,
                          discovery_key VARCHAR(64) NOT NULL,
                          source VARCHAR(16) NOT NULL,
                          discovered_utc BIGINT NOT NULL,
                          PRIMARY KEY (player_uuid, discovery_key)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_rpg_claims (
                          claim_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          ritual_key VARCHAR(64) NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          ingredients_digest VARCHAR(255) NOT NULL,
                          created_utc BIGINT NOT NULL,
                          resolved_utc BIGINT NULL,
                          PRIMARY KEY (claim_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_rpg_tracking_sessions (
                          session_id CHAR(36) NOT NULL,
                          claim_id CHAR(36) NOT NULL,
                          caster_uuid CHAR(36) NOT NULL,
                          target_uuid CHAR(36) NOT NULL,
                          precision VARCHAR(16) NOT NULL,
                          began_utc BIGINT NOT NULL,
                          expires_utc BIGINT NOT NULL,
                          revoked_utc BIGINT NULL,
                          revoke_reason VARCHAR(64) NULL,
                          PRIMARY KEY (session_id),
                          CONSTRAINT rb_rpg_sessions_claim_unique UNIQUE (claim_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_rpg_effects (
                          effect_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          effect_key VARCHAR(64) NOT NULL,
                          granted_utc BIGINT NOT NULL,
                          expires_utc BIGINT NOT NULL,
                          granted_by CHAR(36) NULL,
                          PRIMARY KEY (effect_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_rpg_encounters (
                          encounter_id CHAR(36) NOT NULL,
                          encounter_key VARCHAR(64) NOT NULL,
                          world VARCHAR(64) NOT NULL,
                          x INT NOT NULL,
                          y INT NOT NULL,
                          z INT NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          soul_entity CHAR(36) NULL,
                          began_utc BIGINT NOT NULL,
                          resolved_utc BIGINT NULL,
                          completed_by CHAR(36) NULL,
                          PRIMARY KEY (encounter_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_rpg_events (
                          event_key VARCHAR(128) NOT NULL,
                          player_uuid CHAR(36) NULL,
                          type VARCHAR(32) NOT NULL,
                          detail VARCHAR(255) NULL,
                          outcome VARCHAR(16) NULL,
                          at_utc BIGINT NOT NULL,
                          PRIMARY KEY (event_key)
                        )
                        """),
                        "CREATE INDEX rb_rpg_claims_player_ritual_idx "
                                + "ON rb_rpg_claims (player_uuid, ritual_key, state)",
                        "CREATE INDEX rb_rpg_claims_state_created_idx "
                                + "ON rb_rpg_claims (state, created_utc)",
                        "CREATE INDEX rb_rpg_sessions_caster_idx "
                                + "ON rb_rpg_tracking_sessions (caster_uuid)",
                        "CREATE INDEX rb_rpg_sessions_target_idx "
                                + "ON rb_rpg_tracking_sessions (target_uuid)",
                        "CREATE INDEX rb_rpg_effects_player_idx "
                                + "ON rb_rpg_effects (player_uuid, effect_key)",
                        "CREATE INDEX rb_rpg_encounters_key_state_idx "
                                + "ON rb_rpg_encounters (encounter_key, state)",
                        "CREATE INDEX rb_rpg_events_player_idx "
                                + "ON rb_rpg_events (player_uuid, at_utc)"));
    }

    /** @return every shipped migration, oldest first (Core refuses downgrades). */
    public static List<MigrationSpec> migrations() {
        return List.of(v1());
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
