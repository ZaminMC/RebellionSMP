package net.zaminmc.rebellion.dragons.listener;

import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageOutcome;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.control.PetController;
import net.zaminmc.rebellion.dragons.control.PetController.Delivery;
import org.bukkit.Bukkit;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Pet lifecycle: the three death paths §5.6 defines, all funneling into the
 * idempotent death CAS (ADR-0009 §5), plus mount bookkeeping.
 *
 * <ul>
 *   <li><strong>Dragon killed directly:</strong> {@code EntityDeathEvent}
 *       on a bound pet — the credited killer receives the lineage egg; an
 *       environmental death parks it in the recovery mailbox with the last
 *       owner as claimant.</li>
 *   <li><strong>Owner dies by PvP:</strong> {@code PlayerDeathEvent} — the
 *       pet's lineage CAS runs for the owner's credited killer first, and
 *       only after the CAS commits does the entity receive its death
 *       presentation ({@code kill()}; bare sourceless {@code damage()} is
 *       filtered by vanilla — ADR-0008 E3). A dragon that dies on its own
 *       in the same instant resolves through its own event; the entity CAS
 *       makes exactly one egg, every other delivery is a typed no-op.</li>
 *   <li><strong>Entity vanished without a death event:</strong>
 *       {@code EntityRemoveEvent} with any cause except {@code UNLOAD}
 *       (chunk unload keeps the entity on disk — proven live, E7) re-runs
 *       the no-killer CAS. Idempotent: a DEATH-cause removal 199 ticks
 *       after the death event is a no-op.</li>
 * </ul>
 */
public final class PetLifecycleListener implements Listener {

    private final Plugin plugin;
    private final LineageService lineages;
    private final PetController controller;

    public PetLifecycleListener(@NotNull Plugin plugin, @NotNull LineageService lineages,
            @NotNull PetController controller) {
        this.plugin = plugin;
        this.lineages = lineages;
        this.controller = controller;
    }

    /** Direct pet kill / environmental pet death. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetDeath(EntityDeathEvent event) {
        Optional<Lineage> bound = lineages.byEntity(event.getEntity().getUniqueId());
        if (bound.isEmpty()) {
            return;
        }
        Lineage lineage = bound.get();
        Player killer = event.getEntity().getKiller();
        UUID entityUuid = lineage.entityUuid();
        if (entityUuid == null) {
            return;
        }
        lineages.processDeath(lineage.lineageId(), entityUuid,
                killer == null ? Optional.empty()
                        : Optional.of(killer.getUniqueId()))
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == LineageOutcome.EGG_RETURNED
                            && result.lineage() != null && killer != null) {
                        deliver(killer.getUniqueId(), result.lineage());
                    }
                }));
    }

    /** Owner death: the valid killer inherits the lineage egg. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOwnerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        for (Lineage lineage : lineages.all()) {
            if (lineage.state() != net.zaminmc.rebellion.dragons.LineageState.HATCHED
                    || !victim.getUniqueId().equals(lineage.owner())) {
                continue;
            }
            UUID entityUuid = lineage.entityUuid();
            if (entityUuid == null) {
                continue;
            }
            lineages.processDeath(lineage.lineageId(), entityUuid,
                            Optional.ofNullable(killer == null ? null : killer.getUniqueId()))
                    .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (result.outcome() != LineageOutcome.EGG_RETURNED
                                || result.lineage() == null) {
                            return; // idempotent no-op or refusal
                        }
                        if (killer != null) {
                            deliver(killer.getUniqueId(), result.lineage());
                        }
                        // Death presentation: the pet dies with its owner.
                        Entity pet = Bukkit.getEntity(entityUuid);
                        if (pet instanceof EnderDragon dragon && pet.isValid()) {
                            dragon.kill();
                        }
                    }));
        }
    }

    /** Non-removal disappearances (plugin discard, void, transformations). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return; // chunk unload: the entity persists on disk (E7)
        }
        Optional<Lineage> bound = lineages.byEntity(event.getEntity().getUniqueId());
        if (bound.isEmpty()) {
            return;
        }
        Lineage lineage = bound.get();
        UUID entityUuid = lineage.entityUuid();
        if (entityUuid != null) {
            lineages.processDeath(lineage.lineageId(), entityUuid, Optional.empty());
        }
    }

    private void deliver(java.util.UUID holder, Lineage lineage) {
        Delivery outcome = controller.deliverEgg(holder, lineage);
        if (outcome == Delivery.UNDELIVERABLE) {
            lineages.markEggUndeliverable(lineage.lineageId(), "killer offline at delivery");
        }
    }

    // ------------------------------------------------------------------
    // Mount bookkeeping (the steer loop's input set)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getMount() instanceof EnderDragon pet)) {
            return;
        }
        Optional<Lineage> bound = lineages.byEntity(pet.getUniqueId());
        if (bound.isEmpty()) {
            return;
        }
        if (event.getEntity() instanceof Player rider
                && rider.getUniqueId().equals(bound.get().owner())) {
            controller.riderMounted(pet.getUniqueId(), rider.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getDismounted() instanceof EnderDragon pet)) {
            return;
        }
        if (lineages.byEntity(pet.getUniqueId()).isEmpty()) {
            return;
        }
        controller.riderDismounted(pet.getUniqueId());
        if (pet.isValid()) {
            controller.settle(pet);
        }
    }
}
