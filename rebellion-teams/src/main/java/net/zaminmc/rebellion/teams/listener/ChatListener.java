package net.zaminmc.rebellion.teams.listener;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
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
 * pipeline). Channel formats and the rate-limit notice render through the
 * module's messages.yml catalog (ADR-0012).</p>
 */
public final class ChatListener implements Listener {

    private final TeamService service;
    private final ChatTracker chat;
    private final MessageCatalog messages;
    private final Logger logger;

    public ChatListener(TeamService service, ChatTracker chat, MessageCatalog messages, Logger logger) {
        this.service = service;
        this.chat = chat;
        this.messages = messages;
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
        event.renderer(teamRenderer());
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
        event.renderer(proximityRenderer());
    }

    /** @return true when the message may proceed; cancels + notifies otherwise. */
    private boolean rateGate(AsyncChatEvent event, UUID senderId) {
        if (chat.tryConsume(senderId, service.config().messageIntervalSeconds() * 1000L)) {
            return true;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.msg("chat.rate-limited"));
        return false;
    }

    private ChatRenderer teamRenderer() {
        return renderWith("chat.team-format");
    }

    private ChatRenderer proximityRenderer() {
        return renderWith("chat.proximity-format");
    }

    /**
     * Builds a renderer over the configured format template. The format is
     * deserialized per message — chat is rate-gated and low volume, and the
     * live sender/message Components must be resolved through placeholders,
     * not baked into a pre-rendered component.
     */
    private ChatRenderer renderWith(String formatKey) {
        String template = messages.template(formatKey);
        return (source, senderDisplayName, message, viewer) ->
                MiniMessage.miniMessage().deserialize(template,
                        Placeholder.component("sender", senderDisplayName),
                        Placeholder.component("message", message));
    }
}
