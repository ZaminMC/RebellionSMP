package net.zaminmc.rebellion.rpg.listener;

import java.util.UUID;
import net.zaminmc.rebellion.rpg.controller.SoulController;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Soul-encounter listener (ADR-0017): the entity half's event wiring.
 *
 * <ul>
 *   <li><strong>Protection:</strong> a live soul is a damage-protected
 *       quest giver - every incoming damage event on the entity is
 *       cancelled while its encounter row is the authority for the
 *       outcome. The entity is replaceable; the row is not.</li>
 *   <li><strong>Completion:</strong> right-click a live soul to help it.
 *       The one-winner CAS inside the service decides who wins; losers
 *       resolve to the typed no-op and the entity still leaves (the
 *       encounter is resolved either way).</li>
 *   <li><strong>Vanished souls:</strong> a soul entity that disappears
 *       for any cause except chunk-unload despawns its encounter row
 *       (the E7 lesson: UNLOAD means the entity persists on disk, every
 *       other cause means the row can no longer complete).</li>
 * </ul>
 */
public final class SoulListener implements Listener {

    private final SoulController souls;

    public SoulListener(@NotNull SoulController souls) {
        this.souls = souls;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulDamaged(EntityDamageEvent event) {
        if (souls.isLiveSoul(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSoulInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // both hands fire; run the completion once
        }
        UUID entity = event.getRightClicked().getUniqueId();
        if (!souls.isLiveSoul(entity)) {
            return;
        }
        event.setCancelled(true);
        Player helper = event.getPlayer();
        souls.interactComplete(helper, entity);
    }

    @EventHandler
    public void onSoulRemoved(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return; // chunk unload: the entity persists with its chunk
        }
        souls.soulRemoved(event.getEntity().getUniqueId(), event.getCause());
    }
}
