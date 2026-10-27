package net.zaminmc.rebellion.teams.command;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * persisted through the service, so it survives reconnects.
 */
public final class ChatCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> CHANNELS = List.of("global", "team", "proximity");

    private final TeamService service;
    private final ChatTracker chat;

    public ChatCommand(TeamService service, ChatTracker chat) {
        this.service = service;
        this.chat = chat;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Chat channels are player-only.</red>");
            return true;
        }
        if (!player.hasPermission("rebellion.teams.chat")) {
            send(sender, "<red>You do not have permission to do that.</red>");
            return true;
        }
        if (args.length != 1) {
            send(player, "<red>Usage: /chat <global|team|proximity></red>");
            return true;
        }
        String channel = args[0].toLowerCase(Locale.ROOT);
        switch (channel) {
            case ChatTracker.CHANNEL_GLOBAL -> switchChannel(player, channel);
            case ChatTracker.CHANNEL_TEAM -> {
                if (service.cache().lookup(player.getUniqueId())
                        .flatMap(o -> o).isEmpty()) {
                    send(player, "<red>You need a team to use team chat "
                            + "(create one with /team create).</red>");
                    return true;
                }
                switchChannel(player, channel);
            }
            case ChatTracker.CHANNEL_PROXIMITY -> {
                if (!service.config().proximityEnabled()) {
                    send(player, "<red>The proximity channel is disabled on this server.</red>");
                    return true;
                }
                switchChannel(player, channel);
            }
            default -> send(player, "<red>Unknown channel. Use global, team or proximity.</red>");
        }
        return true;
    }

    private void switchChannel(Player player, String channel) {
        chat.setChannel(player.getUniqueId(), channel);
        // Persist for reconnects; the in-memory tracker is already updated
        // so chat routing never waits on the database.
        service.saveChatChannel(player.getUniqueId(), channel);
        send(player, switch (channel) {
            case ChatTracker.CHANNEL_TEAM -> "<green>Chat channel: team "
                    + "(messages go to your online teammates).</green>";
            case ChatTracker.CHANNEL_PROXIMITY -> "<green>Chat channel: proximity "
                    + "(messages go to players within "
                    + Math.round(service.config().proximityRadius()) + " blocks).</green>";
            default -> "<green>Chat channel: global.</green>";
        });
    }

    private static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
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
