package net.zaminmc.rebellion.teams.listener;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.zaminmc.rebellion.teams.TeamService;
import net.zaminmc.rebellion.teams.attribution.FriendlyFirePolicy;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Friendly-fire cancellation adapter (ADR-0006).
 *
 * <p>Attribution chain (all APIs verified against paper-api 26.3.226):
 * direct {@link Player} → {@link Projectile#getShooter()} →
 * {@link Tameable#getOwnerUniqueId()} → {@link AreaEffectCloud#getSource()}
 * → {@link TNTPrimed#getSource()}. Sources outside this chain (creepers,
 * natural fire, environment) are deliberately unattributed: the policy
 * allows them rather than guess. The event handlers are memory-only; the
 * decision logic lives in the unit-tested {@link FriendlyFirePolicy}.</p>
 */
public final class FriendlyFireListener implements Listener {

    private final TeamService service;
    private final Logger logger;
    /** Bounded diagnostics: at most one cache-miss warning per player/min. */
    private final ConcurrentHashMap<UUID, Long> lastMissWarning = new ConcurrentHashMap<>();

    public FriendlyFireListener(TeamService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        UUID attacker = resolveAttacker(event.getDamager());
        if (attacker != null && service.cache().lookup(attacker).isEmpty()) {
            missDiagnostic(attacker);
        }
        if (FriendlyFirePolicy.decide(attacker, victim.getUniqueId(), service.cache(),
                service.config().protectAllies()) == FriendlyFirePolicy.Decision.CANCEL) {
            event.setCancelled(true);
        }
    }

    /** Lingering potion clouds damage via AreaEffectCloudApplyEvent. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloudApply(AreaEffectCloudApplyEvent event) {
        var cloud = event.getEntity();
        if (!(cloud instanceof AreaEffectCloud aec)) {
            return;
        }
        UUID attacker = cloudSourcePlayer(aec);
        if (attacker == null) {
            return; // unattributable cloud: never guessed
        }
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim
                && FriendlyFirePolicy.decide(attacker, victim.getUniqueId(), service.cache(),
                        service.config().protectAllies())
                        == FriendlyFirePolicy.Decision.CANCEL);
    }

    /** Normal splash potions: zero the intensity for protected teammates. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        var shooter = event.getPotion().getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }
        for (var entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim
                    && FriendlyFirePolicy.decide(player.getUniqueId(), victim.getUniqueId(),
                            service.cache(), service.config().protectAllies())
                            == FriendlyFirePolicy.Decision.CANCEL) {
                event.setIntensity(victim, 0.0);
            }
        }
    }

    /**
     * Resolves the player ultimately responsible for {@code damager}'s
     * damage, or null when it cannot be attributed safely.
     */
    static UUID resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile) {
            return shooterPlayer(projectile.getShooter());
        }
        if (damager instanceof Tameable tameable) {
            return tameable.getOwnerUniqueId();
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return cloudSourcePlayer(cloud);
        }
        if (damager instanceof TNTPrimed tnt) {
            return tnt.getSource() instanceof Player source
                    ? source.getUniqueId() : null;
        }
        return null;
    }

    private static UUID cloudSourcePlayer(AreaEffectCloud cloud) {
        ProjectileSource source = cloud.getSource();
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    private static UUID shooterPlayer(ProjectileSource source) {
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    private void missDiagnostic(UUID player) {
        long now = System.currentTimeMillis();
        Long last = lastMissWarning.get(player);
        if (last != null && now - last < 60_000L) {
            return;
        }
        lastMissWarning.put(player, now);
        logger.warning(() -> "Team membership for " + player
                + " is not loaded yet; damage involving them is allowed until it is.");
    }
}
