package net.zaminmc.rebellion.rpg.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.rpg.RpgService;
import net.zaminmc.rebellion.rpg.db.RpgStore.EventRow;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /rpgadmin} — audit and recovery for the ritual surface (master
 * prompt §5.3 admin tools / §12 recovery). The whole family is read-only
 * except session revocation, which is itself an audited, idempotent
 * service CAS. Nothing here mints discoveries, sessions or effects.
 */
public final class RpgAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("events", "revoke");

    private final RpgService service;
    private final RitualCommand playerCommand;

    public RpgAdminCommand(@NotNull RpgService service,
            @NotNull RitualCommand playerCommand) {
        this.service = service;
        this.playerCommand = playerCommand;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!sender.hasPermission("rebellion.rpg.admin")) {
            sender.sendMessage(playerCommand.messages().msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(playerCommand.messages().msg("admin.usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "events" -> events(sender, args);
            case "revoke" -> revoke(sender, args);
            default -> sender.sendMessage(playerCommand.messages().msg("admin.usage"));
        }
        return true;
    }

    /** Recent ritual events for a player (audit view; offline is legal). */
    private void events(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(playerCommand.messages().msg("admin.usage"));
            return;
        }
        UUID player = resolveKnown(args[1]);
        if (player == null) {
            sender.sendMessage(playerCommand.messages().msg("admin.player-unknown"));
            return;
        }
        service.eventsFor(player).thenAccept(rows ->
                Bukkit.getScheduler().runTask(playerCommand.plugin(), () -> {
                    MessageCatalog messages = playerCommand.messages();
                    if (rows.isEmpty()) {
                        sender.sendMessage(messages.msg("admin.events-none"));
                        return;
                    }
                    int limit = Math.min(rows.size(), 10);
                    sender.sendMessage(messages.msg("admin.events-header",
                            Placeholder.unparsed("player", args[1]),
                            Placeholder.unparsed("count", String.valueOf(limit))));
                    for (int i = rows.size() - limit; i < rows.size(); i++) {
                        EventRow row = rows.get(i);
                        sender.sendMessage(messages.msg("admin.events-line",
                                Placeholder.unparsed("action", row.type()),
                                Placeholder.unparsed("detail",
                                        row.detail() == null ? "-" : row.detail()),
                                Placeholder.unparsed("outcome", row.outcome())));
                    }
                }));
    }

    /** Revokes a tracking session by id (audited, idempotent CAS). */
    private void revoke(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(playerCommand.messages().msg("admin.revoke.usage"));
            return;
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(playerCommand.messages().msg("admin.revoke.not-found"));
            return;
        }
        String reason = args.length > 2 ? String.join(" ",
                java.util.Arrays.copyOfRange(args, 2, args.length)) : "admin";
        service.revokeSession(sessionId, reason).thenAccept(result ->
                Bukkit.getScheduler().runTask(playerCommand.plugin(), () -> {
                    MessageCatalog messages = playerCommand.messages();
                    switch (result.outcome()) {
                        case SESSION_REVOKED -> sender.sendMessage(
                                messages.msg("admin.revoke.ok",
                                        Placeholder.unparsed("sessionId", args[1]),
                                        Placeholder.unparsed("reason", reason)));
                        case SESSION_REVOKE_NOOP -> sender.sendMessage(
                                messages.msg("admin.revoke.noop"));
                        default -> sender.sendMessage(
                                messages.msg("admin.revoke.not-found"));
                    }
                }));
    }

    /** Online exact match, then the cached offline record; null = unknown. */
    private UUID resolveKnown(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("revoke")) {
            return List.of("<sessionId>");
        }
        return List.of();
    }
}
