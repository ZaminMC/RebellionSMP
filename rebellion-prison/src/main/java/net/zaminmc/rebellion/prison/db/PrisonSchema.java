package net.zaminmc.rebellion.prison.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Prison schema, version 1 (ADR-0021). One namespaced set under module key
 * {@code prison}; portable between SQLite and MySQL (enum-string states,
 * server-issued UUID keys, no auto-increment, no backend-specific types).
 *
 * <p>Structural guarantees:</p>
 * <ul>
 *   <li>{@code rb_prison_profiles} — {@code PRIMARY KEY (player_uuid)}:
 *       the death-score and health-scar ledgers. Both scar columns are
 *       write-clamped at mutation time (scars never exceed the configured
 *       active-penalty ceiling; restoration never exceeds scars), so the
 *       stored rows are always consistent and nothing is ever swept.</li>
 *   <li>{@code rb_prison_deaths} — {@code PRIMARY KEY (death_key)}: the
 *       idempotency anchor for death processing (§5.8: "Do not deduct
 *       health twice because death and respawn events both fire or
 *       because the player logs back in"). The controller composes one
 *       deterministic key per physical death; a replay loses the PK.</li>
 *   <li>{@code rb_prison_sentences} — {@code PRIMARY KEY (sentence_id)}
 *       with {@code UNIQUE (live_guard)}: the portable partial-unique
 *       emulation. {@code live_guard} carries the player UUID while the
 *       sentence is PENDING/JAILED and is nulled on release, so exactly
 *       one live sentence can exist per player (a death in prison scores
 *       and scars but can never double-book a second sentence) while
 *       unlimited released history rows survive.</li>
 *   <li>{@code rb_prison_fees} — {@code PRIMARY KEY (fee_id)} with
 *       {@code UNIQUE (sentence_id)}: the §5.9 structured audit of the
 *       release-fee calculation (score, factor, multiplier, rounding,
 *       clamp, result, provider, ledger entry) doubling as the claim row
 *       of the pay protocol — QUOTED → PAID | VOID, all CAS.</li>
 *   <li>{@code rb_prison_rehab} — {@code PRIMARY KEY (player_uuid,
 *       task_key, completion_key)}: structurally idempotent task
 *       completions; the occurrence counter and clamped heal amount ride
 *       the row.</li>
 *   <li>{@code rb_prison_events} — append-only audit keyed by
 *       deterministic server-composed fragments; idempotent by
 *       construction and written inside the same transaction as the
 *       mutation it describes.</li>
 * </ul>
 */
public final class PrisonSchema {

    private PrisonSchema() {
    }

    /** @return the v1 migration (identical for every backend). */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "prison v1: profiles + deaths + sentences + fees + rehab + events",
                List.of(
                        compact("""
                        CREATE TABLE rb_prison_profiles (
                          player_uuid CHAR(36) NOT NULL,
                          death_score BIGINT NOT NULL,
                          scar_points INT NOT NULL,
                          restored_points INT NOT NULL,
                          updated_utc BIGINT NOT NULL,
                          PRIMARY KEY (player_uuid)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_prison_deaths (
                          death_key VARCHAR(96) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          score_delta BIGINT NOT NULL,
                          health_penalty INT NOT NULL,
                          created_utc BIGINT NOT NULL,
                          PRIMARY KEY (death_key)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_prison_sentences (
                          sentence_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          state VARCHAR(24) NOT NULL,
                          jail_world VARCHAR(64) NOT NULL,
                          jail_x BIGINT NOT NULL,
                          jail_y BIGINT NOT NULL,
                          jail_z BIGINT NOT NULL,
                          jail_yaw INT NOT NULL,
                          jail_pitch INT NOT NULL,
                          live_guard CHAR(36) NULL,
                          created_utc BIGINT NOT NULL,
                          released_utc BIGINT NULL,
                          release_reason VARCHAR(24) NULL,
                          PRIMARY KEY (sentence_id),
                          CONSTRAINT rb_prison_sentences_live_unique
                            UNIQUE (live_guard)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_prison_fees (
                          fee_id CHAR(36) NOT NULL,
                          sentence_id CHAR(36) NOT NULL,
                          player_uuid CHAR(36) NOT NULL,
                          state VARCHAR(16) NOT NULL,
                          death_score BIGINT NOT NULL,
                          inflation_factor VARCHAR(32) NOT NULL,
                          multiplier INT NOT NULL,
                          rounding VARCHAR(16) NOT NULL,
                          clamp VARCHAR(16) NOT NULL,
                          amount_minor BIGINT NOT NULL,
                          provider VARCHAR(32) NULL,
                          ledger_entry CHAR(36) NULL,
                          quoted_utc BIGINT NOT NULL,
                          resolved_utc BIGINT NULL,
                          PRIMARY KEY (fee_id),
                          CONSTRAINT rb_prison_fees_sentence_unique
                            UNIQUE (sentence_id)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_prison_rehab (
                          player_uuid CHAR(36) NOT NULL,
                          task_key VARCHAR(32) NOT NULL,
                          completion_key VARCHAR(96) NOT NULL,
                          occurrence INT NOT NULL,
                          healed_points INT NOT NULL,
                          created_utc BIGINT NOT NULL,
                          PRIMARY KEY (player_uuid, task_key, completion_key)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_prison_events (
                          event_key VARCHAR(160) NOT NULL,
                          player_uuid CHAR(36) NULL,
                          type VARCHAR(32) NOT NULL,
                          detail VARCHAR(255) NOT NULL,
                          outcome VARCHAR(48) NOT NULL,
                          at_utc BIGINT NOT NULL,
                          PRIMARY KEY (event_key)
                        )
                        """)));
    }

    /** @return the full registry, versions 1..N (monotonic, gap-free). */
    public static List<MigrationSpec> migrations() {
        return List.of(v1());
    }

    private static String compact(String sql) {
        return sql.replaceAll("\n\s*", " ").trim();
    }
}
