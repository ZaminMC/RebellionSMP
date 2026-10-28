package net.zaminmc.rebellion.dragons.listener;

import io.papermc.paper.event.block.DragonEggFormEvent;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.control.GatewayObserver;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Boss-victory hooks (ADR-0014). Separates the two dragon death flows the
 * master prompt §5.6 keeps apart:
 *
 * <ul>
 *   <li><strong>Boss flow:</strong> a dragon with a live
 *       {@code DragonBattle} — the vanilla fight dragon. Its death may
 *       open the next vanilla End gateway; the {@link GatewayObserver}
 *       watches for that emergence and mints at most one lineage per
 *       observed gateway position. A dragon bound to a lineage (a pet) is
 *       deliberately excluded here even when the vanilla fight adopted it
 *       while players were in the End (ADR-0008 E4: {@code findOrCreateDragon}
 *       adopts any dragon in a ticking fight) — pet deaths belong to
 *       {@link PetLifecycleListener} and can never mint.</li>
 *   <li><strong>Trophy egg:</strong> {@code DragonEggFormEvent} (Paper FQN
 *       — verified relocated, ADR-0008) is vanilla's pedestal trophy, not
 *       the lineage egg. It is logged and left alone; the lineage egg is
 *       issued by this module only.</li>
 * </ul>
 */
public final class BossVictoryListener implements Listener {

    private final LineageService lineages;
    private final GatewayObserver observer;

    public BossVictoryListener(@NotNull LineageService lineages,
            @NotNull GatewayObserver observer) {
        this.lineages = lineages;
        this.observer = observer;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        // Pets never mint, even if the vanilla fight adopted them.
        if (lineages.byEntity(dragon.getUniqueId()).isPresent()) {
            return;
        }
        if (dragon.getDragonBattle() == null) {
            return; // unlinked dragon (e.g. plugin-spawned far from any fight)
        }
        Player victor = event.getEntity().getKiller();
        observer.onVictory(dragon, victor);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVanillaTrophyEgg(DragonEggFormEvent event) {
        // Informational only: the trophy egg is vanilla content; the
        // lineage egg is minted by the observer, keyed on the gateway.
    }
}
