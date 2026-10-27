package net.zaminmc.rebellion.teams.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.zaminmc.rebellion.teams.TeamService;
import net.zaminmc.rebellion.teams.chat.ChatRouter;
import net.zaminmc.rebellion.teams.chat.ChatTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Channel routing for Paper's {@link AsyncChatEvent} (vanilla-client
 * compatible text chat; no voice claims).
 *
 * <p>Global chat is untouched. Team chat restricts viewers to the sender's
 * online teammates (no world scoping) and adds a channel tag. Proximity
 * chat restricts viewers to same-world players within the configured
 * radius. Both non-global channels are rate limited per player. Team and
 * proximity messages are visible to their recipient players only (the
 * console viewer is removed — the server still logs chat through its own
 * pipeline).</p>
 */
public final class ChatListener implements Listener {

    private final TeamService service;
    private final ChatTracker chat;
    private final Logger logger;

    public ChatListener(TeamService service, ChatTracker chat, Logger logger) {
        this.service = service;
        this.chat = chat;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();
        String channel = chat.channel(senderId, service.config().defaultChannel());
        switch (channel) {
            case ChatTracker.CHANNEL_TEAM -> routeTeam(event, senderId);
            case ChatTracker.CHANNEL_PROXIMITY -> routeProximity(event, senderId);
            default -> {
                // global: vanilla behavior stays untouched
            }
        }
    }

    private void routeTeam(AsyncChatEvent event, UUID senderId) {
        if (!rateGate(event, senderId)) {
            return;
        }
        Set<UUID> recipients = ChatRouter.teamRecipients(senderId,
                service.cache().onlineTeammates(senderId));
        event.viewers().removeIf(audience -> !(audience instanceof Player player
                && recipients.contains(player.getUniqueId())));
        event.renderer(ChatListener::teamRenderer);
    }

    private void routeProximity(AsyncChatEvent event, UUID senderId) {
        if (!service.config().proximityEnabled()) {
            return; // channel disabled: fall back to global behavior
        }
        if (!rateGate(event, senderId)) {
            return;
        }
        // The event's viewer set is the server's own recipient snapshot —
        // iterate it (never a global player list) before filtering it.
        List<ChatRouter.Candidate> candidates = new ArrayList<>();
        for (var audience : event.viewers()) {
            if (audience instanceof Player online) {
                candidates.add(new ChatRouter.Candidate(online.getUniqueId(),
                        online.getWorld().getName(),
                        online.getLocation().getX(), online.getLocation().getZ()));
            }
        }
        Set<UUID> recipients = ChatRouter.proximityRecipients(senderId, candidates,
                service.config().proximityRadius());
        event.viewers().removeIf(audience -> !(audience instanceof Player player
                && recipients.contains(player.getUniqueId())));
        event.renderer(ChatListener::proximityRenderer);
    }

    /** @return true when the message may proceed; cancels + notifies otherwise. */
    private boolean rateGate(AsyncChatEvent event, UUID senderId) {
        if (chat.tryConsume(senderId, service.config().messageIntervalSeconds() * 1000L)) {
            return true;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "You are sending chat messages too quickly.", NamedTextColor.RED));
        return false;
    }

    private static Component teamRenderer(Object source, Component senderDisplayName,
            Component message, Object viewer) {
        return Component.text("[Team] ", NamedTextColor.AQUA)
                .append(senderDisplayName)
                .append(Component.text(": "))
                .append(message);
    }

    private static Component proximityRenderer(Object source, Component senderDisplayName,
            Component message, Object viewer) {
        return Component.text("<", NamedTextColor.WHITE)
                .append(senderDisplayName)
                .append(Component.text("> "))
                .append(message);
    }
}
