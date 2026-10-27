package net.zaminmc.rebellion.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hot-path cache semantics: lookup states, team index, ally refresh. */
final class TeamCacheTest {

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();

    private TeamCache.Snapshot member(UUID team) {
        return new TeamCache.Snapshot(team, "n", "N", "member", false, Set.of());
    }

    @Test
    void lookupDistinguishesNotLoadedFromTeamless() {
        TeamCache cache = new TeamCache();
        assertTrue(cache.lookup(p1).isEmpty(), "absent means not loaded");
        cache.putTeamless(p1);
        Optional<Optional<TeamCache.Snapshot>> loaded = cache.lookup(p1);
        assertTrue(loaded.isPresent() && loaded.get().isEmpty(),
                "loaded + teamless");
    }

    @Test
    void onlineTeammatesIndexTracksMembershipChanges() {
        UUID team = UUID.randomUUID();
        TeamCache cache = new TeamCache();
        cache.put(p1, member(team));
        cache.put(p2, member(team));
        assertEquals(Set.of(p1, p2), cache.onlineTeammates(p1));

        cache.evict(p2);
        assertEquals(Set.of(p1), cache.onlineTeammates(p1));

        // Moving to another team updates the index (no double membership).
        UUID other = UUID.randomUUID();
        cache.put(p2, member(other));
        assertFalse(cache.sameTeam(p1, p2));
        assertEquals(Set.of(p1), cache.onlineTeammates(p1));
        assertEquals(Set.of(p2), cache.onlineTeammates(p2));
    }

    @Test
    void updateAlliesRefreshesEveryOnlineMemberSnapshot() {
        UUID team = UUID.randomUUID();
        UUID ally = UUID.randomUUID();
        TeamCache cache = new TeamCache();
        cache.put(p1, member(team));
        cache.put(p2, member(team));
        cache.updateAllies(team, Set.of(ally));
        assertTrue(cache.lookup(p1).flatMap(o -> o).orElseThrow()
                .allies().contains(ally));
        assertTrue(cache.allied(p1, p2) == false); // same team, not ally
        assertTrue(cache.lookup(p2).flatMap(o -> o).orElseThrow()
                .allies().contains(ally));
    }

    @Test
    void updateRankAndEvictWorkOnSingleMembers() {
        UUID team = UUID.randomUUID();
        TeamCache cache = new TeamCache();
        cache.put(p1, member(team));
        cache.updateRank(p1, "officer");
        assertEquals("officer",
                cache.lookup(p1).flatMap(o -> o).orElseThrow().rank());
        cache.evict(p1);
        assertTrue(cache.lookup(p1).isEmpty());
        assertEquals(0, cache.onlineTeamSize(p1));
    }

    @Test
    void evictTeamClearsEveryMemberAndTheIndex() {
        UUID team = UUID.randomUUID();
        TeamCache cache = new TeamCache();
        cache.put(p1, member(team));
        cache.put(p2, member(team));
        cache.evictTeam(team, java.util.List.of(p1, p2, p3));
        assertTrue(cache.lookup(p1).isEmpty());
        assertTrue(cache.lookup(p2).isEmpty());
        assertEquals(Set.of(p2), cache.onlineTeammates(p2),
                "teamless players only see themselves");
    }
}
