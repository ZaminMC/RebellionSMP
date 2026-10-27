package net.zaminmc.rebellion.teams.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.api.database.RowMapper;
import net.zaminmc.rebellion.api.database.TransactionContext;

/**
 * Teams SQL surface (schema v1, ADR-0006). Every statement is a constant and
 * every operation runs inside a caller-supplied transaction, so rank checks
 * and mutations are atomic against the same connection. Constraint
 * violations surface as SQLException and are mapped by the service — the
 * schema's UNIQUE constraints are the race backstop.
 */
public final class TeamStore {

    private TeamStore() {
    }

    // ------------------------------------------------------------------
    // Views (immutable row records)
    // ------------------------------------------------------------------

    public record TeamRow(UUID id, String nameNorm, String displayName, UUID leader,
            boolean friendlyFire, long createdAtUtc, long renamedAtUtc) {
    }

    public record MembershipRow(UUID teamId, UUID player, String rankKey, long joinedAtUtc) {
    }

    public record InviteRow(UUID id, UUID teamId, UUID inviter, UUID invitee,
            long createdAtUtc, long expiresAtUtc) {
    }

    public record RelationRow(UUID teamA, UUID teamB, String relation, long changedAtUtc) {
    }

    public record HistoryRow(UUID id, UUID teamId, UUID actor, String action, String detail,
            long recordedAtUtc) {
    }

    public record ChatRow(UUID player, String channel) {
    }

    // ------------------------------------------------------------------
    // Row mappers
    // ------------------------------------------------------------------

    static final RowMapper<TeamRow> TEAM = rs -> new TeamRow(
            UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
            UUID.fromString(rs.getString(4)), rs.getInt(5) != 0, rs.getLong(6), rs.getLong(7));

    static final RowMapper<MembershipRow> MEMBERSHIP = rs -> new MembershipRow(
            UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
            rs.getString(3), rs.getLong(4));

    static final RowMapper<InviteRow> INVITE = rs -> new InviteRow(
            UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
            UUID.fromString(rs.getString(3)), UUID.fromString(rs.getString(4)),
            rs.getLong(5), rs.getLong(6));

    static final RowMapper<RelationRow> RELATION = rs -> new RelationRow(
            UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
            rs.getString(3), rs.getLong(4));

    static final RowMapper<HistoryRow> HISTORY = rs -> new HistoryRow(
            UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
            UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getLong(6));

    static final RowMapper<String> CHANNEL = rs -> rs.getString(1);

    // ------------------------------------------------------------------
    // Teams
    // ------------------------------------------------------------------

    static final String INSERT_TEAM =
            "INSERT INTO rb_teams (id, name_norm, display_name, leader, friendly_fire,"
            + " created_at_utc, renamed_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)";

    static final String UPDATE_TEAM_NAME =
            "UPDATE rb_teams SET display_name = ?, name_norm = ?, renamed_at_utc = ? WHERE id = ?";

    static final String UPDATE_TEAM_LEADER = "UPDATE rb_teams SET leader = ? WHERE id = ?";

    static final String UPDATE_TEAM_FRIENDLY_FIRE =
            "UPDATE rb_teams SET friendly_fire = ? WHERE id = ?";

    static final String DELETE_TEAM = "DELETE FROM rb_teams WHERE id = ?";

    static final String SELECT_TEAM_BY_ID =
            "SELECT id, name_norm, display_name, leader, friendly_fire, created_at_utc,"
            + " renamed_at_utc FROM rb_teams WHERE id = ?";

    static final String SELECT_TEAM_BY_NAME =
            "SELECT id, name_norm, display_name, leader, friendly_fire, created_at_utc,"
            + " renamed_at_utc FROM rb_teams WHERE name_norm = ?";

    static final String COUNT_MEMBERS = "SELECT COUNT(*) FROM rb_team_members WHERE team_id = ?";

    public static void insertTeam(TransactionContext tx, TeamRow team) throws SQLException {
        tx.update(INSERT_TEAM, team.id(), team.nameNorm(), team.displayName(), team.leader(),
                team.friendlyFire(), team.createdAtUtc(), team.renamedAtUtc());
    }

    /** Deletes a team; cascades remove memberships, invites and relations. */
    public static void deleteTeam(TransactionContext tx, UUID teamId) throws SQLException {
        tx.update(DELETE_TEAM, teamId);
    }

    /** Renames a team (display + normalized names, cooldown timestamp). */
    public static void updateTeamName(TransactionContext tx, String displayName,
            String nameNorm, long renamedAtUtc, UUID teamId) throws SQLException {
        tx.update(UPDATE_TEAM_NAME, displayName, nameNorm, renamedAtUtc, teamId);
    }

    /** Replaces the leader reference (transfer). */
    public static void updateTeamLeader(TransactionContext tx, UUID teamId, UUID newLeader)
            throws SQLException {
        tx.update(UPDATE_TEAM_LEADER, newLeader, teamId);
    }

    /** Toggles team-wide friendly fire. */
    public static void updateTeamFriendlyFire(TransactionContext tx, boolean enabled,
            UUID teamId) throws SQLException {
        tx.update(UPDATE_TEAM_FRIENDLY_FIRE, enabled, teamId);
    }

    public static Optional<TeamRow> teamById(TransactionContext tx, UUID teamId)
            throws SQLException {
        return tx.one(SELECT_TEAM_BY_ID, TEAM, teamId);
    }

    public static Optional<TeamRow> teamByName(TransactionContext tx, String nameNorm)
            throws SQLException {
        return tx.one(SELECT_TEAM_BY_NAME, TEAM, nameNorm);
    }

    public static int memberCount(TransactionContext tx, UUID teamId) throws SQLException {
        return tx.one("SELECT COUNT(*) FROM rb_team_members WHERE team_id = ?",
                rs -> rs.getInt(1), teamId).orElse(0);
    }

    // ------------------------------------------------------------------
    // Memberships
    // ------------------------------------------------------------------

    static final String INSERT_MEMBERSHIP =
            "INSERT INTO rb_team_members (team_id, player, rank_key, joined_at_utc)"
            + " VALUES (?, ?, ?, ?)";

    static final String DELETE_MEMBERSHIP =
            "DELETE FROM rb_team_members WHERE team_id = ? AND player = ?";

    static final String UPDATE_RANK = "UPDATE rb_team_members SET rank_key = ? WHERE player = ?";

    static final String SELECT_MEMBERSHIP_BY_PLAYER =
            "SELECT team_id, player, rank_key, joined_at_utc FROM rb_team_members WHERE player = ?";

    static final String SELECT_MEMBERSHIP_IN_TEAM =
            "SELECT team_id, player, rank_key, joined_at_utc FROM rb_team_members"
            + " WHERE team_id = ? AND player = ?";

    static final String SELECT_MEMBERS =
            "SELECT team_id, player, rank_key, joined_at_utc FROM rb_team_members"
            + " WHERE team_id = ? ORDER BY joined_at_utc, player";

    public static Optional<MembershipRow> membershipOf(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one(SELECT_MEMBERSHIP_BY_PLAYER, MEMBERSHIP, player);
    }

    public static Optional<MembershipRow> membershipInTeam(TransactionContext tx,
            UUID teamId, UUID player) throws SQLException {
        return tx.one(SELECT_MEMBERSHIP_IN_TEAM, MEMBERSHIP, teamId, player);
    }

    public static List<MembershipRow> members(TransactionContext tx, UUID teamId)
            throws SQLException {
        return tx.list(SELECT_MEMBERS, MEMBERSHIP, teamId);
    }

    public static void insertMembership(TransactionContext tx, UUID teamId, UUID player,
            String rankKey, long now) throws SQLException {
        tx.update(INSERT_MEMBERSHIP, teamId, player, rankKey, now);
    }

    public static void deleteMembership(TransactionContext tx, UUID teamId, UUID player)
            throws SQLException {
        tx.update(DELETE_MEMBERSHIP, teamId, player);
    }

    public static void setRank(TransactionContext tx, UUID player, String rankKey)
            throws SQLException {
        tx.update(UPDATE_RANK, rankKey, player);
    }

    // ------------------------------------------------------------------
    // Invites
    // ------------------------------------------------------------------

    static final String DELETE_INVITE = "DELETE FROM rb_team_invites WHERE team_id = ? AND invitee = ?";

    static final String DELETE_INVITES_BY_TEAM = "DELETE FROM rb_team_invites WHERE team_id = ?";

    static final String SELECT_OPEN_INVITES_FOR_PLAYER =
            "SELECT id, team_id, inviter, invitee, created_at_utc, expires_at_utc"
            + " FROM rb_team_invites WHERE invitee = ? AND expires_at_utc > ?"
            + " ORDER BY expires_at_utc";

    static final String SELECT_OPEN_INVITE_IN_TEAM =
            "SELECT id, team_id, inviter, invitee, created_at_utc, expires_at_utc"
            + " FROM rb_team_invites WHERE team_id = ? AND invitee = ? AND expires_at_utc > ?";

    static final String SELECT_OPEN_INVITES_BY_TEAM =
            "SELECT id, team_id, inviter, invitee, created_at_utc, expires_at_utc"
            + " FROM rb_team_invites WHERE team_id = ? AND expires_at_utc > ?"
            + " ORDER BY expires_at_utc";

    public static void insertInvite(TransactionContext tx, UUID id, UUID teamId, UUID inviter,
            UUID invitee, long now, long expiresAt) throws SQLException {
        tx.update("INSERT INTO rb_team_invites (id, team_id, inviter, invitee, created_at_utc,"
                + " expires_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                id, teamId, inviter, invitee, now, expiresAt);
    }

    public static void deleteInvite(TransactionContext tx, UUID teamId, UUID invitee)
            throws SQLException {
        tx.update(DELETE_INVITE, teamId, invitee);
    }

    public static void deleteInvitesByTeam(TransactionContext tx, UUID teamId)
            throws SQLException {
        tx.update(DELETE_INVITES_BY_TEAM, teamId);
    }

    public static List<InviteRow> openInvitesFor(TransactionContext tx, UUID invitee, long now)
            throws SQLException {
        return tx.list(SELECT_OPEN_INVITES_FOR_PLAYER, INVITE, invitee, now);
    }

    public static Optional<InviteRow> openInviteInTeam(TransactionContext tx, UUID teamId,
            UUID invitee, long now) throws SQLException {
        return tx.one(SELECT_OPEN_INVITE_IN_TEAM, INVITE, teamId, invitee, now);
    }

    public static List<InviteRow> openInvitesByTeam(TransactionContext tx, UUID teamId, long now)
            throws SQLException {
        return tx.list(SELECT_OPEN_INVITES_BY_TEAM, INVITE, teamId, now);
    }

    // ------------------------------------------------------------------
    // Diplomacy (symmetric; team_a < team_b enforced by writers)
    // ------------------------------------------------------------------

    public static void upsertRelation(TransactionContext tx, UUID a, UUID b, String relation,
            long now) throws SQLException {
        // DELETE + INSERT keeps the upsert portable across SQLite and MySQL.
        tx.update("DELETE FROM rb_team_relations WHERE team_a = ? AND team_b = ?", a, b);
        if (!"neutral".equals(relation)) {
            tx.update("INSERT INTO rb_team_relations (team_a, team_b, relation, changed_at_utc)"
                    + " VALUES (?, ?, ?, ?)", a, b, relation, now);
        }
    }

    /** @return rows where the given team is involved (either side) */
    public static List<RelationRow> relationsOf(TransactionContext tx, UUID teamId)
            throws SQLException {
        return tx.list("SELECT team_a, team_b, relation, changed_at_utc FROM rb_team_relations"
                + " WHERE team_a = ? OR team_b = ? ORDER BY changed_at_utc", RELATION,
                teamId, teamId);
    }

    /** Ally teams of {@code teamId} (symmetric 'ally' rows). */
    public static Set<UUID> allyTeamsOf(TransactionContext tx, UUID teamId) throws SQLException {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        for (RelationRow row : relationsOf(tx, teamId)) {
            if ("ally".equals(row.relation())) {
                out.add(row.teamA().equals(teamId) ? row.teamB() : row.teamA());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // History (append-only)
    // ------------------------------------------------------------------

    public static void insertHistory(TransactionContext tx, UUID id, UUID teamId, UUID actor,
            String action, String detail, long now) throws SQLException {
        tx.update("INSERT INTO rb_team_history (id, team_id, actor, action, detail,"
                + " recorded_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                id, teamId, actor, action, detail, now);
    }

    public static List<HistoryRow> history(TransactionContext tx, UUID teamId, int limit)
            throws SQLException {
        return tx.list("SELECT id, team_id, actor, action, detail, recorded_at_utc"
                + " FROM rb_team_history WHERE team_id = ? ORDER BY recorded_at_utc DESC, id"
                + " LIMIT ?", HISTORY, teamId, limit);
    }

    // ------------------------------------------------------------------
    // Chat settings + player state (portable upserts via DELETE+INSERT)
    // ------------------------------------------------------------------

    public static void saveChatChannel(TransactionContext tx, UUID player, String channel)
            throws SQLException {
        tx.update("DELETE FROM rb_chat_settings WHERE player = ?", player);
        tx.update("INSERT INTO rb_chat_settings (player, channel) VALUES (?, ?)",
                player, channel);
    }

    public static Optional<String> chatChannel(TransactionContext tx, UUID player)
            throws SQLException {
        return tx.one("SELECT channel FROM rb_chat_settings WHERE player = ?", CHANNEL, player);
    }

    /** @return {lastCreateUtc, lastInviteUtc} for the player, {0,0} when absent */
    public static long[] playerState(TransactionContext tx, UUID player) throws SQLException {
        Optional<long[]> row = tx.one(
                "SELECT last_create_utc, last_invite_utc FROM rb_team_player_state WHERE player = ?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, player);
        return row.orElse(new long[]{0L, 0L});
    }

    public static void savePlayerState(TransactionContext tx, UUID player,
            long lastCreateUtc, long lastInviteUtc) throws SQLException {
        tx.update("DELETE FROM rb_team_player_state WHERE player = ?", player);
        tx.update("INSERT INTO rb_team_player_state (player, last_create_utc, last_invite_utc)"
                + " VALUES (?, ?, ?)", player, lastCreateUtc, lastInviteUtc);
    }

    // ------------------------------------------------------------------
    // Constraint-violation classification
    // ------------------------------------------------------------------

    /**
     * @return true when the exception is a UNIQUE constraint violation on one
     *         of the teams tables (the race backstop firing).
     */
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

    /** @return which teams table a unique violation fired on, or null when unknown */
    public static String uniqueViolationTable(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("rb_team_members")) {
            return "rb_team_members";
        }
        if (m.contains("rb_teams")) {
            return "rb_teams";
        }
        if (m.contains("rb_team_invites")) {
            return "rb_team_invites";
        }
        return null;
    }
}
