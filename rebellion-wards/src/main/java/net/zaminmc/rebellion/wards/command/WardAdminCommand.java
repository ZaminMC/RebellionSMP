package net.zaminmc.rebellion.wards.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardOutcome;
import net.zaminmc.rebellion.wards.WardResult;
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
 */
public final class WardAdminCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WardService service;
    private final WardsConfig config;
    private final TimedMasking timedMasking;

    public WardAdminCommand(WardService service, WardsConfig config, TimedMasking timedMasking) {
        this.service = service;
        this.config = config;
        this.timedMasking = timedMasking;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rebellion.wards.admin")) {
            sender.sendMessage(MINI.deserialize(
                    "<red>You do not have permission to do that.</red>"));
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
                sender.sendMessage(MINI.deserialize(
                        "<red>That player is not online (name filters resolve online "
                        + "players only).</red>"));
                return;
            }
            UUID owner = target.getUniqueId();
            wards = wards.stream().filter(w -> w.owner().equals(owner)).toList();
        }
        sender.sendMessage(MINI.deserialize("<gray>Watch wards on this server ("
                + wards.size() + "):</gray>"));
        for (Ward ward : wards) {
            sender.sendMessage(MINI.deserialize("<gray>• " + ward.id() + " '</gray><aqua>"
                    + escape(ward.name()) + "<gray>' r" + ward.radius() + " "
                    + (ward.active() ? "<green>active</green>" : "<red>inactive</red>")
                    + (ward.type() == net.zaminmc.rebellion.wards.WardType.DECOY
                            ? " <yellow>(decoy)</yellow>" : "")
                    + " <gray>@ " + escape(ward.world()) + " " + (int) Math.floor(ward.x())
                    + "," + (int) Math.floor(ward.y()) + "," + (int) Math.floor(ward.z())
                    + "</gray>"));
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MINI.deserialize("<red>Usage: /wardadmin remove <ward-id></red>"));
            return;
        }
        UUID wardId;
        try {
            wardId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MINI.deserialize("<red>That is not a ward id.</red>"));
            return;
        }
        CompletableFuture<WardResult> future = service.adminRemove(wardId);
        future.whenComplete((result, err) -> {
            if (err != null) {
                sender.sendMessage(MINI.deserialize(
                        "<red>The action is unavailable right now; nothing was changed.</red>"));
                return;
            }
            String detail = result.ward() != null ? result.ward().name() : "";
            sender.sendMessage(MINI.deserialize(MESSAGES.get(result.outcome())
                    .replace("{detail}", detail)));
        });
    }

    private void mask(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(MINI.deserialize(
                    "<red>Usage: /wardadmin mask <player> <seconds></red>"));
            return;
        }
        if (!config.maskingEnabled()) {
            sender.sendMessage(MINI.deserialize(
                    "<red>Masking countermeasures are disabled in the wards config.</red>"));
            return;
        }
        Player target = sender.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(MINI.deserialize(
                    "<red>That player is not online.</red>"));
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(MINI.deserialize("<red>Seconds must be a number.</red>"));
            return;
        }
        if (seconds < 1 || seconds > 86_400) {
            sender.sendMessage(MINI.deserialize(
                    "<red>Seconds must be between 1 and 86400.</red>"));
            return;
        }
        timedMasking.mask(target.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        sender.sendMessage(MINI.deserialize("<green>Granted " + seconds
                + "s of ward masking to " + target.getName() + ".</green>"));
        target.sendMessage(MINI.deserialize(
                "<gray>You are masked from watch ward detection for " + seconds
                + " seconds.</gray>"));
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(MINI.deserialize(
                "<gray>Usage: /wardadmin <list [player]|remove <id>|mask <player> <seconds>>"
                + "</gray>"));
        return true;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
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

    private static final Map<WardOutcome, String> MESSAGES = Map.ofEntries(
            Map.entry(WardOutcome.OK, "<green>Watch ward '{detail}' removed.</green>"),
            Map.entry(WardOutcome.NOT_FOUND, "<gray>No ward with that id exists.</gray>"),
            Map.entry(WardOutcome.DB_ERROR, "<red>The action is unavailable right now; "
                    + "nothing was changed.</red>"));
}
