package net.zaminmc.rebellion.wards.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardOutcome;
import net.zaminmc.rebellion.wards.WardResult;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.detect.WardDetector;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WardService against a real SQLite database through Core's actual
 * DatabaseService — migrations, cap enforcement under concurrency, the
 * UNIQUE race backstops (owner-scoped names, one ward per anchor), anchor
 * lifecycle, management rights and mirror consistency. No mocks.
 */
final class WardServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private WardService service;
    private WardSpatialIndex index;
    private DetectionState state;

    private final UUID owner = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    // Fast test config: player cap 2, team cap 3, global cap 10.
    private WardsConfig config() {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 2, 3, 10,
                Set.of("leader", "officer"), true, 20, 0, true,
                "AMETHYST_SHARD", AlertChannel.TEAM, 300, true,
                Map.of(), "blocks", false);
    }

    /** owner=leader, officer=officer, member=member of TEAM; everyone else
     *  (stranger, ad-hoc test players) is teamless. */
    private TeamAffiliationService fakeTeams() {
        return new TeamAffiliationService() {
            @Override
            public Optional<UUID> teamOf(UUID player) {
                return isTeamMember(player) ? Optional.of(teamId) : Optional.empty();
            }

            @Override
            public Optional<String> rankOf(UUID player) {
                return player.equals(owner) ? Optional.of("leader")
                        : player.equals(officer) ? Optional.of("officer")
                        : player.equals(member) ? Optional.of("member")
                        : Optional.empty();
            }

            @Override
            public Set<UUID> onlineMembersOf(UUID team) {
                return team.equals(teamId) ? Set.of(owner, officer, member) : Set.of();
            }
        };
    }

    private boolean isTeamMember(UUID player) {
        return player.equals(owner) || player.equals(officer) || player.equals(member);
    }

    @BeforeEach
    void boot() throws Exception {
        db = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(db.bootstrap());
        await(db.applyModuleMigrations("wards", List.of(WardsSchema.v1())));
        index = WardSpatialIndex.withDefaultCellSize();
        state = new DetectionState(3_600_000L);
        service = new WardService(db, config(), index, state, fakeTeams(),
                () -> 1_700_000_000_000L);
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
                Map.of(), 10, 2, 5_000, 1_800_000, 2, 2000, true);
    }

    private static void await(CompletableFuture<?> future) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed: " + e, e);
        }
    }

    private WardResult run(CompletableFuture<WardResult> future) {
        await(future);
        return future.join();
    }

    private CompletableFuture<WardResult> create(UUID actor, String name, int x, int z,
            double radius, WardType type) {
        return service.create(actor, teamOf(actor), name, "world", x, 64, z, radius, type);
    }

    private Optional<UUID> teamOf(UUID actor) {
        return isTeamMember(actor) ? Optional.of(teamId) : Optional.empty();
    }

    // ------------------------------------------------------------------
    // Creation + validation
    // ------------------------------------------------------------------

    @Test
    void createBindsWardToAnchorAndMirrorsIt() {
        WardResult result = run(create(owner, "north-post", 100, 200, 64, WardType.WARD));
        assertTrue(result.success());
        Ward ward = result.ward();
        assertEquals("north-post", ward.name());
        assertEquals(Optional.of(teamId), ward.teamId());
        assertEquals(100.5, ward.x());
        assertEquals(200.5, ward.z());
        assertTrue(ward.active());
        assertEquals(WardType.WARD, ward.type());

        // Mirror: index + anchor map.
        assertEquals(1, index.size());
        assertEquals(1, index.query("world", 100, 200).size());
        assertEquals(Optional.of(ward.id()),
                service.wardIdAtAnchor(Ward.AnchorKey.of("world", 100, 64, 200)));
    }

    @Test
    void invalidNamesAndRadiiAreRefusedBeforeTheDatabase() {
        assertTrue(run(create(owner, "x", 0, 0, 64, WardType.WARD)).outcome()
                == WardOutcome.NAME_INVALID); // too short
        assertTrue(run(create(owner, "bad name!", 0, 0, 64, WardType.WARD)).outcome()
                == WardOutcome.NAME_INVALID);
        assertTrue(run(create(owner, "ok-name", 0, 0, 8, WardType.WARD)).outcome()
                == WardOutcome.RADIUS_INVALID); // below min
        assertTrue(run(create(owner, "ok-name", 0, 0, 999, WardType.WARD)).outcome()
                == WardOutcome.RADIUS_INVALID); // above max
        assertEquals(0, index.size());
    }

    @Test
    void namesAreOwnerScopedAndCaseInsensitiveUnique() {
        assertTrue(run(create(owner, "north", 0, 0, 64, WardType.WARD)).success());
        // Same owner, same name in different casing: taken.
        assertEquals(WardOutcome.NAME_TAKEN,
                run(create(owner, "NORTH", 500, 500, 64, WardType.WARD)).outcome());
        // Different owner, same name: fine (names are owner-scoped).
        assertTrue(run(create(stranger, "north", 900, 900, 64, WardType.WARD)).success());
    }

    @Test
    void concurrentSameNameCreateResolvesToOneWard() {
        CompletableFuture<WardResult> a = create(owner, "contested", 0, 0, 64, WardType.WARD);
        CompletableFuture<WardResult> b = create(owner, "contested", 300, 300, 64, WardType.WARD);
        CompletableFuture.allOf(a, b).join();
        long successes = List.of(a.join(), b.join()).stream()
                .filter(WardResult::success).count();
        assertEquals(1, successes, "UNIQUE (owner, name_norm) must settle the race");
        assertEquals(1, index.size());
    }

    @Test
    void oneWardPerAnchorEvenAcrossOwners() {
        assertTrue(run(create(owner, "first", 100, 100, 64, WardType.WARD)).success());
        assertEquals(WardOutcome.ANCHOR_TAKEN,
                run(create(stranger, "second", 100, 100, 64, WardType.WARD)).outcome());
        // Concurrent attempt on a fresh anchor also settles through UNIQUE.
        CompletableFuture<WardResult> a = create(owner, "third", 500, 500, 64, WardType.WARD);
        CompletableFuture<WardResult> b = create(stranger, "fourth", 500, 500, 64, WardType.WARD);
        CompletableFuture.allOf(a, b).join();
        assertEquals(1, List.of(a.join(), b.join()).stream()
                .filter(WardResult::success).count());
    }

    // ------------------------------------------------------------------
    // Caps (in-transaction)
    // ------------------------------------------------------------------

    @Test
    void perPlayerCapHoldsUnderConcurrentCreates() {
        // Cap 2 per player: fire 3 concurrent creates.
        CompletableFuture<WardResult> a = create(owner, "one", 0, 0, 64, WardType.WARD);
        CompletableFuture<WardResult> b = create(owner, "two", 100, 0, 64, WardType.WARD);
        CompletableFuture<WardResult> c = create(owner, "three", 200, 0, 64, WardType.WARD);
        CompletableFuture.allOf(a, b, c).join();
        long successes = List.of(a.join(), b.join(), c.join()).stream()
                .filter(WardResult::success).count();
        assertEquals(2, successes);
        assertEquals(2, index.size());
        assertTrue(List.of(a.join(), b.join(), c.join()).stream()
                .anyMatch(r -> r.outcome() == WardOutcome.PLAYER_CAP));
    }

    @Test
    void teamCapCountsEveryMember() {
        // Cap 3 per team: owner 2 + officer 2 = 4 attempts across two owners.
        assertTrue(run(create(owner, "own-one", 0, 0, 64, WardType.WARD)).success());
        assertTrue(run(create(owner, "own-two", 50, 0, 64, WardType.WARD)).success());
        assertTrue(run(create(officer, "off-one", 100, 0, 64, WardType.WARD)).success());
        assertEquals(WardOutcome.TEAM_CAP,
                run(create(officer, "off-two", 150, 0, 64, WardType.WARD)).outcome());
        // Stranger is teamless: unaffected by the team cap.
        assertTrue(run(create(stranger, "solo-one", 1000, 1000, 64, WardType.WARD)).success());
    }

    @Test
    void globalCapStopsEverything() {
        // Player cap is 2, so reaching the global cap of 10 needs five
        // distinct players at two wards each.
        UUID[] workers = new UUID[5];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = UUID.randomUUID();
            assertTrue(run(create(workers[i], "w-" + i + "-a", i * 100, 0, 16,
                    WardType.WARD)).success());
            assertTrue(run(create(workers[i], "w-" + i + "-b", i * 100, 200, 16,
                    WardType.WARD)).success());
        }
        assertEquals(10, index.size());
        // 10 wards exist; the 11th from a fresh player hits GLOBAL_CAP.
        UUID late = UUID.randomUUID();
        assertEquals(WardOutcome.GLOBAL_CAP,
                run(create(late, "late-ward", 9000, 0, 16, WardType.WARD)).outcome());
    }

    @Test
    void decoysCanBeDisabledByConfig() {
        WardsConfig noDecoys = withDecoys(false);
        WardService strict = new WardService(db, noDecoys, index, state, fakeTeams(),
                () -> 1_700_000_000_000L);
        assertEquals(WardOutcome.DECOYS_DISABLED,
                run(strict.create(owner, Optional.of(teamId), "decoy-one", "world", 0, 64, 0, 64,
                        WardType.DECOY)).outcome());
        assertTrue(run(strict.create(owner, Optional.of(teamId), "ward-one", "world", 0, 64, 0, 64,
                WardType.WARD)).success());
    }

    private WardsConfig withDecoys(boolean decoys) {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 2, 3, 10,
                Set.of("leader", "officer"), decoys, 20, 0, true,
                "AMETHYST_SHARD", AlertChannel.TEAM, 300, true,
                Map.of(), "blocks", false);
    }

    // ------------------------------------------------------------------
    // Management rights + remove/toggle
    // ------------------------------------------------------------------

    @Test
    void ownerAndConfiguredRanksManageOthersDoNot() {
        assertTrue(run(create(owner, "guarded", 100, 100, 64, WardType.WARD)).success());
        UUID wardId = index.all().get(0).id();

        // Stranger: no rights.
        assertEquals(WardOutcome.NO_RIGHTS, run(service.remove(stranger, wardId)).outcome());
        // Team member (rank below manage-ranks): no rights.
        assertEquals(WardOutcome.NO_RIGHTS, run(service.remove(member, wardId)).outcome());
        // Officer: allowed.
        assertTrue(run(service.remove(officer, wardId)).success());
        assertEquals(0, index.size());
        assertEquals(Optional.empty(), service.wardIdAtAnchor(
                Ward.AnchorKey.of("world", 100, 64, 100)));
    }

    @Test
    void toggleFlipsActiveAndClearsDetectionState() {
        assertTrue(run(create(owner, "flippy", 100, 100, 64, WardType.WARD)).success());
        UUID wardId = index.all().get(0).id();

        WardResult toggled = run(service.toggle(owner, wardId));
        assertTrue(toggled.success());
        assertFalse(toggled.ward().active());
        assertFalse(index.byId(wardId).orElseThrow().active());

        // Toggle back: mirror follows again.
        assertTrue(run(service.toggle(owner, wardId)).success());
        assertTrue(index.byId(wardId).orElseThrow().active());

        // Toggling is rank-guarded like removal.
        assertEquals(WardOutcome.NO_RIGHTS, run(service.toggle(member, wardId)).outcome());
    }

    @Test
    void removeByAnchorIsTheNoClaimEscapeHatchAndIdempotent() {
        assertTrue(run(create(owner, "breakme", 100, 100, 64, WardType.WARD)).success());
        Ward.AnchorKey anchor = Ward.AnchorKey.of("world", 100, 64, 100);

        WardResult broken = run(service.removeByAnchor(anchor));
        assertTrue(broken.success());
        assertEquals("breakme", broken.ward().name());
        assertEquals(0, index.size());
        assertEquals(Optional.empty(), service.wardIdAtAnchor(anchor));

        // Second break of the same block: nothing left, no error.
        assertEquals(WardOutcome.NOT_FOUND, run(service.removeByAnchor(anchor)).outcome());
    }

    @Test
    void removalAndToggleClearDetectionPairsForThatWard() {
        assertTrue(run(create(owner, "watched", 100, 100, 64, WardType.WARD)).success());
        UUID wardId = index.all().get(0).id();

        // Real detection pass: two teamless outsiders enter the radius.
        WardDetector detector = new WardDetector(config(), state, fakeTeams(),
                (p, now) -> false);
        Ward ward = index.byId(wardId).orElseThrow();
        UUID secondStranger = UUID.randomUUID();
        assertEquals(1, detector.detect(stranger, "world", 100, 100,
                List.of(ward), 1_000L).size());
        assertEquals(1, detector.detect(secondStranger, "world", 100, 100,
                List.of(ward), 1_000L).size());
        assertEquals(2, service.reportedInsiders(wardId));

        assertTrue(run(service.remove(owner, wardId)).success());
        assertEquals(0, service.reportedInsiders(wardId),
                "ward removal must drop its detection pairs");
    }

    // ------------------------------------------------------------------
    // Restart round-trip
    // ------------------------------------------------------------------

    @Test
    void loadAllRebuildsTheMirrorFromTheDatabase() {
        assertTrue(run(create(owner, "persist1", 100, 100, 64, WardType.WARD)).success());
        assertTrue(run(create(stranger, "persist2", 900, 900, 32, WardType.DECOY)).success());

        // Fresh service + fresh index over the same database: restart.
        WardSpatialIndex newIndex = WardSpatialIndex.withDefaultCellSize();
        WardService restarted = new WardService(db, config(), newIndex,
                new DetectionState(3_600_000L), fakeTeams(), () -> 1L);
        await(restarted.loadAll());

        assertEquals(2, newIndex.size());
        Ward persisted = newIndex.byId(index.all().stream()
                .filter(w -> w.name().equals("persist1")).findFirst().orElseThrow().id())
                .orElseThrow();
        assertEquals(owner, persisted.owner());
        assertEquals(Optional.of(teamId), persisted.teamId());
        assertTrue(persisted.active());
        Ward decoy = newIndex.all().stream()
                .filter(w -> w.name().equals("persist2")).findFirst().orElseThrow();
        assertEquals(WardType.DECOY, decoy.type());
        assertTrue(restarted.wardIdAtAnchor(Ward.AnchorKey.of("world", 900, 64, 900))
                .isPresent(), "anchor map rebuilds too");

        // Toggled state survives the same way.
        assertTrue(run(restarted.toggle(stranger, decoy.id())).success());
        WardSpatialIndex thirdIndex = WardSpatialIndex.withDefaultCellSize();
        WardService third = new WardService(db, config(), thirdIndex,
                new DetectionState(3_600_000L), fakeTeams(), () -> 1L);
        await(third.loadAll());
        assertFalse(third.byId(decoy.id()).orElseThrow().active());
    }
}
