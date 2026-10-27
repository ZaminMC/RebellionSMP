package net.zaminmc.rebellion.wards.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import org.junit.jupiter.api.Test;

/**
 * {@link WardDetector} — the full §5.2 rule set under a fixed clock and
 * fake resolvers: enter/leave cycles, per-pair cooldowns, dwell warning
 * delay, masking suppression (at entry and mid-dwell), outsider definition
 * through the affiliation cache, decoy labeling, inactive wards, and state
 * cleanup. Every alert is an {@link AlertDecision} — information only; no
 * test can even express a protection side effect.
 */
class WardDetectorTest {

    private static final String WORLD = "world";
    private static final String NETHER = "world_nether";

    private final UUID owner = UUID.randomUUID();
    private final UUID intruder = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    // Fixed test config: instant dwell, 300s re-alert cooldown, leave notices on.
    private WardsConfig config(long dwellSeconds, boolean notifyLeave) {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12, 2000,
                Set.of("leader", "officer"), true, 20, dwellSeconds, true,
                "AMETHYST_SHARD", AlertChannel.TEAM, 300, notifyLeave,
                Map.of(), "blocks", false);
    }

    private Ward ward(double x, double z, double radius) {
        return new Ward(UUID.randomUUID(), "north", owner, Optional.of(teamId),
                WORLD, x, 64.0, z, radius, WardType.WARD, true, 0L);
    }

    /** Fake affiliation: owner+teammate share TEAM; everyone else teamless. */
    private TeamAffiliationService fakeTeams() {
        return new TeamAffiliationService() {
            @Override
            public Optional<UUID> teamOf(UUID player) {
                return player.equals(owner) || player.equals(teammate)
                        ? Optional.of(teamId) : Optional.empty();
            }

            @Override
            public Optional<String> rankOf(UUID player) {
                return player.equals(owner) ? Optional.of("leader")
                        : player.equals(teammate) ? Optional.of("officer")
                        : Optional.empty();
            }

            @Override
            public Set<UUID> onlineMembersOf(UUID team) {
                return team.equals(teamId) ? Set.of(owner, teammate) : Set.of();
            }
        };
    }

    private WardDetector detector(WardsConfig config, MaskingResolver masking) {
        return new WardDetector(config, new DetectionState(3_600_000L),
                fakeTeams(), masking);
    }

    // ------------------------------------------------------------------
    // Enter/leave cycles
    // ------------------------------------------------------------------

    @Test
    void enterAlertsOnceAndMovingInsideStaysSilent() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        List<AlertDecision> first = detector.detect(intruder, WORLD, 10, 10,
                List.of(ward), 1_000L);
        assertEquals(1, first.size());
        assertEquals(AlertDecision.Kind.ENTER, first.get(0).kind());
        assertTrue(first.get(0).decoy() == (ward.type() == WardType.DECOY));

        // Same sweep-equivalent position changes inside: silent.
        assertTrue(detector.detect(intruder, WORLD, 20, 20, List.of(ward), 2_000L).isEmpty());
        assertTrue(detector.detect(intruder, WORLD, 30, 30, List.of(ward), 3_000L).isEmpty());
        assertEquals(1, detector.state().insidersOf(ward.id()));
    }

    @Test
    void leaveNotifiesOncePerCycleAndOnlyAfterAReportedEnter() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size() == 1);
        List<AlertDecision> leave = detector.detect(intruder, WORLD, 500, 500,
                List.of(ward), 2_000L);
        assertEquals(1, leave.size());
        assertEquals(AlertDecision.Kind.LEAVE, leave.get(0).kind());

        // Second leave is impossible: not inside.
        assertTrue(detector.detect(intruder, WORLD, 600, 600, List.of(ward), 3_000L).isEmpty());
    }

    @Test
    void leaveNoticesCanBeDisabledByConfig() {
        WardDetector detector = detector(config(0, false), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        assertTrue(detector.detect(intruder, WORLD, 500, 500, List.of(ward), 2_000L).isEmpty());
    }

    @Test
    void boundaryIsInclusiveAtExactlyTheRadius() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);
        // 64 blocks out on the diagonal axis: exactly at radius.
        assertEquals(1, detector.detect(intruder, WORLD, 64, 0,
                List.of(ward), 1_000L).size());
    }

    // ------------------------------------------------------------------
    // Re-alert cooldown (per ward, per player)
    // ------------------------------------------------------------------

    @Test
    void reEnterInsideCooldownIsSilentButAfterCooldownAlertsAgain() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        // Leave, then re-enter 100s later: inside the 300s cooldown.
        List<AlertDecision> leave = detector.detect(intruder, WORLD, 999, 999,
                List.of(ward), 2_000L);
        assertEquals(1, leave.size(), "the reported enter earns a leave notice");
        assertEquals(AlertDecision.Kind.LEAVE, leave.get(0).kind());
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 102_000L).isEmpty(),
                "re-enter inside the cooldown stays silent");

        // Leave without a reported enter: no notice (suppressed re-entry).
        assertTrue(detector.detect(intruder, WORLD, 999, 999, List.of(ward), 103_000L).isEmpty(),
                "unreported re-entry leaves silently");
        List<AlertDecision> again = detector.detect(intruder, WORLD, 5, 5,
                List.of(ward), 400_000L);
        assertEquals(1, again.size());
        assertEquals(AlertDecision.Kind.ENTER, again.get(0).kind());
    }

    @Test
    void cooldownIsPerWardNotPerPlayer() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward north = new Ward(UUID.randomUUID(), "north", owner, Optional.of(teamId),
                WORLD, 0, 64, 0, 64, WardType.WARD, true, 0L);
        Ward south = new Ward(UUID.randomUUID(), "south", owner, Optional.of(teamId),
                WORLD, 300, 64, 300, 64, WardType.WARD, true, 0L);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0,
                List.of(north, south), 1_000L).size());
        // Same player walks over to the other ward: south's enter is new
        // intel (never throttled by north's pair), and north's reported
        // enter earns its leave notice.
        List<AlertDecision> both = detector.detect(intruder, WORLD, 300, 300,
                List.of(north, south), 2_000L);
        assertEquals(2, both.size());
        assertTrue(both.stream().anyMatch(a -> a.kind() == AlertDecision.Kind.ENTER
                && a.ward().id().equals(south.id())), "south enter alert fires");
        assertTrue(both.stream().anyMatch(a -> a.kind() == AlertDecision.Kind.LEAVE
                && a.ward().id().equals(north.id())), "north leave notice fires");
    }

    // ------------------------------------------------------------------
    // Outsider definition
    // ------------------------------------------------------------------

    @Test
    void ownerAndTeammatesAreNeverOutsiders() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertTrue(detector.detect(owner, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        assertTrue(detector.detect(teammate, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        // And their "entry" is bookkeeping only: no leave notice later.
        assertTrue(detector.detect(owner, WORLD, 999, 999, List.of(ward), 2_000L).isEmpty());
        assertTrue(detector.detect(teammate, WORLD, 999, 999, List.of(ward), 2_000L).isEmpty());
    }

    @Test
    void withoutTeamsModuleEveryoneExceptTheOwnerIsAnOutsider() {
        WardDetector solo = new WardDetector(config(0, true), new DetectionState(3_600_000L),
                null, (p, now) -> false);
        Ward ward = new Ward(UUID.randomUUID(), "north", owner, Optional.of(teamId),
                WORLD, 0, 64, 0, 64, WardType.WARD, true, 0L);

        assertTrue(solo.detect(owner, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        assertEquals(1, solo.detect(teammate, WORLD, 0, 0, List.of(ward), 1_000L).size());
    }

    @Test
    void coldCacheMeansOutsiderNotTrusted() {
        // An affiliation that "has not loaded" anyone reports empty for the
        // intruder - the detector must alert (never silently trust unknown).
        TeamAffiliationService coldCache = new TeamAffiliationService() {
            @Override
            public Optional<UUID> teamOf(UUID player) {
                return Optional.empty();
            }

            @Override
            public Optional<String> rankOf(UUID player) {
                return Optional.empty();
            }

            @Override
            public Set<UUID> onlineMembersOf(UUID team) {
                return Set.of();
            }
        };
        WardDetector detector = new WardDetector(config(0, true), new DetectionState(3_600_000L),
                coldCache, (p, now) -> false);
        Ward ward = ward(0, 0, 64);
        assertEquals(1, detector.detect(teammate, WORLD, 0, 0, List.of(ward), 1_000L).size());
    }

    // ------------------------------------------------------------------
    // Dwell (warning delay)
    // ------------------------------------------------------------------

    @Test
    void dwellDelaysTheAlertUntilThePlayerRemains() {
        WardDetector detector = detector(config(10, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        // Entered, but only 5 of 10 required seconds passed: no alert.
        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 6_000L).isEmpty());

        // Still inside at 10s+: the pending alert commits.
        List<AlertDecision> committed = detector.detect(intruder, WORLD, 5, 5,
                List.of(ward), 11_500L);
        assertEquals(1, committed.size());
        assertEquals(AlertDecision.Kind.ENTER, committed.get(0).kind());
    }

    @Test
    void leavingBeforeDwellProducesNothingAtAll() {
        WardDetector detector = detector(config(10, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        // Grazed the edge and left before the dwell: no enter, so no leave
        // notice either - edge-grazing is silent by design.
        assertTrue(detector.detect(intruder, WORLD, 999, 999, List.of(ward), 5_000L).isEmpty());
        assertTrue(detector.state().insidePlayers(6_000L).isEmpty());
    }

    @Test
    void maskingDuringTheDwellWindowSuppressesThePendingAlert() {
        // Masked from 6s onward; dwell would commit at 10s.
        WardDetector detector = detector(config(10, true),
                (p, now) -> p.equals(intruder) && now >= 6_000L);
        Ward ward = ward(0, 0, 64);

        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 6_000L).isEmpty());
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 11_500L).isEmpty(),
                "masking during dwell must suppress the commit");
        // Suppressed means never reported: a later leave has no notice.
        assertTrue(detector.detect(intruder, WORLD, 999, 999, List.of(ward), 15_000L).isEmpty());
    }

    // ------------------------------------------------------------------
    // Masking
    // ------------------------------------------------------------------

    @Test
    void maskedPlayersNeverTriggerEnterAlerts() {
        boolean[] masked = {true};
        WardDetector detector = detector(config(0, true), (p, now) -> masked[0]);
        Ward ward = ward(0, 0, 64);

        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).isEmpty());
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 2_000L).isEmpty());
        // Unmask INSIDE the radius: the sentry never saw the entry, so there
        // is no new transition to report (documented masking semantics).
        masked[0] = false;
        assertTrue(detector.detect(intruder, WORLD, 5, 5, List.of(ward), 3_000L).isEmpty(),
                "unmasking inside must not retro-detect");
        // But their leave after unmasking is also silent (never reported).
        assertTrue(detector.detect(intruder, WORLD, 999, 999, List.of(ward), 4_000L).isEmpty());
    }

    @Test
    void maskingNeverUndoesADeliveredAlert() {
        // Alert at t=1000 unmasked; mask afterwards: leave still notifies.
        boolean[] masked = {false};
        WardDetector detector = detector(config(0, true), (p, now) -> masked[0]);
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        masked[0] = true;
        List<AlertDecision> leave = detector.detect(intruder, WORLD, 999, 999,
                List.of(ward), 2_000L);
        assertEquals(1, leave.size());
        assertEquals(AlertDecision.Kind.LEAVE, leave.get(0).kind());
    }

    // ------------------------------------------------------------------
    // Decoys
    // ------------------------------------------------------------------

    @Test
    void decoyWardsDetectIdenticallyButAreLabeled() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward decoy = new Ward(UUID.randomUUID(), "fake", owner, Optional.of(teamId),
                WORLD, 0, 64, 0, 64, WardType.DECOY, true, 0L);

        List<AlertDecision> alerts = detector.detect(intruder, WORLD, 30, 40,
                List.of(decoy), 1_000L);
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).decoy(), "decoy alerts carry the label so own-intel can mark them");
        assertEquals(50.0, alerts.get(0).distanceBlocks(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Inactive wards + world isolation + state cleanup
    // ------------------------------------------------------------------

    @Test
    void inactiveWardsAreSkippedEntirely() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward inactive = ward(0, 0, 64).withActive(false);
        assertTrue(detector.detect(intruder, WORLD, 0, 0, List.of(inactive), 1_000L).isEmpty());
    }

    @Test
    void crossWorldWardsCannotAppearAsCandidates() {
        // The index is per-world, so a nether-positioned pass must never see
        // overworld wards; the detector documents and preserves that.
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward overworldWard = ward(0, 0, 64);
        // A detector pass for a player in the nether receives only nether
        // candidates (empty here): nothing fires, no state is created.
        assertTrue(detector.detect(intruder, NETHER, 0, 0, List.of(), 1_000L).isEmpty());
        assertTrue(detector.state().insidePlayers(1_000L).isEmpty());
        // The same ward still detects in its own world.
        assertEquals(1, detector.detect(intruder, WORLD, 0, 0,
                List.of(overworldWard), 2_000L).size());
    }

    @Test
    void quitClearsAllPlayerPairs() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward north = ward(0, 0, 64);
        Ward south = new Ward(UUID.randomUUID(), "south", owner, Optional.of(teamId),
                WORLD, 300, 64, 300, 64, WardType.WARD, true, 0L);

        detector.detect(intruder, WORLD, 0, 0, List.of(north, south), 1_000L);
        assertFalse(detector.state().insidePlayers(1_000L).isEmpty());
        detector.state().clearPlayer(intruder);
        assertTrue(detector.state().insidePlayers(1_000L).isEmpty());
        assertEquals(0, detector.state().size());
    }

    @Test
    void wardRemovalClearsItsPairsSoTogglingBackReDetects() {
        WardDetector detector = detector(config(0, true), (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        detector.state().clearWard(ward.id());
        // Service toggles/removes call clearWard; a re-added active ward
        // treats everyone as outside again -> fresh enter alert.
        List<AlertDecision> fresh = detector.detect(intruder, WORLD, 0, 0,
                List.of(ward), 1_500L);
        assertEquals(1, fresh.size());
    }

    // ------------------------------------------------------------------
    // Memory bounds
    // ------------------------------------------------------------------

    @Test
    void idlePairsArePrunedByTheInsidePlayersPass() {
        DetectionState state = new DetectionState(1_000L); // prune after 1s
        WardDetector detector = new WardDetector(config(0, true), state, fakeTeams(),
                (p, now) -> false);
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        assertEquals(1, state.size());
        // Left (with its leave notice), pair goes idle; after the prune
        // horizon it disappears.
        assertEquals(1, detector.detect(intruder, WORLD, 999, 999, List.of(ward), 2_000L).size());
        assertEquals(1, state.size(), "idle pair survives the leave until the horizon");
        state.insidePlayers(3_500L); // pruning pass
        assertEquals(0, state.size(), "idle pair past the horizon is pruned");
    }

    @Test
    void insidersCountReportsOnlyAlertedOnes() {
        UUID maskedIntruder = UUID.randomUUID();
        WardDetector detector = detector(config(0, true),
                (p, now) -> p.equals(maskedIntruder));
        Ward ward = ward(0, 0, 64);

        assertEquals(1, detector.detect(intruder, WORLD, 0, 0, List.of(ward), 1_000L).size());
        assertTrue(detector.detect(maskedIntruder, WORLD, 0, 0, List.of(ward), 1_000L)
                .isEmpty());
        // One store, one ward: the reported intruder counts, the suppressed
        // one is intentionally invisible to the owner.
        assertEquals(1, detector.state().insidersOf(ward.id()));
    }
}
