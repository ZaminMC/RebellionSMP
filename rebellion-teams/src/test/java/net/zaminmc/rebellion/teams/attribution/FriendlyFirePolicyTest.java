package net.zaminmc.rebellion.teams.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.teams.TeamCache;
import org.junit.jupiter.api.Test;

/**
 * The full friendly-fire decision matrix from ADR-0006, covering the
 * attribution sources the listener feeds in: direct melee (player), arrow
 * (projectile shooter), pet (tameable owner), lingering cloud (cloud
 * source), TNT (igniter), and unresolvable sources.
 */
final class FriendlyFirePolicyTest {

    private final UUID attacker = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();

    private TeamCache cacheWith(TeamCache.Snapshot attackerSnapshot,
            TeamCache.Snapshot victimSnapshot) {
        TeamCache cache = new TeamCache();
        if (attackerSnapshot != null) {
            cache.put(attacker, attackerSnapshot);
        } else {
            cache.putTeamless(attacker);
        }
        if (victimSnapshot != null) {
            cache.put(victim, victimSnapshot);
        } else {
            cache.putTeamless(victim);
        }
        return cache;
    }

    private TeamCache.Snapshot member(UUID team, boolean friendlyFire, Set<UUID> allies) {
        return new TeamCache.Snapshot(team, team.toString(), team.toString(),
                "member", friendlyFire, allies);
    }

    @Test
    void unresolvableSourcesAreAlwaysAllowed() {
        // Creeper explosion, natural fire, environment: attacker = null.
        TeamCache cache = cacheWith(
                member(UUID.randomUUID(), false, Set.of()),
                member(UUID.randomUUID(), false, Set.of()));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(null, victim, cache, true));
    }

    @Test
    void selfDamageIsAllowedEvenWithFriendlyFireOff() {
        TeamCache cache = new TeamCache();
        UUID team = UUID.randomUUID();
        cache.put(attacker, member(team, false, Set.of()));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, attacker, cache, true));
    }

    @Test
    void cacheMissOnEitherSideAllowsDamage() {
        // Victim not loaded yet (just joined): allow, never guess.
        TeamCache cache = new TeamCache();
        cache.put(attacker, member(UUID.randomUUID(), false, Set.of()));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim, cache, true));

        // Attacker not loaded yet.
        TeamCache cache2 = new TeamCache();
        cache2.put(victim, member(UUID.randomUUID(), false, Set.of()));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim, cache2, true));
    }

    @Test
    void teamlessParticipantsAreNeverProtected() {
        UUID team = UUID.randomUUID();
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim,
                        cacheWith(member(team, false, Set.of()), null), false));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim,
                        cacheWith(null, member(team, false, Set.of())), false));
    }

    @Test
    void sameTeamRespectsTheFriendlyFireFlag() {
        UUID team = UUID.randomUUID();
        // Friendly fire disabled -> projectile from a teammate is cancelled.
        assertEquals(FriendlyFirePolicy.Decision.CANCEL,
                FriendlyFirePolicy.decide(attacker, victim,
                        cacheWith(member(team, false, Set.of()),
                                member(team, false, Set.of())), false));
        // Friendly fire explicitly enabled -> melee between teammates allowed.
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim,
                        cacheWith(member(team, true, Set.of()),
                                member(team, true, Set.of())), false));
    }

    @Test
    void petTntAndCloudSourcesResolveThroughTheSameMatrix() {
        // Pet owner / TNT igniter / cloud source all arrive as an attacker
        // UUID — the matrix is identical to the direct case.
        UUID team = UUID.randomUUID();
        TeamCache cache = cacheWith(member(team, false, Set.of()),
                member(team, false, Set.of()));
        assertEquals(FriendlyFirePolicy.Decision.CANCEL,
                FriendlyFirePolicy.decide(attacker, victim, cache, false));
    }

    @Test
    void alliesProtectedOnlyWhenConfigured() {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        TeamCache allied = cacheWith(
                member(teamA, false, Set.of(teamB)),
                member(teamB, false, Set.of(teamA)));
        // Off by default: allies fight normally.
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim, allied, false));
        // On: the attacker's team chose to protect its allies too.
        assertEquals(FriendlyFirePolicy.Decision.CANCEL,
                FriendlyFirePolicy.decide(attacker, victim, allied, true));
    }

    @Test
    void allyProtectionFollowsTheAttackersFriendlyFireChoice() {
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        // Attacker's team has friendly fire ON: even with protect-allies,
        // they fight their allies (ally protection is an extension of the
        // team's own ff choice, never an override of it).
        TeamCache cache = cacheWith(
                member(teamA, true, Set.of(teamB)),
                member(teamB, false, Set.of(teamA)));
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim, cache, true));
    }

    @Test
    void unrelatedTeamsAreAlwaysAllowed() {
        assertEquals(FriendlyFirePolicy.Decision.ALLOW,
                FriendlyFirePolicy.decide(attacker, victim,
                        cacheWith(member(UUID.randomUUID(), false, Set.of()),
                                member(UUID.randomUUID(), false, Set.of())), true));
    }
}
