package net.zaminmc.rebellion.teams.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Teams schema, version 1 (ADR-0006). One namespaced set under module key
 * {@code teams}; statements are portable between SQLite and MySQL because
 * every generated key is a server-issued UUID — no auto-increment anywhere.
 *
 * <p>Uniqueness constraints are the race backstop: {@code rb_teams(name_norm)}
 * stops duplicate team names and {@code rb_team_members(player)} stops double
 * membership regardless of command interleaving.</p>
 */
public final class TeamsSchema {

    private TeamsSchema() {
    }

    /** @return the v1 migration (identical for every backend) */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "teams v1: teams, members, invites, relations, history, chat settings",
                List.of(
                        compact("""
                        CREATE TABLE rb_teams (
                          id CHAR(36) NOT NULL,
                          name_norm VARCHAR(24) NOT NULL,
                          display_name VARCHAR(24) NOT NULL,
                          leader CHAR(36) NOT NULL,
                          friendly_fire TINYINT NOT NULL DEFAULT 0,
                          created_at_utc BIGINT NOT NULL,
                          renamed_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id),
                          CONSTRAINT rb_teams_name_unique UNIQUE (name_norm)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_team_members (
                          team_id CHAR(36) NOT NULL,
                          player CHAR(36) NOT NULL,
                          rank_key VARCHAR(16) NOT NULL,
                          joined_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (team_id, player),
                          CONSTRAINT rb_team_members_player_unique UNIQUE (player),
                          CONSTRAINT rb_team_members_team_fk FOREIGN KEY (team_id)
                            REFERENCES rb_teams (id) ON DELETE CASCADE
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_team_invites (
                          id CHAR(36) NOT NULL,
                          team_id CHAR(36) NOT NULL,
                          inviter CHAR(36) NOT NULL,
                          invitee CHAR(36) NOT NULL,
                          created_at_utc BIGINT NOT NULL,
                          expires_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id),
                          CONSTRAINT rb_team_invites_team_invitee_unique UNIQUE (team_id, invitee),
                          CONSTRAINT rb_team_invites_team_fk FOREIGN KEY (team_id)
                            REFERENCES rb_teams (id) ON DELETE CASCADE
                        )
                        """),
                        "CREATE INDEX rb_team_invites_invitee_idx ON rb_team_invites (invitee, expires_at_utc)",
                        compact("""
                        CREATE TABLE rb_team_relations (
                          team_a CHAR(36) NOT NULL,
                          team_b CHAR(36) NOT NULL,
                          relation VARCHAR(8) NOT NULL,
                          changed_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (team_a, team_b),
                          CONSTRAINT rb_team_relations_a_fk FOREIGN KEY (team_a)
                            REFERENCES rb_teams (id) ON DELETE CASCADE,
                          CONSTRAINT rb_team_relations_b_fk FOREIGN KEY (team_b)
                            REFERENCES rb_teams (id) ON DELETE CASCADE
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_team_history (
                          id CHAR(36) NOT NULL,
                          team_id CHAR(36) NOT NULL,
                          actor CHAR(36) NOT NULL,
                          action VARCHAR(32) NOT NULL,
                          detail VARCHAR(255) NOT NULL,
                          recorded_at_utc BIGINT NOT NULL,
                          PRIMARY KEY (id)
                        )
                        """),
                        "CREATE INDEX rb_team_history_team_idx ON rb_team_history (team_id, recorded_at_utc)",
                        compact("""
                        CREATE TABLE rb_chat_settings (
                          player CHAR(36) NOT NULL,
                          channel VARCHAR(12) NOT NULL,
                          PRIMARY KEY (player)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_team_player_state (
                          player CHAR(36) NOT NULL,
                          last_create_utc BIGINT NOT NULL DEFAULT 0,
                          last_invite_utc BIGINT NOT NULL DEFAULT 0,
                          PRIMARY KEY (player)
                        )
                        """)));
    }

    private static String compact(String ddl) {
        return ddl.trim().replaceAll("\\s+", " ");
    }
}
