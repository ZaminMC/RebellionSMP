package net.zaminmc.rebellion.dragons.listener;

import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.egg.EggItems;
import net.zaminmc.rebellion.dragons.egg.EggItems.StampedIdentity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Physical lineage-egg armor (master prompt §5.6: "Egg state cannot be
 * destroyed by lava, fire, void, explosions, dispensers, hoppers, item
 * despawn, or accidental inventory loss"). Every destructive path for a
 * stack carrying a <em>live</em> egg stamp is cancelled outright:
 *
 * <ul>
 *   <li>{@code EntityDamageEvent}/{@code EntityCombustEvent} on the dropped
 *       item — fire, lava, void, explosions, cactus;</li>
 *   <li>{@code ItemDespawnEvent} — a live egg never despawns;</li>
 *   <li>{@code InventoryMoveItemEvent} — hoppers and hopper minecarts
 *       cannot suck it away;</li>
 *   <li>{@code BlockDispenseEvent} — dispensers and droppers refuse it;</li>
 *   <li>{@code EntityPortalEvent} — it stays in its dimension (no portal
 *       teleport of the item entity).</li>
 * </ul>
 *
 * <p>Identification is stamp-based, not material-based: only a stack whose
 * PDC {@code egg_instance} resolves against a still-egg lineage row in the
 * live mirror is protected. Stale copies (superseded instance stamps) lose
 * protection — they are inert souvenirs, and letting them burn is exactly
 * the anti-clone posture. The registry row, never the item, is the
 * authority: if a live egg is somehow lost anyway, the lineage is
 * recoverable through claim/admin-route into a fresh stamped egg.</p>
 */
public final class EggProtectionListener implements Listener {

    private final LineageService lineages;
    private final EggItems eggs;

    public EggProtectionListener(@NotNull LineageService lineages, @NotNull EggItems eggs,
            @NotNull Plugin plugin) {
        this.lineages = lineages;
        this.eggs = eggs;
    }

    /** @return true when the stack carries the LIVE stamp of a still-egg lineage. */
    private boolean isLiveEgg(@Nullable ItemStack stack) {
        StampedIdentity stamped = eggs.read(stack);
        return stamped != null && lineages.byEggInstance(stamped.eggInstance()).isPresent();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item
                && isLiveEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item
                && isLiveEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggItemDespawn(ItemDespawnEvent event) {
        if (isLiveEgg(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggHopperMove(InventoryMoveItemEvent event) {
        if (isLiveEgg(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggDispense(BlockDispenseEvent event) {
        if (isLiveEgg(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggItemPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Item item
                && isLiveEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }
}
