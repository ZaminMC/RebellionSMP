package net.zaminmc.rebellion.items.menu;

import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes inventory interactions for open menus to their {@link MenuHost}.
 *
 * <p>Menus are discovered through the {@code InventoryHolder} of the open
 * top inventory — no per-player tracking map is needed, so there is no stale
 * state to clean up when players disconnect with menus open.</p>
 */
public final class MenuListener implements Listener {

    private final Logger logger;

    public MenuListener(Logger logger) {
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHost host) {
            host.onClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHost host) {
            host.onDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // Holder-pattern routing needs no cleanup; the hook exists so future
        // modules (e.g. menu close actions) have one documented entry point.
        if (event.getInventory().getHolder() instanceof MenuHost host) {
            logger.fine(() -> "Menu " + host.getClass().getSimpleName() + " closed by "
                    + event.getPlayer().getName());
        }
    }
}
