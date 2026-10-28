package net.zaminmc.rebellion.wards.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardOutcome;
import net.zaminmc.rebellion.wards.WardResult;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.alert.TimedMasking;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /wardadmin} — staff administration (guarded by
 * {@code rebellion.wards.admin}, enforced again per subcommand):
 * <ul>
 *   <li>{@code list [player]} — every ward, optionally filtered by owner
 *       (UUID authority: the name filter matches online players only).</li>
 *   <li>{@code remove <id>} — removes any ward by its server-issued UUID.</li>
 *   <li>{@code mask <player> <seconds>} — grants timed ward masking to an
 *       ONLINE player (counter-ritual preview + support tool; the UUID comes
 *       from the live Player object, never the name).</li>
 * </ul>
 *
 * <p>All feedback renders through the module's messages.yml catalog
 * (ADR-0012); names, ids and numbers arrive as placeholders.</p>
 */
public final class WardAdminCommand implements CommandExecutor, TabCompleter {

    private final WardService service;
    private final WardsConfig config;
    private final TimedMasking timedMasking;
    private final MessageCatalog messages;

    public WardAdminCommand(WardService service, WardsConfig config, TimedMasking timedMasking,
            MessageCatalog messages) {
        this.service = service;
        this.config = config;
        this.timedMasking = timedMasking;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!sender.hasPermission("rebellion.wards.admin")) {
            sender.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            return usage(sender);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args);
            case "remove" -> remove(sender, args);
            case "mask" -> mask(sender, args);
            default -> {
                return usage(sender);
            }
        }
        return true;
    }

    private void list(CommandSender sender, String[] args) {
        List<Ward> wards = service.all();
        if (args.length >= 2) {
            Player target = sender.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(messages.msg("admin.not-online",
                        Placeholder.unparsed("player", args[1])));
                return;
            }
            UUID owner = target.getUniqueId();
            wards = wards.stream().filter(w -> w.owner().equals(owner)).toList();
        }
        sender.sendMessage(messages.raw("admin.list-header",
                Placeholder.unparsed("count", String.valueOf(wards.size()))));
        for (Ward ward : wards) {
            sender.sendMessage(messages.raw("admin.list-line",
                    Placeholder.unparsed("id", String.valueOf(ward.id())),
                    Placeholder.unparsed("ward", ward.name()),
                    Placeholder.unparsed("radius", String.valueOf(ward.radius())),
                    Placeholder.component("state", messages.raw(
                            ward.active() ? "state.active" : "state.inactive")),
                    Placeholder.component("decoy", ward.type() == WardType.DECOY
                            ? messages.raw("state.decoy-tag") : Component.empty()),
                    Placeholder.unparsed("world", ward.world()),
                    Placeholder.unparsed("x", String.valueOf((int) Math.floor(ward.x()))),
                    Placeholder.unparsed("y", String.valueOf((int) Math.floor(ward.y()))),
                    Placeholder.unparsed("z", String.valueOf((int) Math.floor(ward.z())))));
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.msg("admin.remove-usage"));
            return;
        }
        UUID wardId;
        try {
            wardId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(messages.msg("admin.not-an-id"));
            return;
        }
        CompletableFuture<WardResult> future = service.adminRemove(wardId);
        future.whenComplete((result, err) -> {
            if (err != null) {
                sender.sendMessage(messages.msg("common.unavailable"));
                return;
            }
            // Same deterministic key scheme, under the admin namespace:
            // admin.adminremove.<outcome> (ADR-0012).
            String key = switch (result.outcome()) {
                case OK -> "admin.adminremove.ok";
                case DB_ERROR -> "common.unavailable";
                default -> "admin.adminremove."
                        + result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-');
            };
            String detail = result.ward() != null ? result.ward().name() : "";
            sender.sendMessage(messages.msg(key,
                    Placeholder.unparsed("ward", detail),
                    Placeholder.unparsed("detail", detail)));
        });
    }

    private void mask(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(messages.msg("admin.mask.usage"));
            return;
        }
        if (!config.maskingEnabled()) {
            sender.sendMessage(messages.msg("admin.mask.disabled"));
            return;
        }
        Player target = sender.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(messages.msg("admin.mask.not-online",
                    Placeholder.unparsed("player", args[1])));
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.msg("admin.mask.seconds-invalid"));
            return;
        }
        if (seconds < 1 || seconds > 86_400) {
            sender.sendMessage(messages.msg("admin.mask.seconds-range"));
            return;
        }
        timedMasking.mask(target.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        sender.sendMessage(messages.msg("admin.mask.granted",
                Placeholder.unparsed("seconds", String.valueOf(seconds)),
                Placeholder.unparsed("player", target.getName())));
        target.sendMessage(messages.msg("admin.mask.granted-target",
                Placeholder.unparsed("seconds", String.valueOf(seconds))));
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(messages.msg("admin.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("list", "remove", "mask")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    completions.add(sub);
                }
            }
        }
        return completions;
    }
}
