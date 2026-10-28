package net.zaminmc.rebellion.dragons.listener;

import com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.control.PetController;
import net.zaminmc.rebellion.dragons.control.PetController.FeedResult;
import net.zaminmc.rebellion.dragons.control.PetController.MountOutcome;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Pet interaction surface: pearl feeding, the horse-like ride
 * interaction, the friendly damage shield, the configurable water
 * weakness, friendly fireball-hit cancellation, and the closed-loop
 * steering phase guard.
 *
 * <ul>
 *   <li><strong>Pearl feeding:</strong> right-click with an Ender Pearl in
 *       the main hand. The heal CAS (cooldown-guarded, entity-pinned) runs
 *       before the pearl is consumed — a retried event or double click
 *       inside the cooldown is refused before anything is touched
 *       (§5.6: atomic pearl consumption + health update).</li>
 *   <li><strong>Horse-like riding:</strong> right-click WITHOUT a pearl is
 *       the mount interaction for the owner (right-click to ride, sneak to
 *       land — the same vocabulary as a saddle horse). The command path
 *       (/dragon mount) and the interaction path share the same controller
 *       checks; non-owners are told the dragon is not theirs.</li>
 *   <li><strong>Friendly shield:</strong> a bound pet never harms its own
 *       side. Vanilla boss contact/wing damage on the owner, a teammate
 *       (teams module present) or the current rider is cancelled, and so
 *       is damage from the lingering impact cloud of one of our own
 *       fireballs (§5.6 friendly fire; §5.1 counts pet attacks as a
 *       friendly-fire source).</li>
 *   <li><strong>Water weakness:</strong> incoming damage on a bound pet is
 *       multiplied by the configured factor while the pet is in water or
 *       rain — a slight, bounded penalty, never an instant death. The
 *       multiplier is validated at config load (1.0..4.0).</li>
 *   <li><strong>Fireball hits:</strong> plugin-launched fireballs (there is
 *       no shooter on a spawned DragonFireball, so ours are identified by
 *       UUID) never harm the owner, or a teammate when the teams module is
 *       present. Everything else is vanilla semantics: cloud damage, no
 *       block damage (measured, ADR-0008 E6). Impact clouds that survive
 *       against hostile targets are tracked so the shield covers their
 *       lingering damage too.</li>
 *   <li><strong>Phase guard:</strong> while a pet is ridden, vanilla phase
 *       transitions away from FLY_TO_PORTAL are corrected — the control
 *       hook of the closed-loop podium steering (E2c).</li>
 * </ul>
 */
public final class PetInteractListener implements Listener {

    private final LineageService lineages;
    private final PetController controller;

    public PetInteractListener(@NotNull LineageService lineages,
            @NotNull PetController controller) {
        this.lineages = lineages;
        this.controller = controller;
    }

    // ------------------------------------------------------------------
    // Pearl feeding + ride interaction
    // ------------------------------------------------------------------

    @EventHandler
    public void onRightClickPet(PlayerInteractEntityEvent event) {
        // Both hands fire this event; the interaction surface runs from the
        // main hand only.
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof EnderDragon pet)) {
            return;
        }
        Optional<Lineage> bound = lineages.byEntity(pet.getUniqueId());
        if (bound.isEmpty()) {
            return;
        }
        Player feeder = event.getPlayer();
        ItemStack held = feeder.getInventory().getItemInMainHand();
        if (held.getType() == Material.ENDER_PEARL) {
            event.setCancelled(true);
            controller.feedPearl(feeder, pet, bound.get(), held, result -> render(feeder, result,
                    bound.get()));
            return;
        }
        // No pearl: the ride interaction (right-click to ride, like a saddle
        // horse; sneak to land). Cancelling also keeps the off-hand follow-up
        // event from double-firing the interaction.
        event.setCancelled(true);
        if (!feeder.getUniqueId().equals(bound.get().owner())) {
            feeder.sendMessage(controller.messages().msg("dragon.mount.not-yours"));
            return;
        }
        MountOutcome outcome = controller.mount(feeder);
        switch (outcome) {
            case OK -> feeder.sendMessage(controller.messages().msg("dragon.mount.ok"));
            case DISABLED -> feeder.sendMessage(
                    controller.messages().msg("dragon.mount.disabled"));
            case TOO_FAR -> feeder.sendMessage(
                    controller.messages().msg("dragon.mount.too-far"));
            case NOT_HATCHED -> feeder.sendMessage(
                    controller.messages().msg("dragon.mount.not-hatched"));
        }
    }

    private void render(Player feeder, FeedResult result, Lineage lineage) {
        switch (result) {
            case OK -> feeder.sendMessage(controller.messages().msg("feed.ok",
                    Placeholder.unparsed("amount",
                            String.valueOf(controller.config().pearlHealAmount()))));
            case COOLDOWN -> feeder.sendMessage(controller.messages().msg("feed.cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(
                            controller.pearlCooldownRemainingMs(lineage,
                                    System.currentTimeMillis()) / 1000L))));
            case NOT_OWNER -> feeder.sendMessage(controller.messages().msg("feed.not-owner",
                    Placeholder.unparsed("owner", String.valueOf(lineage.owner()))));
            default -> feeder.sendMessage(controller.messages().msg("common.unavailable"));
        }
    }

    // ------------------------------------------------------------------
    // Friendly shield (pet contact damage + own fireball clouds)
    // ------------------------------------------------------------------

    /**
     * A pet never harms its own side. Vanilla boss body/wing contact damage
     * from a bound pet is cancelled when the victim is the owner, a
     * teammate (teams module present) or whoever is currently riding it —
     * without this shield the boss body hurts the owner standing next to,
     * or flying on, their own dragon (live session-12 report). The same
     * shield covers the lingering impact cloud of one of OUR fireballs (a
     * hostile-impact cloud near the owner still breathes for a while).
     * Natural boss dragons and foreign clouds keep vanilla semantics.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPetHurtsFriendly(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (event.getDamager() instanceof EnderDragon pet) {
            Optional<Lineage> bound = lineages.byEntity(pet.getUniqueId());
            if (bound.isEmpty() || bound.get().ownerOpt().isEmpty()) {
                return; // natural boss dragon: vanilla rules
            }
            UUID owner = bound.get().ownerOpt().get();
            if (victim.getUniqueId().equals(owner) || isTeammate(owner, victim)
                    || pet.getPassengers().contains(victim)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getDamager() instanceof AreaEffectCloud cloud) {
            controller.cloudOwner(cloud.getUniqueId()).ifPresent(owner -> {
                if (victim.getUniqueId().equals(owner) || isTeammate(owner, victim)) {
                    event.setCancelled(true);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Water weakness
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPetDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof EnderDragon pet
                && lineages.byEntity(pet.getUniqueId()).isPresent()
                && pet.isInWaterOrRain()
                && controller.config().waterWeaknessMultiplier() > 1.0) {
            event.setDamage(event.getDamage() * controller.config().waterWeaknessMultiplier());
        }
    }

    // ------------------------------------------------------------------
    // Fireball friendly-fire cancellation
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireballHit(EnderDragonFireballHitEvent event) {
        UUID fireball = event.getEntity().getUniqueId();
        Optional<UUID> owner = controller.fireballOwner(fireball,
                System.currentTimeMillis());
        if (owner.isEmpty()) {
            return; // not one of ours (natural boss fireball): vanilla rules
        }
        controller.forgetFireball(fireball);
        for (LivingEntity target : event.getTargets()) {
            if (target.getUniqueId().equals(owner.get()) || isTeammate(owner.get(), target)) {
                // Cancel the whole hit: no cloud, no damage — a dragon never
                // burns its own rider or its rider's team (§5.6).
                event.setCancelled(true);
                return;
            }
        }
        // The impact goes ahead against a hostile/neutral target, but its
        // lingering vanilla breath cloud must still not burn the owner's
        // side when they step through it. Track it for the friendly shield
        // (E6: getAreaEffectCloud() is present at impact; the cancellation
        // above removes the cloud together with the hit, so only clouds
        // from surviving impacts reach this tracking path).
        if (event.getAreaEffectCloud() != null) {
            controller.trackCloud(event.getAreaEffectCloud().getUniqueId(), owner.get());
        }
    }

    private boolean isTeammate(UUID owner, LivingEntity target) {
        var teams = controller.teamView();
        if (teams == null || !(target instanceof Player other)) {
            return false;
        }
        var ownerTeam = teams.teamOf(owner);
        var otherTeam = teams.teamOf(other.getUniqueId());
        return ownerTeam.isPresent() && ownerTeam.equals(otherTeam);
    }

    // ------------------------------------------------------------------
    // Closed-loop steering guard
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPhaseChange(EnderDragonChangePhaseEvent event) {
        if (!controller.isMounted(event.getEntity().getUniqueId())
                || !controller.config().rideEnabled()) {
            return;
        }
        if (event.getNewPhase() != EnderDragon.Phase.FLY_TO_PORTAL) {
            // Vanilla wants out of the control phase; the rider keeps it.
            event.setNewPhase(EnderDragon.Phase.FLY_TO_PORTAL);
        }
    }
}
