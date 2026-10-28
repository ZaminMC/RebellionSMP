package net.zaminmc.rebellion.alchemy.listener;

import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cache lifecycle (ADR-0020): load every slice per join, evict per quit —
 * the RpgCacheListener discipline. No UUID may linger after quit (the
 * master prompt's no-stale-Player rule).
 */
public final class AlchemyCacheListener implements Listener {

    private final AlchemyPlayerCache cache;

    public AlchemyCacheListener(AlchemyPlayerCache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        cache.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.evict(event.getPlayer().getUniqueId());
    }
}
