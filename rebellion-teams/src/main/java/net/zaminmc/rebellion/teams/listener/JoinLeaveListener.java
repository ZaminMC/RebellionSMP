package net.zaminmc.rebellion.teams.listener;

import java.util.logging.Logger;
import net.zaminmc.rebellion.teams.TeamService;
import net.zaminmc.rebellion.teams.chat.ChatTracker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cache lifecycle for online players. Join loads the authoritative team +
 * chat state asynchronously (persistence thread); until it lands, the
 * friendly-fire policy allows damage and chat defaults to global — a cold
 * cache never cancels anything. Quit evicts both caches.
 */
public final class JoinLeaveListener implements Listener {

    private final TeamService service;
    private final ChatTracker chat;
    private final Logger logger;
    private String lastFailure; // one diagnostic per player per failure burst

    public JoinLeaveListener(TeamService service, ChatTracker chat, Logger logger) {
        this.service = service;
        this.chat = chat;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        service.loadAndCache(player.getUniqueId())
                .whenComplete((channel, err) -> {
                    if (err == null) {
                        chat.setChannel(player.getUniqueId(), channel);
                    } else if (player.isOnline()) {
                        String message = err.getMessage();
                        if (!message.equals(lastFailure)) {
                            lastFailure = message;
                            logger.warning("Team state for " + player.getName()
                                    + " did not load (damage protection and chat "
                                    + "channels stay permissive until rejoin): " + message);
                        }
                    }
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        service.evict(player.getUniqueId());
        chat.evict(player.getUniqueId());
    }
}
