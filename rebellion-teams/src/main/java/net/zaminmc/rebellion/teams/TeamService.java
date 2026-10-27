package net.zaminmc.rebellion.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;
import net.zaminmc.rebellion.teams.db.TeamStore;

/**
 * Team management orchestration (ADR-0006).
 *
 * <p>Every mutation is one database transaction: rank checks read the live
 * rows inside the transaction, the mutation + history row commit together,
 * and caches are touched only after a normal commit. Constraint violations
 * are classified and mapped to {@link TeamOutcome}s — races lose cleanly
 * with a specific message instead of a half-state. The schema's UNIQUE
 * constraints (team name, one team per player, one open invite per
 * team+invitee) are the authoritative race backstop.</p>
 */
public final class TeamService {

    /** Rank names are code-owned (ADR-0006): fixed set, data-driven grants. */
    public static final String RANK_LEADER = "leader";
    public static final String RANK_OFFICER = "officer";
    public static final String RANK_MEMBER = "member";

    public static final String RELATION_ALLY = "ally";
    public static final String RELATION_ENEMY = "enemy";
    public static final String RELATION_NEUTRAL = "neutral";

    /** Own-team view for /team info. */
    public record TeamView(TeamStore.TeamRow team, List<TeamStore.MembershipRow> members,
            List<TeamStore.RelationRow> relations) {
    }

    /** One pending invite for /team invites. */
    public record InviteView(String teamName, long expiresAtUtc) {
    }

    private final RebellionDatabase db;
    private final TeamsConfig config;
    private final TeamCache cache;
    private final LongSupplier clock;

    public TeamService(RebellionDatabase db, TeamsConfig config, TeamCache cache) {
        this(db, config, cache, System::currentTimeMillis);
    }

    public TeamService(RebellionDatabase db, TeamsConfig config, TeamCache cache,
            LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.cache = cache;
        this.clock = clock;
    }

    public TeamCache cache() {
        return cache;
    }

    public TeamsConfig config() {
        return config;
    }

    // ------------------------------------------------------------------
    // Creation + invites
    // ------------------------------------------------------------------

    public CompletableFuture<TeamResult> create(UUID actor, String displayName) {
        Optional<String> normalized = TeamNames.validateAndNormalize(displayName);
        if (normalized.isEmpty()) {
            return CompletableFuture.completedFuture(TeamResult.fail(TeamOutcome.NAME_INVALID));
        }
        String nameNorm = normalized.get();
        return db.transaction(tx -> {
            if (TeamStore.membershipOf(tx, actor).isPresent()) {
                return TeamResult.fail(TeamOutcome.ALREADY_IN_TEAM);
            }
            long now = clock.getAsLong();
            long lastCreate = TeamStore.playerState(tx, actor)[0];
            long cooldownMs = config.createCooldownSeconds() * 1000L;
            if (now - lastCreate < cooldownMs) {
                long secondsLeft = (cooldownMs - (now - lastCreate) + 999) / 1000;
                return TeamResult.fail(TeamOutcome.CREATE_COOLDOWN, String.valueOf(secondsLeft));
            }
            UUID teamId = UUID.randomUUID();
            try {
                TeamStore.insertTeam(tx, new TeamStore.TeamRow(teamId, nameNorm,
                        displayName, actor, false, now, now));
                TeamStore.insertMembership(tx, teamId, actor, RANK_LEADER, now);
                TeamStore.savePlayerState(tx, actor, now,
                        TeamStore.playerState(tx, actor)[1]);
                TeamStore.insertHistory(tx, UUID.randomUUID(), teamId, actor,
                        "create", nameNorm, now);
            } catch (java.sql.SQLException e) {
                return mapUnique(e, TeamOutcome.NAME_TAKEN);
            }
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                loadSnapshot(actor); // cache follows the committed authority
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> invite(UUID actor, UUID invitee) {
        if (actor.equals(invitee)) {
            return CompletableFuture.completedFuture(
                    TeamResult.fail(TeamOutcome.INVITE_SELF));
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!isAtLeast(actorRow, RANK_OFFICER)) {
                return TeamResult.fail(TeamOutcome.NO_OFFICER_RIGHTS);
            }
            if (TeamStore.membershipOf(tx, invitee).isPresent()) {
                return TeamResult.fail(TeamOutcome.INVITE_TARGET_IN_TEAM);
            }
            long now = clock.getAsLong();
            long intervalMs = config.inviteSendIntervalSeconds() * 1000L;
            long lastInvite = TeamStore.playerState(tx, actor)[1];
            if (now - lastInvite < intervalMs) {
                long secondsLeft = (intervalMs - (now - lastInvite) + 999) / 1000;
                return TeamResult.fail(TeamOutcome.INVITE_RATE_LIMITED, String.valueOf(secondsLeft));
            }
            if (TeamStore.openInvitesFor(tx, invitee, now).size() >= config.maxPendingInvites()) {
                return TeamResult.fail(TeamOutcome.INVITE_QUEUE_FULL);
            }
            try {
                // Re-inviting refreshes the expiry: replace any existing open
                // invite for this team+invitee pair atomically.
                TeamStore.deleteInvite(tx, actorRow.teamId(), invitee);
                TeamStore.insertInvite(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                        invitee, now, now + config.inviteExpirySeconds() * 1000L);
                long[] state = TeamStore.playerState(tx, actor);
                TeamStore.savePlayerState(tx, actor, state[0], now);
                TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                        "invite", invitee.toString(), now);
            } catch (java.sql.SQLException e) {
                return mapUnique(e, TeamOutcome.DB_FAILURE);
            }
            return TeamResult.ok();
        });
    }

    public CompletableFuture<TeamResult> accept(UUID actor, String teamNameOrNull) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            List<TeamStore.InviteRow> open = TeamStore.openInvitesFor(tx, actor, now);
            if (open.isEmpty()) {
                return TeamResult.fail(TeamOutcome.NO_INVITE);
            }
            if (TeamStore.membershipOf(tx, actor).isPresent()) {
                return TeamResult.fail(TeamOutcome.ALREADY_IN_TEAM);
            }
            TeamStore.InviteRow chosen;
            if (teamNameOrNull == null) {
                if (open.size() > 1) {
                    return TeamResult.fail(TeamOutcome.NO_INVITE, "ambiguous");
                }
                chosen = open.get(0);
            } else {
                TeamStore.TeamRow team = TeamStore.teamByName(tx,
                        TeamNames.normalize(teamNameOrNull)).orElse(null);
                if (team == null) {
                    return TeamResult.fail(TeamOutcome.NO_INVITE);
                }
                chosen = TeamStore.openInviteInTeam(tx, team.id(), actor, now).orElse(null);
                if (chosen == null) {
                    return TeamResult.fail(TeamOutcome.INVITE_NOT_FOUND);
                }
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, chosen.teamId()).orElse(null);
            if (team == null) {
                // Stale invite to a deleted team cannot happen thanks to the
                // cascade, but a missing team is still handled explicitly.
                TeamStore.deleteInvite(tx, chosen.teamId(), actor);
                return TeamResult.fail(TeamOutcome.NO_INVITE);
            }
            if (TeamStore.memberCount(tx, team.id()) >= config.maxMembers()) {
                return TeamResult.fail(TeamOutcome.TEAM_FULL, team.displayName());
            }
            try {
                TeamStore.insertMembership(tx, team.id(), actor, RANK_MEMBER, now);
                TeamStore.deleteInvite(tx, team.id(), actor);
                TeamStore.insertHistory(tx, UUID.randomUUID(), team.id(), actor,
                        "join", "", now);
            } catch (java.sql.SQLException e) {
                // UNIQUE(player) fired: invitee joined another team mid-flight.
                return mapUnique(e, TeamOutcome.ALREADY_IN_TEAM);
            }
            return TeamResult.ok(team.displayName());
        }).thenApply(result -> {
            if (result.success()) {
                loadSnapshot(actor); // refresh cache from authority
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> decline(UUID actor, String teamNameOrNull) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            List<TeamStore.InviteRow> open = TeamStore.openInvitesFor(tx, actor, now);
            if (open.isEmpty()) {
                return TeamResult.fail(TeamOutcome.NO_INVITE);
            }
            TeamStore.InviteRow chosen;
            if (teamNameOrNull == null) {
                if (open.size() > 1) {
                    return TeamResult.fail(TeamOutcome.NO_INVITE, "ambiguous");
                }
                chosen = open.get(0);
            } else {
                TeamStore.TeamRow team = TeamStore.teamByName(tx,
                        TeamNames.normalize(teamNameOrNull)).orElse(null);
                if (team == null) {
                    return TeamResult.fail(TeamOutcome.NO_INVITE);
                }
                chosen = TeamStore.openInviteInTeam(tx, team.id(), actor, now).orElse(null);
                if (chosen == null) {
                    return TeamResult.fail(TeamOutcome.INVITE_NOT_FOUND);
                }
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, chosen.teamId()).orElse(null);
            TeamStore.deleteInvite(tx, chosen.teamId(), actor);
            TeamStore.insertHistory(tx, UUID.randomUUID(), chosen.teamId(), actor,
                    "decline", team == null ? "" : team.nameNorm(), now);
            return TeamResult.ok(team == null ? "" : team.displayName());
        });
    }

    public CompletableFuture<TeamResult> uninvite(UUID actor, UUID invitee) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!isAtLeast(actorRow, RANK_OFFICER)) {
                return TeamResult.fail(TeamOutcome.NO_OFFICER_RIGHTS);
            }
            long now = clock.getAsLong();
            if (TeamStore.openInviteInTeam(tx, actorRow.teamId(), invitee, now).isEmpty()) {
                return TeamResult.fail(TeamOutcome.INVITE_NOT_FOUND);
            }
            TeamStore.deleteInvite(tx, actorRow.teamId(), invitee);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    "uninvite", invitee.toString(), now);
            return TeamResult.ok();
        });
    }

    // ------------------------------------------------------------------
    // Membership mutations
    // ------------------------------------------------------------------

    public CompletableFuture<TeamResult> kick(UUID actor, UUID target) {
        if (actor.equals(target)) {
            return CompletableFuture.completedFuture(
                    TeamResult.fail(TeamOutcome.CANT_TARGET_SELF));
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            TeamStore.MembershipRow targetRow = TeamStore.membershipInTeam(
                    tx, actorRow.teamId(), target).orElse(null);
            if (targetRow == null) {
                return TeamResult.fail(TeamOutcome.TARGET_NOT_MEMBER);
            }
            if (RANK_LEADER.equals(targetRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.CANT_TARGET_LEADER);
            }
            if (RANK_OFFICER.equals(actorRow.rankKey())
                    && RANK_OFFICER.equals(targetRow.rankKey())) {
                // Only the leader may remove officers.
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            if (!isAtLeast(actorRow, RANK_OFFICER)) {
                return TeamResult.fail(TeamOutcome.NO_OFFICER_RIGHTS);
            }
            long now = clock.getAsLong();
            TeamStore.deleteMembership(tx, actorRow.teamId(), target);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    "kick", target.toString(), now);
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                cache.evict(target);
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> leave(UUID actor) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            long now = clock.getAsLong();
            int count = TeamStore.memberCount(tx, actorRow.teamId());
            boolean autoDisband = count == 1;
            TeamStore.deleteMembership(tx, actorRow.teamId(), actor);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    autoDisband ? "disband_auto" : "leave", "", now);
            if (autoDisband) {
                // Cascade removes invites + relations; history survives (no FK).
                TeamStore.deleteTeam(tx, actorRow.teamId());
            }
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                cache.evict(actor);
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> disband(UUID actor) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            long now = clock.getAsLong();
            List<TeamStore.MembershipRow> members = TeamStore.members(tx, actorRow.teamId());
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    "disband", members.size() + " members", now);
            TeamStore.deleteTeam(tx, actorRow.teamId());
            List<UUID> memberIds = members.stream().map(TeamStore.MembershipRow::player).toList();
            return TeamResult.ok(String.join(",", memberIds.stream().map(UUID::toString).toList()));
        }).thenApply(result -> {
            if (result.success()) {
                for (UUID member : result.detail().isEmpty()
                        ? List.<UUID>of() : stringList(result.detail())) {
                    cache.evict(member);
                }
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> rename(UUID actor, String newName) {
        Optional<String> normalized = TeamNames.validateAndNormalize(newName);
        if (normalized.isEmpty()) {
            return CompletableFuture.completedFuture(
                    TeamResult.fail(TeamOutcome.NAME_INVALID));
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, actorRow.teamId()).orElse(null);
            if (team == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            long now = clock.getAsLong();
            long cooldownMs = config.renameCooldownSeconds() * 1000L;
            if (now - team.renamedAtUtc() < cooldownMs) {
                long secondsLeft = (cooldownMs - (now - team.renamedAtUtc()) + 999) / 1000;
                return TeamResult.fail(TeamOutcome.RENAME_COOLDOWN, String.valueOf(secondsLeft));
            }
            try {
                TeamStore.updateTeamName(tx, newName, normalized.get(), now, team.id());
                TeamStore.insertHistory(tx, UUID.randomUUID(), team.id(), actor,
                        "rename", team.nameNorm() + "->" + normalized.get(), now);
            } catch (java.sql.SQLException e) {
                return mapUnique(e, TeamOutcome.NAME_TAKEN);
            }
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                loadSnapshot(actor);
                // Name changes are team-wide in the hot-path cache: refresh
                // every online member's snapshot from the fresh authority.
                cache.lookup(actor).flatMap(o -> o).ifPresent(s ->
                        cache.forEachOnlineMember(s.teamId(), this::loadSnapshot));
            }
            return result;
        });
    }

    // ------------------------------------------------------------------
    // Ranks
    // ------------------------------------------------------------------

    public CompletableFuture<TeamResult> transfer(UUID actor, UUID target) {
        if (actor.equals(target)) {
            return CompletableFuture.completedFuture(
                    TeamResult.fail(TeamOutcome.CANT_TARGET_SELF));
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            TeamStore.MembershipRow targetRow = TeamStore.membershipInTeam(
                    tx, actorRow.teamId(), target).orElse(null);
            if (targetRow == null) {
                return TeamResult.fail(TeamOutcome.TARGET_NOT_MEMBER);
            }
            long now = clock.getAsLong();
            TeamStore.setRank(tx, target, RANK_LEADER);
            TeamStore.setRank(tx, actor, RANK_OFFICER);
            TeamStore.updateTeamLeader(tx, actorRow.teamId(), target);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    "transfer", target.toString(), now);
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                cache.updateRank(actor, RANK_OFFICER);
                cache.updateRank(target, RANK_LEADER);
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> promote(UUID actor, UUID target) {
        return changeRank(actor, target, true);
    }

    public CompletableFuture<TeamResult> demote(UUID actor, UUID target) {
        return changeRank(actor, target, false);
    }

    private CompletableFuture<TeamResult> changeRank(UUID actor, UUID target, boolean up) {
        if (actor.equals(target)) {
            return CompletableFuture.completedFuture(
                    TeamResult.fail(TeamOutcome.CANT_TARGET_SELF));
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            TeamStore.MembershipRow targetRow = TeamStore.membershipInTeam(
                    tx, actorRow.teamId(), target).orElse(null);
            if (targetRow == null) {
                return TeamResult.fail(TeamOutcome.TARGET_NOT_MEMBER);
            }
            if (RANK_LEADER.equals(targetRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.CANT_TARGET_LEADER);
            }
            if (up && RANK_OFFICER.equals(targetRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.ALREADY_OFFICER);
            }
            if (!up && !RANK_OFFICER.equals(targetRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.TARGET_NOT_OFFICER);
            }
            long now = clock.getAsLong();
            TeamStore.setRank(tx, target, up ? RANK_OFFICER : RANK_MEMBER);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    up ? "promote" : "demote", target.toString(), now);
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                cache.updateRank(target, up ? RANK_OFFICER : RANK_MEMBER);
            }
            return result;
        });
    }

    // ------------------------------------------------------------------
    // Team settings + diplomacy
    // ------------------------------------------------------------------

    public CompletableFuture<TeamResult> setFriendlyFire(UUID actor, boolean enabled) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, actorRow.teamId()).orElse(null);
            if (team == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            long now = clock.getAsLong();
            TeamStore.updateTeamFriendlyFire(tx, enabled, team.id());
            TeamStore.insertHistory(tx, UUID.randomUUID(), team.id(), actor,
                    "friendlyfire", enabled ? "on" : "off", now);
            return TeamResult.ok();
        }).thenApply(result -> {
            if (result.success()) {
                // FF is team-wide: every online member's snapshot must see it.
                cache.lookup(actor).flatMap(o -> o).ifPresent(s ->
                        cache.updateTeam(s.teamId(), s.nameNorm(), s.displayName(), enabled));
            }
            return result;
        });
    }

    public CompletableFuture<TeamResult> setRelation(UUID actor, String otherTeamName,
            String relation) {
        if (!RELATION_ALLY.equals(relation) && !RELATION_ENEMY.equals(relation)
                && !RELATION_NEUTRAL.equals(relation)) {
            throw new IllegalArgumentException("relation must be ally/enemy/neutral");
        }
        return db.transaction(tx -> {
            TeamStore.MembershipRow actorRow = TeamStore.membershipOf(tx, actor).orElse(null);
            if (actorRow == null) {
                return TeamResult.fail(TeamOutcome.NOT_IN_TEAM);
            }
            if (!RANK_LEADER.equals(actorRow.rankKey())) {
                return TeamResult.fail(TeamOutcome.NO_LEADER_RIGHTS);
            }
            TeamStore.TeamRow other = TeamStore.teamByName(tx,
                    TeamNames.normalize(otherTeamName)).orElse(null);
            if (other == null) {
                return TeamResult.fail(TeamOutcome.TEAM_NOT_FOUND);
            }
            if (other.id().equals(actorRow.teamId())) {
                return TeamResult.fail(TeamOutcome.RELATION_OWN_TEAM);
            }
            long now = clock.getAsLong();
            UUID a = min(actorRow.teamId(), other.id());
            UUID b = max(actorRow.teamId(), other.id());
            TeamStore.upsertRelation(tx, a, b, relation, now);
            TeamStore.insertHistory(tx, UUID.randomUUID(), actorRow.teamId(), actor,
                    "relation", other.nameNorm() + "=" + relation, now);
            return TeamResult.ok(other.displayName());
        }).thenApply(result -> {
            if (result.success()) {
                refreshAllies(actor);
            }
            return result;
        });
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Own team's full view; empty when the player is teamless. */
    public CompletableFuture<Optional<TeamView>> teamInfo(UUID player) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow row = TeamStore.membershipOf(tx, player).orElse(null);
            if (row == null) {
                return Optional.<TeamView>empty();
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, row.teamId()).orElse(null);
            if (team == null) {
                return Optional.empty();
            }
            return Optional.of(new TeamView(team, TeamStore.members(tx, team.id()),
                    TeamStore.relationsOf(tx, team.id())));
        });
    }

    /** Public team view by (display) name. */
    public CompletableFuture<Optional<TeamView>> teamInfoByName(String name) {
        return db.transaction(tx -> {
            TeamStore.TeamRow team = TeamStore.teamByName(tx,
                    TeamNames.normalize(name)).orElse(null);
            if (team == null) {
                return Optional.<TeamView>empty();
            }
            return Optional.of(new TeamView(team, TeamStore.members(tx, team.id()),
                    TeamStore.relationsOf(tx, team.id())));
        });
    }

    /** Pending unexpired invites of one player. */
    public CompletableFuture<List<InviteView>> invitesOf(UUID player) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            List<InviteView> out = new ArrayList<>();
            for (TeamStore.InviteRow invite : TeamStore.openInvitesFor(tx, player, now)) {
                String name = TeamStore.teamById(tx, invite.teamId())
                        .map(TeamStore.TeamRow::displayName).orElse("?");
                out.add(new InviteView(name, invite.expiresAtUtc()));
            }
            return List.copyOf(out);
        });
    }

    /** Audit history of the player's team (permission-checked by the command). */
    public CompletableFuture<List<TeamStore.HistoryRow>> historyOf(UUID player, int limit) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow row = TeamStore.membershipOf(tx, player).orElse(null);
            if (row == null) {
                return List.<TeamStore.HistoryRow>of();
            }
            return TeamStore.history(tx, row.teamId(), limit);
        });
    }

    // ------------------------------------------------------------------
    // Cache loading (join path)
    // ------------------------------------------------------------------

    /**
     * Loads the player's authoritative state and refreshes the cache.
     * @return the stored chat channel, or the configured default.
     */
    public CompletableFuture<String> loadAndCache(UUID player) {
        return db.transaction(tx -> {
            TeamStore.MembershipRow row = TeamStore.membershipOf(tx, player).orElse(null);
            String channel = TeamStore.chatChannel(tx, player)
                    .orElse(config.defaultChannel());
            if (row == null) {
                return new JoinData(null, null, null, channel);
            }
            TeamStore.TeamRow team = TeamStore.teamById(tx, row.teamId()).orElse(null);
            if (team == null) {
                return new JoinData(null, null, null, channel);
            }
            return new JoinData(team, row, TeamStore.allyTeamsOf(tx, team.id()), channel);
        }).thenApply(data -> {
            if (data.team == null) {
                cache.putTeamless(player);
            } else {
                cache.put(player, new TeamCache.Snapshot(data.team.id(), data.team.nameNorm(),
                        data.team.displayName(), data.membership.rankKey(),
                        data.team.friendlyFire(), data.allies));
            }
            return data.channel;
        });
    }

    /** Evicts a player from the hot-path cache. */
    public void evict(UUID player) {
        cache.evict(player);
    }

    /** Stores the player's chat channel preference (portable upsert). */
    public CompletableFuture<Void> saveChatChannel(UUID player, String channel) {
        return db.transaction(tx -> {
            TeamStore.saveChatChannel(tx, player, channel);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private record JoinData(TeamStore.TeamRow team, TeamStore.MembershipRow membership,
            Set<UUID> allies, String channel) {
    }

    /** Reloads the actor's snapshot from the database into the cache. */
    private void loadSnapshot(UUID player) {
        loadAndCache(player);
    }

    /** Refreshes both teams' ally sets in every online member snapshot. */
    private void refreshAllies(UUID actor) {
        db.transaction(tx -> {
            TeamStore.MembershipRow row = TeamStore.membershipOf(tx, actor).orElse(null);
            if (row == null) {
                return java.util.Map.<UUID, Set<UUID>>of();
            }
            java.util.Map<UUID, Set<UUID>> out = new java.util.HashMap<>();
            out.put(row.teamId(), TeamStore.allyTeamsOf(tx, row.teamId()));
            for (TeamStore.RelationRow relation : TeamStore.relationsOf(tx, row.teamId())) {
                UUID other = relation.teamA().equals(row.teamId())
                        ? relation.teamB() : relation.teamA();
                out.put(other, TeamStore.allyTeamsOf(tx, other));
            }
            return out;
        }).thenAccept(allies -> allies.forEach(cache::updateAllies));
    }

    private static UUID min(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    private static UUID max(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? b : a;
    }

    private static boolean isAtLeast(TeamStore.MembershipRow row, String required) {
        return rankLevel(row.rankKey()) >= rankLevel(required);
    }

    private static int rankLevel(String rank) {
        return switch (rank) {
            case RANK_LEADER -> 3;
            case RANK_OFFICER -> 2;
            default -> 1;
        };
    }

    private static TeamResult mapUnique(java.sql.SQLException e, TeamOutcome whenTeamsTable) {
        if (TeamStore.isUniqueViolation(e)) {
            String table = TeamStore.uniqueViolationTable(e);
            if ("rb_team_members".equals(table)) {
                return TeamResult.fail(TeamOutcome.ALREADY_IN_TEAM);
            }
            if ("rb_teams".equals(table)) {
                return TeamResult.fail(whenTeamsTable);
            }
        }
        throw new RebellionException(FailureCode.PERSISTENCE_FAILURE,
                "team operation failed: " + e.getMessage(), null, e);
    }

    /**
     * Converts a failed future into a {@link TeamResult}; saturation and
     * database failures are fail-closed (nothing was applied).
     */
    public static TeamResult failureToResult(Throwable t) {
        if (t instanceof RebellionException re) {
            if (re.code() == FailureCode.PERSISTENCE_OVERLOADED) {
                return TeamResult.fail(TeamOutcome.OVERLOADED);
            }
            return TeamResult.fail(TeamOutcome.DB_FAILURE);
        }
        return TeamResult.fail(TeamOutcome.DB_FAILURE);
    }

    private static List<UUID> stringList(String joined) {
        List<UUID> out = new ArrayList<>();
        for (String part : joined.split(",")) {
            if (!part.isBlank()) {
                out.add(UUID.fromString(part));
            }
        }
        return out;
    }
}
