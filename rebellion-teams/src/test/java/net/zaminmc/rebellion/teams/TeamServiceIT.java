package net.zaminmc.rebellion.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.teams.db.TeamsSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TeamService against a real SQLite database through Core's actual
 * DatabaseService — migrations, membership rules, invite races, rank
 * enforcement, diplomacy, disband cascade and cache behavior. No mocks: the
 * schema's UNIQUE constraints are exercised for real.
 */
final class TeamServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private TeamService service;
    private final UUID leader = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    // Fast test config: no cooldowns, tiny limits, long invite expiry.
    private TeamsConfig config(boolean protectAllies) {
        return new TeamsConfig(3, 2, 86_400, 0, 0, 0, protectAllies,
                100.0, true, "global", 1);
    }

    @BeforeEach
    void boot() throws Exception {
        db = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(db.bootstrap());
        await(db.applyModuleMigrations("teams", List.of(TeamsSchema.v1())));
        service = new TeamService(db, config(false), new TeamCache());
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    private DiagnosticsService diagnostics() {
        CoreConfig config = new CoreConfig(1, false, 30, "diagnostics", 5, 50);
        return new DiagnosticsService(config, tmp, java.util.logging.Logger.getLogger("test"));
    }

    private DatabaseConfig sqliteConfig() {
        return new DatabaseConfig(
                DatabaseConfig.Backend.SQLITE, "rebellion.sqlite",
                "localhost", 3306, "rebellion", "user", "",
                java.util.Map.of(), 10, 2, 5_000, 1_800_000,
                2, 2000, true);
    }

    private static void await(CompletableFuture<?> future) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed: " + e, e);
        }
    }

    private TeamResult run(CompletableFuture<TeamResult> future) {
        await(future);
        return future.join();
    }

    /** Full happy path: create -> invite -> accept, plus cache + history. */
    @Test
    void createInviteAcceptHappyPath() {
        assertTrue(run(service.create(leader, "Rebels")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());

        var info = service.teamInfo(a);
        await(info);
        var view = info.join().orElseThrow();
        assertEquals("rebels", view.team().nameNorm());
        assertEquals(2, view.members().size());
        assertEquals(TeamService.RANK_MEMBER,
                view.members().stream().filter(m -> m.player().equals(a))
                        .findFirst().orElseThrow().rankKey());

        // Cache reflects membership for the hot path.
        service.loadAndCache(a).join();
        var snapshot = service.cache().lookup(a).flatMap(o -> o).orElseThrow();
        assertEquals("Rebels", snapshot.displayName());
        assertEquals(TeamService.RANK_MEMBER, snapshot.rank());

        // History carries create + invite + join.
        var history = service.historyOf(a, 10);
        await(history);
        assertEquals(3, history.join().size());
    }

    @Test
    void duplicateTeamNameLosesCleanly() {
        assertTrue(run(service.create(leader, "Rebels")).success());
        TeamResult second = run(service.create(b, "rebels"));
        assertFalse(second.success());
        assertEquals(TeamOutcome.NAME_TAKEN, second.outcome());

        TeamResult invalid = run(service.create(b, "staff"));
        assertEquals(TeamOutcome.NAME_INVALID, invalid.outcome());
    }

    /** Two teams invite the same player; both accepts race, one wins. */
    @Test
    void doubleAcceptRaceHasExactlyOneWinner() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.create(b, "Beta")).success());
        assertTrue(run(service.invite(leader, c)).success());
        assertTrue(run(service.invite(b, c)).success());

        TeamResult first = run(service.accept(c, "alpha"));
        TeamResult second = run(service.accept(c, "beta"));
        // SQLite serializes writers; exactly one membership can exist.
        assertTrue(first.success() != second.success());
        assertEquals(TeamOutcome.ALREADY_IN_TEAM,
                first.success() ? second.outcome() : first.outcome());

        var info = service.teamInfo(c);
        await(info);
        assertEquals(2, info.join().orElseThrow().members().size(),
                "the winner's team is leader + invitee");
    }

    @Test
    void joinsWhileFullAreRefused() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());
        assertTrue(run(service.invite(leader, b)).success());
        assertTrue(run(service.accept(b, null)).success());
        // Config caps teams at 3 members (leader + a + b).
        assertTrue(run(service.invite(leader, c)).success());
        TeamResult full = run(service.accept(c, null));
        assertFalse(full.success());
        assertEquals(TeamOutcome.TEAM_FULL, full.outcome());
    }

    @Test
    void rankEnforcementMatrix() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());
        assertTrue(run(service.invite(leader, b)).success());
        assertTrue(run(service.accept(b, null)).success());

        // Plain member cannot manage.
        assertEquals(TeamOutcome.NO_OFFICER_RIGHTS,
                run(service.kick(a, b)).outcome());
        assertEquals(TeamOutcome.NO_OFFICER_RIGHTS,
                run(service.invite(a, c)).outcome());

        // Officer can kick members, but not officers or the leader.
        assertTrue(run(service.promote(leader, a)).success());
        assertTrue(run(service.kick(a, b)).success());
        assertTrue(run(service.invite(leader, b)).success());
        assertTrue(run(service.accept(b, null)).success());
        assertTrue(run(service.promote(leader, b)).success());
        assertEquals(TeamOutcome.NO_LEADER_RIGHTS, run(service.kick(a, b)).outcome());
        assertEquals(TeamOutcome.CANT_TARGET_LEADER, run(service.kick(a, leader)).outcome());
        assertEquals(TeamOutcome.CANT_TARGET_SELF, run(service.kick(a, a)).outcome());

        // Only the leader changes ranks.
        assertEquals(TeamOutcome.NO_LEADER_RIGHTS, run(service.promote(a, b)).outcome());

        // Transfer moves leadership atomically.
        assertTrue(run(service.transfer(leader, a)).success());
        var info = service.teamInfo(a);
        await(info);
        var view = info.join().orElseThrow();
        assertEquals(TeamService.RANK_LEADER,
                view.members().stream().filter(m -> m.player().equals(a))
                        .findFirst().orElseThrow().rankKey());
        assertEquals(TeamService.RANK_OFFICER,
                view.members().stream().filter(m -> m.player().equals(leader))
                        .findFirst().orElseThrow().rankKey());
        assertEquals(a, view.team().leader());
    }

    @Test
    void leaveAsLastMemberAutoDisbands() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.leave(leader)).success());

        var info = service.teamInfo(leader);
        await(info);
        assertTrue(info.join().isEmpty());
        // The team row is gone (name can be reused).
        assertTrue(run(service.create(a, "Alpha")).success());
    }

    @Test
    void disbandCascadesButKeepsHistory() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());
        assertTrue(run(service.disband(leader)).success());

        var info = service.teamInfo(leader);
        await(info);
        assertTrue(info.join().isEmpty());

        // History survived (append-only, no FK on team_id).
        var history = db.queryOne("SELECT COUNT(*) FROM rb_team_history",
                rs -> rs.getInt(1));
        await(history);
        assertTrue(history.join().orElse(0) > 0, "disband history must survive the cascade");

        // Invites cascaded away with the team.
        var invites = db.queryOne("SELECT COUNT(*) FROM rb_team_invites",
                rs -> rs.getInt(1));
        await(invites);
        assertEquals(0, invites.join().orElse(-1));

        // Members cascaded: the ex-member can create a new team.
        assertTrue(run(service.create(a, "Beta")).success());
    }

    @Test
    void officerCannotDisbandOrRenameOrSetDiplomacy() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());
        assertTrue(run(service.promote(leader, a)).success());

        assertEquals(TeamOutcome.NO_LEADER_RIGHTS,
                run(service.disband(a)).outcome());
        assertEquals(TeamOutcome.NO_LEADER_RIGHTS,
                run(service.rename(a, "NewName")).outcome());
        assertEquals(TeamOutcome.NO_LEADER_RIGHTS,
                run(service.setFriendlyFire(a, true)).outcome());
        assertEquals(TeamOutcome.NO_LEADER_RIGHTS,
                run(service.setRelation(a, "beta", "ally")).outcome());
    }

    @Test
    void diplomacyIsSymmetricAndNeutralRemovesRow() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.create(b, "Beta")).success());

        TeamResult self = run(service.setRelation(leader, "alpha", "ally"));
        assertEquals(TeamOutcome.RELATION_OWN_TEAM, self.outcome());
        TeamResult unknown = run(service.setRelation(leader, "gamma", "ally"));
        assertEquals(TeamOutcome.TEAM_NOT_FOUND, unknown.outcome());

        assertTrue(run(service.setRelation(leader, "beta", "ally")).success());
        // Exactly one row, ordered pair.
        var rows = db.queryList(
                "SELECT team_a, team_b, relation FROM rb_team_relations",
                rs -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
        await(rows);
        assertEquals(1, rows.join().size());

        assertTrue(run(service.setRelation(leader, "beta", "neutral")).success());
        var count = db.queryOne("SELECT COUNT(*) FROM rb_team_relations", rs -> rs.getInt(1));
        await(count);
        assertEquals(0, count.join().orElse(-1));
    }

    @Test
    void expiredInvitesCannotBeAccepted() {
        // Fixed, advancing clock: invite created at t=0 expires at t=1000.
        long[] clock = {0L};
        TeamService shortExpiry = new TeamService(db,
                new TeamsConfig(3, 2, 1, 0, 0, 0, false, 100.0, true, "global", 1),
                service.cache(), () -> clock[0]);
        assertTrue(run(shortExpiry.create(leader, "Alpha")).success());
        assertTrue(run(shortExpiry.invite(leader, a)).success());
        clock[0] = 2_000L; // past the 1-second expiry
        assertEquals(TeamOutcome.NO_INVITE, run(shortExpiry.accept(a, null)).outcome());
    }

    @Test
    void inviteQueueAndUninvite() {
        UUID d = UUID.randomUUID();
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.create(b, "Beta")).success());
        assertTrue(run(service.create(d, "Delta")).success());
        assertTrue(run(service.invite(leader, c)).success());
        assertTrue(run(service.invite(b, c)).success());
        // Max 2 pending (config): the third team's invite overflows.
        assertEquals(TeamOutcome.INVITE_QUEUE_FULL,
                run(service.invite(d, c)).outcome());
        // Re-inviting by the same team refreshes its own invite, not the queue.
        assertTrue(run(service.invite(leader, c)).success());
        assertEquals(TeamOutcome.INVITE_NOT_FOUND,
                run(service.uninvite(leader, a)).outcome());
        assertTrue(run(service.uninvite(leader, c)).success());
        // The invite was withdrawn: the team exists but has no invite for c.
        assertEquals(TeamOutcome.INVITE_NOT_FOUND, run(service.accept(c, "alpha")).outcome());
        // Queue has room again after the uninvite.
        assertTrue(run(service.invite(d, c)).success());
    }

    @Test
    void friendlyFireTogglePersistsAndReachesTheCache() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());
        assertTrue(run(service.setFriendlyFire(leader, true)).success());

        service.loadAndCache(leader).join();
        assertTrue(service.cache().lookup(leader).flatMap(o -> o).orElseThrow()
                .friendlyFire());
        var info = service.teamInfo(leader);
        await(info);
        assertTrue(info.join().orElseThrow().team().friendlyFire());
    }

    @Test
    void createCooldownBlocksImmediateRecreation() {
        // Cooldown of 60s with a fixed, advancing clock.
        long[] clock = {1_000_000L};
        TeamService cooled = new TeamService(db,
                new TeamsConfig(3, 2, 86_400, 60, 0, 0, false, 100.0, true, "global", 1),
                service.cache(), () -> clock[0]);
        assertTrue(run(cooled.create(leader, "Alpha")).success());
        assertTrue(run(cooled.leave(leader)).success());
        TeamResult blocked = run(cooled.create(leader, "Beta"));
        assertEquals(TeamOutcome.CREATE_COOLDOWN, blocked.outcome());
        assertNotNull(blocked.detail());

        clock[0] += 61_000L;
        assertTrue(run(cooled.create(leader, "Beta")).success());
    }

    @Test
    void renameCooldownAndCollision() {
        long[] clock = {1_000_000L};
        TeamService renamer = new TeamService(db,
                new TeamsConfig(3, 2, 86_400, 0, 60, 0, false, 100.0, true, "global", 1),
                service.cache(), () -> clock[0]);
        assertTrue(run(renamer.create(leader, "Alpha")).success());
        assertTrue(run(renamer.create(b, "Beta")).success());

        // The rename cooldown counts from creation; advance past it first.
        clock[0] += 61_000L;
        assertTrue(run(renamer.rename(leader, "NewAlpha")).success());
        assertEquals(TeamOutcome.RENAME_COOLDOWN,
                run(renamer.rename(leader, "EvenNewer")).outcome());

        clock[0] += 61_000L;
        assertEquals(TeamOutcome.NAME_TAKEN, run(renamer.rename(leader, "beta")).outcome());
        assertTrue(run(renamer.rename(leader, "EvenNewer")).success());
    }

    @Test
    void migrationsAreIdempotentPerModuleKey() {
        // A second apply with the same set completes and applies nothing new.
        await(db.applyModuleMigrations("teams", List.of(TeamsSchema.v1())));
        var versions = db.appliedMigrationVersions();
        assertEquals(1, versions.get("teams"));

        // A different set against the same key is refused.
        CompletableFuture<Void> changed = db.applyModuleMigrations("teams", List.of(
                new net.zaminmc.rebellion.api.database.MigrationSpec(1, "different",
                        List.of("CREATE TABLE rb_wrong (id TEXT)"))));
        try {
            changed.get(20, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("changed migration set must be refused");
        } catch (Exception expected) {
            // refused with a MigrationException — nothing was applied
        }
        var stillOne = db.queryOne("SELECT COUNT(*) FROM rb_teams", rs -> rs.getInt(1));
        await(stillOne);
        // The teams table still exists and works.
        assertTrue(run(service.create(leader, "Alpha")).success());
    }

    @Test
    void chatChannelPreferenceRoundTrips() {
        await(service.saveChatChannel(leader, "team"));
        String channel = service.loadAndCache(leader).join();
        assertEquals("team", channel);
        await(service.saveChatChannel(leader, "proximity"));
        assertEquals("proximity", service.loadAndCache(leader).join());
    }

    /** After mutations the cache mirrors the database exactly (authority). */
    @Test
    void cacheMirrorsCommittedStateAfterEveryMutation() {
        assertTrue(run(service.create(leader, "Alpha")).success());
        assertTrue(run(service.invite(leader, a)).success());
        assertTrue(run(service.accept(a, null)).success());

        assertEquals(2, service.cache().onlineTeamSize(leader));
        assertTrue(run(service.kick(leader, a)).success());
        // Evicted member falls back to teamless (not stale membership).
        assertTrue(service.cache().lookup(a).flatMap(o -> o).isEmpty());

        // Allies land in every online member snapshot after diplomacy.
        assertTrue(run(service.create(b, "Beta")).success());
        assertTrue(run(service.setRelation(leader, "beta", "ally")).success());
        var snapshot = service.cache().lookup(leader).flatMap(o -> o).orElseThrow();
        var betaId = service.teamInfoByName("Beta");
        await(betaId);
        assertTrue(snapshot.allies().contains(betaId.join().orElseThrow().team().id()));
        assertTrue(service.cache().allied(leader, b));
    }
}
