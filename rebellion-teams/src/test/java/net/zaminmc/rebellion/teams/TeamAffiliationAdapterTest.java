package net.zaminmc.rebellion.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TeamAffiliationAdapter} — the cross-module affiliation contract
 * (ADR-0007). Verifies the cache-only semantics: not-loaded players report
 * empty (consumers treat them as teamless), loaded teamless players report
 * empty, and real snapshots expose team id, rank and online members without
 * any database access.
 */
class TeamAffiliationAdapterTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final UUID TEAM = UUID.randomUUID();

    @Test
    void unknownPlayerReportsTeamlessAndNoRank() {
        TeamAffiliationAdapter adapter = new TeamAffiliationAdapter(new TeamCache());
        assertTrue(adapter.teamOf(ALICE).isEmpty());
        assertTrue(adapter.rankOf(ALICE).isEmpty());
        assertTrue(adapter.onlineMembersOf(TEAM).isEmpty());
        assertFalse(adapter.sameTeam(ALICE, BOB));
    }

    @Test
    void loadedTeamlessPlayerIsEmptyNotUnknown() {
        TeamCache cache = new TeamCache();
        cache.putTeamless(ALICE);
        TeamAffiliationAdapter adapter = new TeamAffiliationAdapter(cache);
        assertTrue(adapter.teamOf(ALICE).isEmpty());
        assertTrue(adapter.rankOf(ALICE).isEmpty());
    }

    @Test
    void sameTeamOnlyForMembersOfIdenticalTeamId() {
        TeamCache cache = new TeamCache();
        cache.put(ALICE, new TeamCache.Snapshot(TEAM, "nightwatch", "Nightwatch",
                "leader", false, Set.of()));
        cache.put(BOB, new TeamCache.Snapshot(TEAM, "nightwatch", "Nightwatch",
                "member", false, Set.of()));
        cache.putTeamless(CAROL);
        TeamAffiliationAdapter adapter = new TeamAffiliationAdapter(cache);

        assertEquals(Optional.of(TEAM), adapter.teamOf(ALICE));
        assertEquals(Optional.of("leader"), adapter.rankOf(ALICE));
        assertTrue(adapter.sameTeam(ALICE, BOB));
        assertFalse(adapter.sameTeam(ALICE, CAROL));
        assertFalse(adapter.sameTeam(BOB, CAROL));
    }

    @Test
    void onlineMembersOfTeamReflectsCacheAndEmptyAfterEviction() {
        TeamCache cache = new TeamCache();
        cache.put(ALICE, new TeamCache.Snapshot(TEAM, "nightwatch", "Nightwatch",
                "leader", false, Set.of()));
        cache.put(BOB, new TeamCache.Snapshot(TEAM, "nightwatch", "Nightwatch",
                "member", false, Set.of()));
        TeamAffiliationAdapter adapter = new TeamAffiliationAdapter(cache);

        assertEquals(Set.of(ALICE, BOB), adapter.onlineMembersOf(TEAM));
        assertEquals(Set.of(ALICE, BOB), adapter.onlineTeammatesOf(ALICE));

        cache.evict(BOB);
        assertEquals(Set.of(ALICE), adapter.onlineMembersOf(TEAM));
    }

    @Test
    void onlineTeammatesFallsBackToSelfWhenTeamlessOrUnresolved() {
        TeamCache cache = new TeamCache();
        cache.putTeamless(CAROL);
        cache.put(ALICE, new TeamCache.Snapshot(TEAM, "nightwatch", "Nightwatch",
                "leader", false, Set.of()));
        TeamAffiliationAdapter adapter = new TeamAffiliationAdapter(cache);

        assertEquals(Set.of(CAROL), adapter.onlineTeammatesOf(CAROL));
        assertEquals(Set.of(ALICE), adapter.onlineTeammatesOf(ALICE));
    }
}
