package net.zaminmc.rebellion.rpg.listener;

import net.zaminmc.rebellion.rpg.command.RpgPlaceholders;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Discovery-cache lifecycle for the placeholder contract: one load per
 * join, one evict per quit. Placeholder resolution itself stays a pure
 * in-memory read (PlaceholderContributor); every granting path refreshes
 * the same cache through {@link RpgPlaceholders#load}.
 */
public final class RpgCacheListener implements Listener {

    private final RpgPlaceholders placeholders;

    public RpgCacheListener(@NotNull RpgPlaceholders placeholders) {
        this.placeholders = placeholders;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        placeholders.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        placeholders.evict(event.getPlayer().getUniqueId());
    }
}
