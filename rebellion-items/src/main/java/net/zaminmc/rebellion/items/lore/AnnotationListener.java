package net.zaminmc.rebellion.items.lore;

import java.util.logging.Logger;
import net.zaminmc.rebellion.items.ItemsConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Applies rarity annotation at the configured item flows.
 *
 * <p>Bounded by design (master prompt §5.4): no chunk scans, no per-tick
 * work, no full inventory sweeps outside the configured join pass. Each
 * handler only visits the stacks it is handed by the event. Future flows
 * (drops, crafting/smithing output, container access) will be added behind
 * explicit config switches when Phase 2 continues.</p>
 */
public final class AnnotationListener implements Listener {

    private final AnnotateService annotator;
    private volatile ItemsConfig config;
    private final Logger logger;

    public AnnotationListener(AnnotateService annotator, ItemsConfig config, Logger logger) {
        this.annotator = annotator;
        this.config = config;
        this.logger = logger;
    }

    /** Atomic snapshot swap on reload. */
    public void updateConfig(ItemsConfig config) {
        this.config = config;
    }

    private boolean mayAnnotate(ItemStack stack) {
        ItemsConfig cfg = config;
        switch (cfg.annotationMode()) {
            case CUSTOM_ONLY -> {
                // Only plugin-created custom items.
                return annotator.identity(stack)
                        .map(id -> id.itemId() != null)
                        .orElse(false);
            }
            case MANAGED_ITEMS -> {
                // Custom items plus stacks this plugin family already manages.
                return annotator.identity(stack).isPresent();
            }
            case ALL_ITEMS -> {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.player.PlayerAttemptPickupItemEvent event) {
        if (!config.pickupAnnotation()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (mayAnnotate(stack)) {
            annotator.annotate(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.joinNormalization()) {
            return;
        }
        Player player = event.getPlayer();
        int touched = 0;
        for (ItemStack stack : player.getInventory()) {
            if (stack != null && mayAnnotate(stack) && annotator.annotate(stack)) {
                touched++;
            }
        }
        final int count = touched;
        if (count > 0) {
            logger.fine(() -> "Join normalization annotated " + count
                    + " stack(s) for " + player.getName());
        }
    }
}
