package net.zaminmc.rebellion.teams.attribution;

import java.util.UUID;
import net.zaminmc.rebellion.teams.TeamCache;

/**
 * Friendly-fire cancellation policy (ADR-0006) — pure decision logic over an
 * already-attributed attacker UUID and the membership cache. The Bukkit
 * adapter resolves "who caused this damage" (direct player, projectile
 * shooter, tameable pet owner, area-effect-cloud source, TNT igniter);
 * unresolvable sources reach this policy as a null attacker and are always
 * allowed. Attribution must be safe, never guessed.
 *
 * <p>Matrix (all branches tested):</p>
 * <ul>
 *   <li>unresolved attacker → ALLOW (creeper, environment, fire tick)</li>
 *   <li>self-damage → ALLOW</li>
 *   <li>cache miss on either side → ALLOW (join still loading; the listener
 *       emits a bounded diagnostic, never a DB call)</li>
 *   <li>either side teamless → ALLOW</li>
 *   <li>same team, friendly fire enabled → ALLOW (explicitly permitted)</li>
 *   <li>same team, friendly fire disabled → CANCEL</li>
 *   <li>allies, protect-allies on, attacker's team has friendly fire
 *       disabled → CANCEL; otherwise ALLOW</li>
 *   <li>unrelated teams → ALLOW</li>
 * </ul>
 */
public final class FriendlyFirePolicy {

    public enum Decision { ALLOW, CANCEL }

    private FriendlyFirePolicy() {
    }

    /**
     * @param attacker       resolved attacker player UUID, or null when the
     *                       source could not be attributed safely
     * @param victim         the damaged player
     * @param cache          membership cache (hot path; no DB access)
     * @param protectAllies  config: apply same-team protection to allies too
     */
    public static Decision decide(UUID attacker, UUID victim, TeamCache cache,
            boolean protectAllies) {
        if (attacker == null || attacker.equals(victim)) {
            return Decision.ALLOW;
        }
        var attackerTeam = cache.lookup(attacker).flatMap(o -> o);
        if (attackerTeam.isEmpty()) {
            return Decision.ALLOW;
        }
        var victimTeam = cache.lookup(victim).flatMap(o -> o);
        if (victimTeam.isEmpty()) {
            return Decision.ALLOW;
        }
        TeamCache.Snapshot attackerSnapshot = attackerTeam.get();
        TeamCache.Snapshot victimSnapshot = victimTeam.get();

        if (attackerSnapshot.teamId().equals(victimSnapshot.teamId())) {
            // Same team: cancelled only when the team disabled friendly fire.
            return attackerSnapshot.friendlyFire() ? Decision.ALLOW : Decision.CANCEL;
        }
        if (protectAllies
                && !attackerSnapshot.friendlyFire()
                && attackerSnapshot.allies().contains(victimSnapshot.teamId())) {
            // The attacker's team chose to protect its allies as well.
            return Decision.CANCEL;
        }
        return Decision.ALLOW;
    }
}
