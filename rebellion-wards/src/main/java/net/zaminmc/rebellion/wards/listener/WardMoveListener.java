package net.zaminmc.rebellion.wards.listener;

import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.feed.MovementFeed;
import net.zaminmc.rebellion.wards.feed.PositionSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The movement side of the sampling pipeline. This handler is the hottest
 * code in the module (PlayerMoveEvent fires constantly), so it does the
 * minimum the master prompt's performance section allows:
 *
 * <ol>
 *   <li>Compare block coordinates of from/to (same-block moves are ignored
 *       — head rotation, swimming bob, micro-steps).</li>
 *   <li>O(1) feed mark with the new position. No index query, no ward list,
 *       no team lookup, no allocation beyond one snapshot record.</li>
 * </ol>
 *
 * <p>World changes reset detection state (documented in
 * docs/CONFIGURATION.md "Cross-world behavior"): the player left every ward
 * of the old world; the new world detects them fresh. Quit drops the feed
 * entry and every (player, ward) pair — no leaks, no stale insiders.</p>
 */
public final class WardMoveListener implements Listener {

    private final MovementFeed feed;
    private final WardService service;

    public WardMoveListener(MovementFeed feed, WardService service) {
        this.feed = feed;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null
                || from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // same block: rotation/sub-block movement is not meaningful
        }
        mark(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        // Dimension/world transition: leave all old-world wards silently and
        // start fresh detection in the new world.
        service.clearPlayerDetection(event.getPlayer().getUniqueId());
        mark(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        feed.remove(player.getUniqueId());
        service.clearPlayerDetection(player.getUniqueId());
    }

    private void mark(Player player, Location location) {
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        if (world.isEmpty()) {
            return;
        }
        feed.update(player.getUniqueId(),
                PositionSnapshot.of(world, location.getX(), location.getY(), location.getZ()));
    }
}
