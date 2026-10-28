package net.zaminmc.rebellion.teams.command;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.teams.TeamService;
import net.zaminmc.rebellion.teams.chat.ChatTracker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /chat <global|team|proximity>} — switches the player's chat channel.
 * Team channel requires membership (checked against the hot-path cache);
 * proximity channel requires {@code chat.proximity-enabled}. The choice is
 * persisted through the service, so it survives reconnects. Feedback renders
 * through the module's messages.yml catalog (ADR-0012).
 */
public final class ChatCommand implements CommandExecutor, TabCompleter {

    private static final List<String> CHANNELS = List.of("global", "team", "proximity");

    private final TeamService service;
    private final ChatTracker chat;
    private final MessageCatalog messages;

    public ChatCommand(TeamService service, ChatTracker chat, MessageCatalog messages) {
        this.service = service;
        this.chat = chat;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("chat.player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.teams.chat")) {
            sender.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(messages.msg("chat.usage"));
            return true;
        }
        String channel = args[0].toLowerCase(Locale.ROOT);
        switch (channel) {
            case ChatTracker.CHANNEL_GLOBAL -> switchChannel(player, channel);
            case ChatTracker.CHANNEL_TEAM -> {
                if (service.cache().lookup(player.getUniqueId())
                        .flatMap(o -> o).isEmpty()) {
                    sender.sendMessage(messages.msg("chat.need-team"));
                    return true;
                }
                switchChannel(player, channel);
            }
            case ChatTracker.CHANNEL_PROXIMITY -> {
                if (!service.config().proximityEnabled()) {
                    sender.sendMessage(messages.msg("chat.proximity-disabled"));
                    return true;
                }
                switchChannel(player, channel);
            }
            default -> sender.sendMessage(messages.msg("chat.unknown-channel"));
        }
        return true;
    }

    private void switchChannel(Player player, String channel) {
        chat.setChannel(player.getUniqueId(), channel);
        // Persist for reconnects; the in-memory tracker is already updated
        // so chat routing never waits on the database.
        service.saveChatChannel(player.getUniqueId(), channel);
        String key = switch (channel) {
            case ChatTracker.CHANNEL_TEAM -> "chat.switched-team";
            case ChatTracker.CHANNEL_PROXIMITY -> "chat.switched-proximity";
            default -> "chat.switched-global";
        };
        player.sendMessage(messages.msg(key,
                Placeholder.unparsed("radius",
                        String.valueOf(Math.round(service.config().proximityRadius())))));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return CHANNELS.stream().filter(c -> c.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
