package net.zaminmc.rebellion.items.lore;

import java.util.logging.Logger;
import net.zaminmc.rebellion.items.ItemsConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Applies rarity annotation at the configured item flows.
 *
 * <p>Bounded by design (master prompt §5.4): no chunk scans, no per-tick
 * work. Each handler only visits the stacks it is handed by the event; the
 * two inventory passes (join, container open) are bounded by the inventory
 * size and each stack exactly once. Every flow is an explicit config
 * opt-in.</p>
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

    /** Bounded: only the dropped stacks this event carries, never a chunk scan. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!config.blockDropAnnotation()) {
            return;
        }
        for (org.bukkit.entity.Item drop : event.getItems()) {
            ItemStack stack = drop.getItemStack();
            if (mayAnnotate(stack)) {
                annotator.annotate(stack);
            }
        }
    }

    /** Crafting output: annotate the result representation the event carries. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!config.craftingOutputAnnotation()) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result != null && mayAnnotate(result)) {
            annotator.annotate(result);
        }
    }

    /** Smithing output: single prepared result stack per event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithing(PrepareSmithingEvent event) {
        if (!config.smithingOutputAnnotation()) {
            return;
        }
        ItemStack result = event.getResult();
        if (result != null && mayAnnotate(result)) {
            annotator.annotate(result);
        }
    }

    /** Container open: one bounded pass over the top inventory's stacks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!config.containerOpenAnnotation()) {
            return;
        }
        int touched = 0;
        for (ItemStack stack : event.getInventory()) {
            if (stack != null && mayAnnotate(stack) && annotator.annotate(stack)) {
                touched++;
            }
        }
        final int count = touched;
        if (count > 0) {
            logger.fine(() -> "Container-open normalization annotated " + count
                    + " stack(s) for " + event.getPlayer().getName());
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
